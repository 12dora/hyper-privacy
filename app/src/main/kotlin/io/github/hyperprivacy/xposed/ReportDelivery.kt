package io.github.hyperprivacy.xposed

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import io.github.hyperprivacy.status.StatusContract
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One delivery attempt of a status batch, over one of two transports:
 *
 * - **provider** (catalog targets and system_server): the module app's status provider, via
 *   `acquireUnstableContentProviderClient`, which returns `null` instead of throwing when the
 *   provider cannot be resolved: before user unlock (system_server at boot), while the module app
 *   is being installed, or when the calling app has no package visibility of the module (Android
 *   11+ filtering). These cases are indistinguishable here, so all are reported as [UNAVAILABLE]
 *   and retried with backoff by the caller; each distinct problem is logged once per process.
 * - **relay** (protected third-party apps, when [relayMethod] is given): a `call` on the Settings
 *   provider, answered by system_server ([io.github.hyperprivacy.core.status.StatusRelay]). The
 *   module package and its provider are never resolved from such a process, so module
 *   self-hiding stays in force there. A `null` answer (relay not hooked, or not accepted) counts
 *   as [REJECTED]; a missing module secret as [UNAVAILABLE].
 */
internal class ReportDelivery(
    private val log: HookLog,
    private val context: () -> Context?,
    private val relayMethod: (() -> String?)? = null,
) {
    enum class Outcome {
        DELIVERED,

        /** The receiver answered `null`: this caller is not authorised. Retrying cannot help. */
        REJECTED,

        /** No Context yet, or the receiver is not resolvable from this process right now. */
        UNAVAILABLE,

        /** The receiver was found but the call failed (provider died, binder error …). */
        FAILED,
    }

    private val warnedUnavailable = AtomicBoolean()
    private val warnedRejected = AtomicBoolean()
    private val warnedFailed = AtomicBoolean()

    fun send(extras: Bundle): Outcome {
        val ctx = context() ?: return Outcome.UNAVAILABLE
        val relay = relayMethod
        return if (relay != null) sendToRelay(ctx, relay(), extras) else sendToProvider(ctx, extras)
    }

    private fun sendToRelay(ctx: Context, method: String?, extras: Bundle): Outcome {
        method ?: return Outcome.UNAVAILABLE
        return try {
            if (ctx.contentResolver.call(Settings.AUTHORITY, method, null, extras) != null) {
                Outcome.DELIVERED
            } else {
                warnOnce(warnedRejected, "status relay not accepted")
                Outcome.REJECTED
            }
        } catch (t: Throwable) {
            warnOnce(warnedFailed, "status relay failed", t)
            Outcome.FAILED
        }
    }

    private fun sendToProvider(ctx: Context, extras: Bundle): Outcome {
        val client = try {
            ctx.contentResolver.acquireUnstableContentProviderClient(StatusContract.AUTHORITY)
        } catch (t: Throwable) {
            warnOnce(warnedFailed, "acquiring the status provider failed", t)
            null
        }
        if (client == null) {
            warnOnce(
                warnedUnavailable,
                "status provider ${StatusContract.AUTHORITY} unavailable (user locked, module app missing, or " +
                    "not visible to this app); counting locally and retrying",
            )
            return Outcome.UNAVAILABLE
        }
        return try {
            if (client.call(StatusContract.METHOD_REPORT, null, extras) != null) {
                Outcome.DELIVERED
            } else {
                warnOnce(warnedRejected, "status report rejected by the module app (caller not in scope?)")
                Outcome.REJECTED
            }
        } catch (t: Throwable) {
            warnOnce(warnedFailed, "status report failed", t)
            Outcome.FAILED
        } finally {
            client.close()
        }
    }

    private fun warnOnce(flag: AtomicBoolean, message: String, error: Throwable? = null) {
        if (flag.compareAndSet(false, true)) log.warn(message, error)
    }
}
