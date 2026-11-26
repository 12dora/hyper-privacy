package io.github.hyperprivacy.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.scope.DeviceCoverage
import io.github.hyperprivacy.status.bootTimeMillis
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Outcome of a scope request, shown once as a snackbar. */
internal enum class ScopeResult {
    /** Apps were added; they pick the module up when they next start. */
    APPROVED,

    /** system_server was added: the device must restart. */
    APPROVED_REBOOT,

    /** LSPosed refused, or the user declined. */
    DENIED,

    /** LSPosed did not answer in time; its notification may still be pending. */
    NO_RESPONSE,

    /** The framework could not take the request at all. */
    UNSUPPORTED,
}

/** Installed targets the module is not injected into. Empty means the Home row is hidden. */
@Immutable
internal data class ScopeUi(
    val missingApps: Int = 0,
    val systemMissing: Boolean = false,
    val requesting: Boolean = false,
) {
    val isEmpty: Boolean get() = missingApps == 0 && !systemMissing
}

/**
 * Keeps the UI in step with the device: which catalog apps are installed ([refreshApps]) and whether
 * the LSPosed scope covers them. LSPosed does not add apps that are installed after the module was
 * enabled, so the gap is computed on resume and closed only when the user asks ([request]); nothing
 * is ever requested automatically.
 *
 * State is confined to [worker]; binder callbacks hop onto it. [service], [installed] and
 * [refreshApps] are called there too.
 */
internal class ScopeSync(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val rebootFlag: RebootFlag,
    private val service: () -> XposedService?,
    private val installed: () -> Set<String>?,
    private val refreshApps: () -> Unit,
    private val onChange: () -> Unit,
) {
    private val results = Channel<ScopeResult>(Channel.BUFFERED)

    /** One-shot request outcomes. */
    val messages: Flow<ScopeResult> = results.receiveAsFlow()

    private var missing: List<String> = emptyList()
    private var pending: Request? = null

    val ui: ScopeUi
        get() = ScopeUi(
            missingApps = missing.count { it != Catalog.SYSTEM_SCOPE },
            systemMissing = Catalog.SYSTEM_SCOPE in missing,
            requesting = pending != null,
        )

    /** Whether system_server was added to the scope since this boot started. */
    val rebootPending: Boolean get() = rebootFlag.isPending

    /**
     * Re-reads installed apps and the granted scope. Called on resume and when a package is added or
     * removed while the UI is visible; safe from any thread.
     */
    fun refresh() {
        scope.launch(worker) {
            refreshApps()
            check()
            onChange()
        }
    }

    /** Asks LSPosed to add the missing entries; it shows its own approval prompt. Only ever on a user tap. */
    fun request() {
        scope.launch(worker) { requestNow() }
    }

    /** Re-reads the granted scope; worker only. Unknown (no service, binder error) hides the row. */
    fun check() {
        val apps = installed()
        val granted = service()?.let { runCatching { it.scope }.getOrNull() }
        missing = if (apps == null || granted == null) emptyList() else DeviceCoverage.missingScope(apps, granted)
    }

    private fun requestNow() {
        val target = service()
        if (pending != null || missing.isEmpty() || target == null) return
        val request = Request(missing)
        pending = request
        onChange()
        runCatching { target.requestScope(request.packages, request) }
            .onSuccess {
                scope.launch(worker) {
                    delay(REQUEST_TIMEOUT_MS)
                    if (pending === request) finish(request, ScopeResult.NO_RESPONSE)
                }
            }
            .onFailure { finish(request, ScopeResult.UNSUPPORTED) }
    }

    /** Only the first answer to a request is shown; later ones (per-package callbacks) just refresh. */
    private fun finish(request: Request, result: ScopeResult) {
        if (!request.answered) {
            request.answered = true
            results.trySend(result)
        }
        if (pending === request) pending = null
        check()
        onChange()
    }

    private inner class Request(val packages: List<String>) : XposedService.OnScopeEventListener {
        // Worker only.
        var answered = false

        override fun onScopeRequestApproved(approved: List<String>) {
            scope.launch(worker) {
                val system = Catalog.SYSTEM_SCOPE in approved
                if (system) rebootFlag.mark()
                finish(this@Request, if (system) ScopeResult.APPROVED_REBOOT else ScopeResult.APPROVED)
            }
        }

        override fun onScopeRequestFailed(message: String) {
            scope.launch(worker) { finish(this@Request, ScopeResult.DENIED) }
        }
    }

    private companion object {
        /** LSPosed answers through a notification the user may take a while to open. */
        const val REQUEST_TIMEOUT_MS = 60_000L
    }
}

/** Remembers, across app restarts, that system_server joined the scope and the device has not restarted since. */
internal class RebootFlag(context: Context) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    val isPending: Boolean get() = prefs.getLong(KEY, 0L) >= bootTimeMillis()

    fun mark() = prefs.edit { putLong(KEY, System.currentTimeMillis()) }

    private companion object {
        const val FILE_NAME = "scope"
        const val KEY = "system_added_at"
    }
}
