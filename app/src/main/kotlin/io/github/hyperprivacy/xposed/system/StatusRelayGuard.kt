package io.github.hyperprivacy.xposed.system

import android.os.Binder
import android.os.Bundle
import io.github.hyperprivacy.core.status.StatusRelay
import io.github.hyperprivacy.core.system.AppIndex
import io.github.hyperprivacy.status.StatusContract
import io.github.hyperprivacy.xposed.FailOpenHooker
import io.github.hyperprivacy.xposed.HookLog
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.Verdict
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.concurrent.atomic.AtomicReference

/**
 * system_server end of the status relay ([StatusRelay]): protected third-party apps deliver their
 * status here instead of to the module app, which stays hidden from them.
 *
 * Hooks `com.android.providers.settings.SettingsProvider#call(String, String, Bundle)`
 * (SettingsProvider runs in the `system` process; every app can reach it, no package visibility
 * involved). Only a method equal to [StatusRelay.method] of the current module secret is ours;
 * everything else runs the original untouched, so the cost on this path is one string compare.
 * For our method the caller uid must belong to a third-party app with tracker protection on
 * ([SystemState.protectedPackages]); rows are kept only for that app's own packages
 * ([StatusRelay.accept]) and handed to system_server's [io.github.hyperprivacy.xposed.StatusReporter],
 * which forwards them to the module app. A rejected call gets `null`, the provider's own answer to
 * an unknown method, so the relay proves nothing to a caller that does not hold the secret.
 */
internal class StatusRelayGuard(
    private val xposed: XposedInterface,
    private val log: HookLog,
    private val state: SystemState,
) {
    private class Method(val secret: ByteArray, val name: String)

    private val hookedLoaders = HashSet<ClassLoader?>()
    private val method = AtomicReference<Method?>()

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
        val name = chain.getArg(0) as? String ?: return Verdict.Proceed
        val expected = currentMethod() ?: return Verdict.Proceed
        if (name != expected) return Verdict.Proceed
        return Verdict.Return(accept(chain.getArg(2) as? Bundle))
    }

    /** The relay method of the current secret; recomputed only when the secret changes. */
    private fun currentMethod(): String? {
        val secret = state.switches?.secret ?: return null
        method.get()?.takeIf { it.secret === secret }?.let { return it.name }
        return StatusRelay.method(secret).also { method.set(Method(secret, it)) }
    }

    private fun accept(extras: Bundle?): Bundle? {
        val appId = AppIndex.callerAppId(Binder.getCallingUid())
        val index = state.index
        if (appId < 0 || !index.isKnown(appId) || !index.isThirdParty(appId)) return null
        val protectedPackages = state.protectedPackages
        val owned = { pkg: String -> pkg in protectedPackages && index.appIdOf(pkg) == appId }
        if (protectedPackages.none(owned)) return null
        val reporter = state.reporter ?: return null
        val batch = StatusRelay.accept(StatusContract.decode(extras), owned)
        if (!batch.isEmpty) reporter.relay(batch)
        return Bundle.EMPTY
    }

    private companion object {
        const val PROVIDER_CLASS = "com.android.providers.settings.SettingsProvider"
        const val CALL = "call"
    }
}
