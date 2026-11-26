package io.github.hyperprivacy.xposed.fp

import android.content.Intent
import android.os.BatteryManager
import io.github.hyperprivacy.xposed.GuardEnv
import io.github.hyperprivacy.xposed.Reflection
import java.lang.reflect.Method

/**
 * The battery cycle count, which `BatteryManager` publishes only as [BatteryManager.EXTRA_CYCLE_COUNT]
 * of the `ACTION_BATTERY_CHANGED` broadcast (no public `BATTERY_PROPERTY_*` carries it). It wears
 * with use and is therefore nearly unique per device; many devices do not report it at all, so apps
 * already treat it as optional and the extra is simply removed.
 *
 * Two points cover both ways an app gets that intent: the sticky intent returned by
 * `registerReceiver` (`ContextImpl#registerReceiverInternal`, behind every overload) and the copy
 * delivered to a dynamically registered receiver (`LoadedApk$ReceiverDispatcher#performReceive`).
 * Every other extra, charge level, status, temperature and the rest, stays as delivered.
 */
internal class BatteryTraitHooks(private val env: GuardEnv, private val state: FingerprintState) {
    /** Returns the number of methods hooked. */
    fun install(): Int = installSticky() + installDelivery()

    private fun installSticky(): Int {
        val methods = methods(CONTEXT_IMPL, "registerReceiverInternal") { it.returnType == Intent::class.java }
        return env.hookAll(methods, ResultHook(state) { real -> (real as? Intent)?.also(::strip) })
    }

    private fun installDelivery(): Int {
        val methods = methods(RECEIVER_DISPATCHER, "performReceive") {
            it.parameterTypes.firstOrNull() == Intent::class.java
        }
        return env.hookAll(methods, ArgumentHook(state) { chain -> (chain.getArg(0) as? Intent)?.let(::strip) })
    }

    /** Removes the cycle count from a battery broadcast, in place; any other intent is left alone. */
    private fun strip(intent: Intent) {
        if (intent.action != Intent.ACTION_BATTERY_CHANGED || !intent.hasExtra(BatteryManager.EXTRA_CYCLE_COUNT)) return
        intent.removeExtra(BatteryManager.EXTRA_CYCLE_COUNT)
        state.hit(GROUP)
    }

    private fun methods(className: String, name: String, accept: (Method) -> Boolean): List<Method> {
        val type = Reflection.loadClass(className, null) ?: run {
            env.log.warn("$className not found in ${env.packageName}")
            return emptyList()
        }
        return type.declaredMethods.filter { it.name == name && accept(it) }
    }

    companion object {
        val GROUP = TraitGroup("battery")
        private const val CONTEXT_IMPL = "android.app.ContextImpl"
        private const val RECEIVER_DISPATCHER = "android.app.LoadedApk\$ReceiverDispatcher"
    }
}
