package io.github.hyperprivacy.ui.apps

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.ui.ScopeResult
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Per-app LSPosed scope for tracker protection: which apps the module is injected into, requests
 * for single apps (only ever on a user tap) and removal when protection is turned off. Requires
 * `staticScope=false` in module.prop: with a static scope LSPosed refuses packages outside
 * scope.list. State is confined to [worker]; binder callbacks hop onto it.
 */
internal class ScopeRequests(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val service: () -> XposedService?,
    private val onChange: () -> Unit,
) {
    private val results = Channel<ScopeResult>(Channel.BUFFERED)

    /** One-shot request outcomes. */
    val messages: Flow<ScopeResult> = results.receiveAsFlow()

    /** Packages in the module scope as last read; empty when unknown. */
    var granted: Set<String> = emptySet()
        private set

    /** Packages with a request awaiting LSPosed's answer. */
    val requesting: MutableSet<String> = HashSet()

    fun check() {
        granted = service()?.let { runCatching { it.scope.toSet() }.getOrNull() }.orEmpty()
    }

    fun request(packageName: String) {
        val target = service() ?: return
        if (packageName in granted || !requesting.add(packageName)) return
        val listener = Listener(packageName)
        runCatching { target.requestScope(listOf(packageName), listener) }
            .onSuccess {
                scope.launch(worker) {
                    delay(REQUEST_TIMEOUT_MS)
                    listener.finish(ScopeResult.NO_RESPONSE)
                }
            }
            .onFailure { listener.finish(ScopeResult.UNSUPPORTED) }
    }

    /** Takes a user app back out of the scope; catalog targets keep theirs. */
    fun remove(packageName: String) {
        if (packageName in Catalog.scope || packageName !in granted) return
        val target = service() ?: return
        if (runCatching { target.removeScope(listOf(packageName)) }.isSuccess) granted = granted - packageName
    }

    private inner class Listener(private val packageName: String) : XposedService.OnScopeEventListener {
        // Worker only.
        private var answered = false

        fun finish(result: ScopeResult) {
            if (answered) return
            answered = true
            requesting -= packageName
            val before = granted
            check()
            if (result == ScopeResult.APPROVED && granted.isEmpty()) granted = before + packageName
            results.trySend(result)
            onChange()
        }

        override fun onScopeRequestApproved(approved: List<String>) {
            scope.launch(worker) { finish(ScopeResult.APPROVED) }
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
