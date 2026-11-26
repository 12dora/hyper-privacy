package io.github.hyperprivacy.xposed

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.provider.Settings
import io.github.hyperprivacy.core.status.RelayKeys
import io.github.hyperprivacy.core.status.RelayPayload
import io.github.hyperprivacy.core.status.StatusBatch
import io.github.hyperprivacy.core.status.StatusRelay
import io.github.hyperprivacy.status.StatusContract
import java.security.SecureRandom
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
 * - **relay** (protected third-party apps, when [relayKeys] is given): a sealed `call` on the
 *   Settings provider, answered by system_server ([StatusRelay]). The module package and its
 *   provider are never resolved from such a process, so module self-hiding stays in force there.
 *   Anything but an accepting answer (the stock provider's `SecurityException` while the relay
 *   hook is not ready, or not ready for this app yet; a `null`) is [UNAVAILABLE] and retried: from
 *   here a transient refusal cannot be told from a lasting one. A missing module secret is
 *   [UNAVAILABLE] too.
 */
internal class ReportDelivery(
    private val log: HookLog,
    private val context: () -> Context?,
    private val relayKeys: (() -> RelayKeys?)? = null,
) {
    enum class Outcome {
        DELIVERED,

        /** The receiver answered `null`: this caller is not authorised. Retrying cannot help. */
        REJECTED,

        /** No Context yet, or the receiver is not resolvable or not ready for this process right now. */
        UNAVAILABLE,

        /** The receiver was found but the call failed (provider died, binder error …). */
        FAILED,
    }

    private val warnedUnavailable = AtomicBoolean()
    private val warnedRejected = AtomicBoolean()
    private val warnedFailed = AtomicBoolean()
    private val random by lazy { SecureRandom() }

    /** Whether batches go through the status relay (and so must fit [StatusRelay.MAX_PAYLOAD_BYTES]). */
    val relayed: Boolean get() = relayKeys != null

    /** Sends [batch]; [trustedSigners] (system_server only) rides along to the module app. */
    fun send(batch: StatusBatch, trustedSigners: Set<String> = emptySet()): Outcome {
        val ctx = context() ?: return Outcome.UNAVAILABLE
        val relay = relayKeys
        return if (relay != null) {
            sendToRelay(ctx, relay(), batch)
        } else {
            sendToProvider(ctx, StatusContract.encode(batch, trustedSigners))
        }
    }

    private fun sendToRelay(ctx: Context, keys: RelayKeys?, batch: StatusBatch): Outcome {
        keys ?: return Outcome.UNAVAILABLE
        return try {
            val sealed = keys.seal(RelayPayload.encode(batch), random)
            val extras = Bundle(1).apply { putByteArray(StatusRelay.EXTRA_PAYLOAD, sealed) }
            val resolver = baseResolverContext(ctx).contentResolver
            if (resolver.call(Settings.AUTHORITY, StatusRelay.METHOD, keys.token, extras) != null) {
                Outcome.DELIVERED
            } else {
                warnOnce(warnedUnavailable, "status relay not ready; retrying")
                Outcome.UNAVAILABLE
            }
        } catch (_: SecurityException) {
            // The stock provider's answer: the relay hook is not installed or not ready for this app yet.
            warnOnce(warnedUnavailable, "status relay not ready; retrying")
            Outcome.UNAVAILABLE
        } catch (t: Throwable) {
            warnOnce(warnedFailed, "status relay failed", t)
            Outcome.FAILED
        }
    }

    /**
     * The innermost base context of [ctx]: the framework's own context, whose resolver the host
     * app cannot override (an `Application` subclass can override `getContentResolver`).
     */
    private fun baseResolverContext(ctx: Context): Context {
        var current = ctx
        while (current is ContextWrapper) current = current.baseContext ?: break
        return current
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
