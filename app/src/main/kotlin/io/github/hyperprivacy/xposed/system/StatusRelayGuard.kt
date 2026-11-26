package io.github.hyperprivacy.xposed.system

import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import io.github.hyperprivacy.core.status.RateLimiter
import io.github.hyperprivacy.core.status.RelayKeys
import io.github.hyperprivacy.core.status.RelayPayload
import io.github.hyperprivacy.core.status.StatusRelay
import io.github.hyperprivacy.core.system.AppIndex
import io.github.hyperprivacy.xposed.FailOpenHooker
import io.github.hyperprivacy.xposed.HookLog
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.Sinks
import io.github.hyperprivacy.xposed.Verdict
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode

/**
 * system_server end of the status relay ([StatusRelay]): protected third-party apps deliver their
 * status here instead of to the module app, which stays hidden from them.
 *
 * Hooks `com.android.providers.settings.SettingsProvider#call(String, String, Bundle)`
 * (SettingsProvider runs in the `system` process; every app can reach it, no package visibility
 * involved). Every Settings read and write passes here, so the common path is one String compare
 * of the method with [StatusRelay.METHOD] (decided by length or first character for every stock
 * method but that one), no allocation and no lock; everything else runs the original untouched.
 *
 * A relay call is taken only when, in this order (each step O(1), nothing decoded before the last):
 * its `arg` is the [RelayKeys.token] of a protected package (tokens are rebuilt only when the
 * protected set or the module secret changes); the caller uid is that package's own, a known
 * third-party app; the caller is within its rate limit ([RateLimiter], [BURST] calls, then one
 * per [REFILL_MS]); the only extra read, a flat `byte[]`, is at most [StatusRelay.MAX_SEALED_BYTES]
 * and opens with the package's key. Rows are then filtered by [StatusRelay.accept] and handed to
 * system_server's [io.github.hyperprivacy.xposed.StatusReporter], which forwards them to the
 * module app. Anything refused runs the stock provider, whose answer to this method from an app
 * is a silent `SecurityException`: the relay proves nothing to a caller that does not hold the
 * token, and nothing is logged (no method name, no token) whatever the state of this hook.
 */
internal class StatusRelayGuard(
    private val xposed: XposedInterface,
    private val log: HookLog,
    private val state: SystemState,
) {
    /** Tokens of the protected packages under one secret; replaced, never mutated. */
    private class Tokens(val secret: ByteArray?, val packages: Set<String>, val byToken: Map<String, RelayKeys>)

    private val hookedLoaders = HashSet<ClassLoader?>()
    private val limiter = RateLimiter(BURST, REFILL_MS)

    @Volatile
    private var tokens = Tokens(null, emptySet(), emptyMap())

    /** Whether at least one class loader's `SettingsProvider#call` is hooked. */
    @Volatile
    var hooked: Boolean = false
        private set

    @Synchronized
    fun install(classLoader: ClassLoader?, via: String): Boolean {
        if (classLoader in hookedLoaders) return true
        val type = Reflection.loadClass(PROVIDER_CLASS, classLoader) ?: run {
            log.warn("status relay: $PROVIDER_CLASS not found via $via")
            return false
        }
        val call = runCatching {
            type.getDeclaredMethod(CALL, String::class.java, String::class.java, Bundle::class.java)
        }.getOrNull()
        if (call == null || call.returnType != Bundle::class.java) {
            log.warn("status relay: $PROVIDER_CLASS#$CALL not found via $via")
            return false
        }
        xposed.hook(call)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept(FailOpenHooker(log, "status relay", ::decide))
        hookedLoaders += classLoader
        hooked = true
        log.info("status relay: hooked $PROVIDER_CLASS#$CALL via $via")
        return true
    }

    private fun decide(chain: Chain): Verdict {
        // Hot path: every Settings call. String.equals rejects on length, then on the first char.
        if (chain.getArg(0) != StatusRelay.METHOD) return Verdict.Proceed
        val token = chain.getArg(1) as? String ?: return Verdict.Proceed
        if (token.length != RelayKeys.TOKEN_LENGTH) return Verdict.Proceed
        val keys = currentTokens()[token] ?: return Verdict.Proceed
        return if (accept(keys, chain.getArg(2) as? Bundle)) ACCEPTED else Verdict.Proceed
    }

    /**
     * Tokens of the current protected packages and secret; rebuilt (one HMAC per protected package)
     * only after either changed, and only ever reached by a relay-shaped call.
     */
    private fun currentTokens(): Map<String, RelayKeys> {
        val secret = state.switches?.secret
        val packages = state.protectedPackages
        val current = tokens
        if (current.secret === secret && current.packages === packages) return current.byToken
        val byToken = if (secret == null) {
            emptyMap()
        } else {
            packages.asSequence().map { RelayKeys.derive(secret, it) }.associateBy { it.token }
        }
        tokens = Tokens(secret, packages, byToken)
        return byToken
    }

    /** Whether the relay call from the calling uid for [keys]'s package was taken (see the class comment). */
    private fun accept(keys: RelayKeys, extras: Bundle?): Boolean {
        val appId = AppIndex.callerAppId(Binder.getCallingUid())
        if (appId < 0) return false
        val index = state.index
        if (!index.isKnown(appId)) {
            // Installed (or protected) seconds ago: the reporter retries once the index caught up.
            if (!index.isEmpty) state.requestIndexRefresh(appId)
            return false
        }
        if (!index.isThirdParty(appId) || index.appIdOf(keys.packageName) != appId) return false
        val reporter = state.reporter ?: return false
        if (!limiter.tryAcquire(appId, SystemClock.uptimeMillis())) return false
        val sealed = extras?.getByteArray(StatusRelay.EXTRA_PAYLOAD) ?: return false
        if (sealed.size > StatusRelay.MAX_SEALED_BYTES) return false
        val plaintext = keys.open(sealed) ?: return false
        val protectedPackages = state.protectedPackages
        val batch = StatusRelay.accept(
            batch = RelayPayload.decode(plaintext),
            ownedByCaller = { pkg -> pkg in protectedPackages && index.appIdOf(pkg) == appId },
            isKnownSink = Sinks::isKnown,
            now = System.currentTimeMillis(),
        )
        if (!batch.isEmpty) reporter.relay(batch)
        return true
    }

    private companion object {
        const val PROVIDER_CLASS = "com.android.providers.settings.SettingsProvider"
        const val CALL = "call"

        /** Relay calls one app may make at once, and the interval after which it may make one more. */
        const val BURST = 4
        const val REFILL_MS = 5_000L

        /** The answer to a taken relay call: non-null, unlike the stock provider's for this method. */
        val ACCEPTED = Verdict.Return(Bundle.EMPTY)
    }
}
