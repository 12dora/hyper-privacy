package io.github.hyperprivacy.xposed

import android.content.Context
import android.os.Bundle
import io.github.hyperprivacy.status.StatusContract
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One delivery attempt of a status batch to the module app's provider.
 *
 * Uses `acquireUnstableContentProviderClient`, which returns `null` instead of throwing when the
 * provider cannot be resolved: before user unlock (system_server at boot), while the module app
 * is being installed, or when the calling app has no package visibility of the module (Android
 * 11+ filtering). These cases are indistinguishable here, so all are reported as [UNAVAILABLE]
 * and retried with backoff by the caller; each distinct problem is logged once per process.
 */
internal class ReportDelivery(private val log: HookLog, private val context: () -> Context?) {
    enum class Outcome {
        DELIVERED,

        /** The provider answered `null`: this caller is not authorised. Retrying cannot help. */
        REJECTED,

        /** No Context yet, or the provider is not resolvable from this process right now. */
        UNAVAILABLE,

        /** The provider was found but the call failed (provider died, binder error …). */
        FAILED,
    }

    private val warnedUnavailable = AtomicBoolean()
    private val warnedRejected = AtomicBoolean()
    private val warnedFailed = AtomicBoolean()

    fun send(extras: Bundle): Outcome {
        val ctx = context() ?: return Outcome.UNAVAILABLE
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
