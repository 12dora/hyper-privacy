package io.github.hyperprivacy.xposed.origin

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import io.github.hyperprivacy.xposed.GuardEnv
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ad page guard: an activity of an enabled ad SDK (its class under the SDK's prefixes, a relocated
 * root from the manifest anchors, an enabled suspected package, or defined by a class loader the
 * SDK created) is finished right after it is created, so it never becomes visible.
 *
 * Hooked on `Activity.performCreate` (every activity, whatever `Instrumentation` the app installs),
 * else the public `Instrumentation.callActivityOnCreate`. The class is looked up before the
 * original; the original runs untouched (the SDK's `onCreate` sees a normal, non-finishing
 * activity, so nothing it does there can fail on a finishing one); `finish()` follows once it has
 * returned, with the close transition off. The framework then skips start and resume for the
 * finishing activity, so no window is ever drawn, and the SDK gets the normal `onDestroy` it uses
 * for a closed ad.
 *
 * Fail-open, and never where finishing could take the app with it: an activity that is already
 * finishing, the root of its task (a splash flow that launched the ad and finished itself), a
 * launcher entry, a `singleTask` / `singleInstance` page, or one started for a result by code that
 * is not the SDK's is left alone. The app's own activities never match
 * ([io.github.hyperprivacy.core.tracker.OriginMatcher]: own roots win, and derived roots never
 * reach inside the app's roots).
 *
 * Cost: one volatile read per activity creation while no ad SDK is enabled; otherwise one class
 * name lookup (no stack walk). The safety checks (a few binder calls) run only for an ad page.
 */
internal class AdPageGuard(private val env: GuardEnv, private val origin: AdOrigin) {
    private val failures = AtomicInteger()

    /** Returns the number of hooked entry points. */
    fun install(): Int {
        val perform = performCreate()
        val targets = if (perform != null) listOf(perform) else instrumentationCreate()
        targets.forEach { method ->
            val ownedByActivity = method.declaringClass == Activity::class.java
            env.xposed.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val activity = if (ownedByActivity) chain.thisObject as? Activity else chain.getArg(0) as? Activity
                val rule = if (activity != null && origin.active) {
                    safely {
                        origin.ruleOfClass(
                            activity.javaClass,
                        )
                    }
                } else {
                    null
                }
                val result = chain.proceed()
                if (activity != null && rule != null) safely { close(activity, rule) }
                result
            }
        }
        env.log.info("ad page guard: ${targets.size} entry point(s) in ${env.packageName}")
        return targets.size
    }

    private fun close(activity: Activity, rule: String) {
        val unsafe = unsafeReason(activity)
        if (unsafe != null) {
            env.log.info("ad page ${activity.javaClass.name} left open: $unsafe")
            return
        }
        activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        activity.finish()
        noTransition(activity)
        origin.hit(rule, "page ${activity.javaClass.simpleName}")
    }

    /** Why finishing [activity] could hurt the app (it is then left open), or `null` when it is safe. */
    private fun unsafeReason(activity: Activity): String? {
        val intent: Intent? = activity.intent
        val caller = activity.callingActivity
        return when {
            activity.isFinishing || activity.isDestroyed -> "already finishing"
            intent?.action == Intent.ACTION_MAIN || intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true -> "launcher"
            activity.isTaskRoot -> "task root"
            caller != null && origin.ruleOfName(caller.className) == null -> "started for result"
            launchMode(activity) in SINGLE_MODES -> "single task"
            else -> null
        }
    }

    private fun launchMode(activity: Activity): Int = safely {
        val flags = PackageManager.ComponentInfoFlags.of(0)
        activity.packageManager.getActivityInfo(activity.componentName, flags).launchMode
    } ?: ActivityInfo.LAUNCH_SINGLE_TASK

    // The legacy call still applies to the transition of this finish on current releases.
    @Suppress("DEPRECATION")
    private fun noTransition(activity: Activity) = activity.overridePendingTransition(0, 0)

    /** `Activity.performCreate(Bundle, …)` with the most parameters (the others delegate to it). */
    private fun performCreate(): Method? = runCatching {
        Activity::class.java.declaredMethods.filter {
            it.name == PERFORM_CREATE && it.parameterTypes.firstOrNull() == Bundle::class.java
        }.maxByOrNull { it.parameterCount }
    }.getOrNull()

    private fun instrumentationCreate(): List<Method> = Instrumentation::class.java.declaredMethods.filter {
        it.name == CALL_ON_CREATE && it.parameterTypes.firstOrNull() == Activity::class.java
    }

    // A failure here only means the page is shown as usual.
    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        if (failures.incrementAndGet() <= MAX_LOGGED_FAILURES) env.log.error("ad page guard failed", t)
        null
    }

    private companion object {
        const val PERFORM_CREATE = "performCreate"
        const val CALL_ON_CREATE = "callActivityOnCreate"
        const val MAX_LOGGED_FAILURES = 5
        val SINGLE_MODES = setOf(
            ActivityInfo.LAUNCH_SINGLE_TASK,
            ActivityInfo.LAUNCH_SINGLE_INSTANCE,
            ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK,
        )
    }
}
