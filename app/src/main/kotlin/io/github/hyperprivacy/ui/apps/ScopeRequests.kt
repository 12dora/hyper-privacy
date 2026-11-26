package io.github.hyperprivacy.ui.apps

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.scope.Protection
import io.github.hyperprivacy.core.scope.ScopeDecisions
import io.github.hyperprivacy.ui.ScopeResult
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Per-app LSPosed scope for tracker protection: which apps the module is injected into, requests
 * for single apps (only ever on a user tap) and removal when protection is turned off. Requires
 * `staticScope=false` in module.prop: with a static scope LSPosed refuses packages outside
 * scope.list.
 *
 * The scope is derived state: whether protection is on lives only in the remote preferences, and
 * [protection] reads them (tri-state: an unreadable preference is [Protection.UNKNOWN] and never
 * removes anything). Every answer from LSPosed, however late (after the request timed out, or
 * after protection was turned off and on again), re-reads the scope and is decided from the state
 * at that moment ([ScopeDecisions.onAnswer]): an app whose protection is definitely off is taken
 * back out. State is confined to [worker]; binder callbacks hop onto it.
 */
internal class ScopeRequests(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val service: () -> XposedService?,
    private val protection: (String) -> Protection,
    private val onChange: () -> Unit,
) {
    private val results = Channel<ScopeResult>(Channel.BUFFERED)

    /** One-shot request outcomes. */
    val messages: Flow<ScopeResult> = results.receiveAsFlow()

    /** Packages in the module scope as last read successfully; empty until then. */
    var granted: Set<String> = emptySet()
        private set

    /** Whether [granted] comes from a successful read. */
    private var known = false

    /** Open requests by package: each awaits LSPosed's answer or its timeout. */
    private val pending = HashMap<String, Request>()

    /** Packages with a request awaiting LSPosed's answer. */
    val requesting: Set<String> get() = pending.keys

    /** Re-reads the scope; a failed read keeps the previous answer. */
    fun check() {
        val read = service()?.let { runCatching { it.scope.toSet() }.getOrNull() } ?: return
        granted = read
        known = true
    }

    /**
     * Takes every app in [candidates] (the listed user apps) whose protection is definitely off
     * back out of the scope, e.g. one left behind by a request approved after protection was turned
     * off. Apps whose protection cannot be read right now stay.
     */
    fun reconcile(candidates: Collection<String>) {
        if (!known) return
        candidates.filter { it in granted && it !in pending && ScopeDecisions.shouldRemove(protection(it)) }
            .forEach(::remove)
    }

    fun request(packageName: String) {
        val target = service() ?: return
        if (packageName in granted || packageName in pending) return
        val request = Request(packageName)
        pending[packageName] = request
        runCatching { target.requestScope(listOf(packageName), request) }
            .onSuccess {
                request.timeout = scope.launch(worker) {
                    delay(REQUEST_TIMEOUT_MS)
                    request.finish(ScopeResult.NO_RESPONSE)
                }
            }
            .onFailure { request.finish(ScopeResult.UNSUPPORTED) }
    }

    /** Takes a user app back out of the scope (catalog targets keep theirs) and drops its open request. */
    fun remove(packageName: String) {
        if (packageName in Catalog.scope) return
        pending.remove(packageName)?.cancel()
        val target = service() ?: return
        if (runCatching { target.removeScope(listOf(packageName)) }.isSuccess) granted = granted - packageName
    }

    private inner class Request(private val packageName: String) : XposedService.OnScopeEventListener {
        // Worker only.
        var timeout: Job? = null
        private var answered = false
        private var cancelled = false

        fun cancel() {
            cancelled = true
            timeout?.cancel()
        }

        fun finish(result: ScopeResult) {
            val current = pending[packageName] === this && !cancelled
            if (pending[packageName] === this) pending.remove(packageName)
            timeout?.cancel()
            val first = !answered
            answered = true
            check()
            val approved = result == ScopeResult.APPROVED
            // LSPosed's own answer wins over a scope read that may predate it.
            if (approved) granted = granted + packageName
            when (ScopeDecisions.onAnswer(approved, protection(packageName), current, first)) {
                ScopeDecisions.Answer.REMOVE -> if (packageName in granted) remove(packageName)

                ScopeDecisions.Answer.REPORT -> {
                    // A late approval of a superseded request also settles the newer one.
                    if (approved) pending.remove(packageName)?.settle()
                    results.trySend(result)
                }

                ScopeDecisions.Answer.IGNORE -> Unit
            }
            onChange()
        }

        /** Answered through an older request for the same app: stop waiting, stay silent. */
        fun settle() {
            answered = true
            timeout?.cancel()
        }

        override fun onScopeRequestApproved(approved: List<String>) {
            scope.launch(worker) {
                finish(if (packageName in approved) ScopeResult.APPROVED else ScopeResult.DENIED)
            }
        }

        override fun onScopeRequestFailed(message: String) {
            scope.launch(worker) { finish(ScopeResult.DENIED) }
        }
    }

    private companion object {
        /** LSPosed answers through a notification the user may take a while to open. */
        const val REQUEST_TIMEOUT_MS = 60_000L
    }
}
