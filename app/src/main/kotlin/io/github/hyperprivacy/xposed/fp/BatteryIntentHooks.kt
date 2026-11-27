package io.github.hyperprivacy.xposed.fp

import android.content.Intent
import io.github.hyperprivacy.xposed.GuardEnv
import io.github.hyperprivacy.xposed.Reflection
import java.lang.reflect.Method

/**
 * The two points that cover both ways an app gets an `ACTION_BATTERY_CHANGED` intent: the sticky
 * intent returned by `registerReceiver` (`ContextImpl#registerReceiverInternal`, behind every
 * overload) and the copy delivered to a dynamically registered receiver
 * (`LoadedApk$ReceiverDispatcher#performReceive`). [rewrite] gets each battery intent and returns a
 * replacement, or `null` to keep it; any other intent costs one string comparison.
 *
 * [rewrite] must never change the intent in place: the sticky one may be the process-wide cached
 * copy, and a delivered one may be shared with other receivers of the process. It copies only an
 * intent it actually changes (one `Intent` per such broadcast).
 */
internal class BatteryIntentHooks(
    private val env: GuardEnv,
    private val state: FingerprintState<*>,
    private val sink: String,
    private val rewrite: (Intent) -> Intent?,
) {
    /** Returns the number of methods hooked. */
    fun install(): Int = installSticky() + installDelivery()

    private fun installSticky(): Int {
        val methods = methods(CONTEXT_IMPL, "registerReceiverInternal") { it.returnType == Intent::class.java }
        return env.hookAll(methods, ResultHook(state, sink, ::battery))
    }

    private fun installDelivery(): Int {
        val methods = methods(RECEIVER_DISPATCHER, "performReceive") {
            it.parameterTypes.firstOrNull() == Intent::class.java
        }
        return env.hookAll(methods, ArgumentHook(state, sink, ::battery))
    }

    private fun battery(value: Any?): Intent? {
        val intent = value as? Intent ?: return null
        if (intent.action != Intent.ACTION_BATTERY_CHANGED) return null
        return rewrite(intent)
    }

    private fun methods(className: String, name: String, accept: (Method) -> Boolean): List<Method> {
        val type = Reflection.loadClass(className, null) ?: run {
            env.log.warn("$className not found in ${env.packageName}")
            return emptyList()
        }
        return type.declaredMethods.filter { it.name == name && accept(it) }
    }

    private companion object {
        const val CONTEXT_IMPL = "android.app.ContextImpl"
        const val RECEIVER_DISPATCHER = "android.app.LoadedApk\$ReceiverDispatcher"
    }
}
