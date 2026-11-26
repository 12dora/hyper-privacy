package io.github.hyperprivacy.xposed.origin

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import io.github.hyperprivacy.xposed.GuardEnv
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ad page guard: an activity of an enabled ad SDK (its class under the SDK's prefixes, a relocated
 * root from the manifest anchors, an enabled suspected package, or defined by a class loader the
 * SDK created) is finished as it is created, so it never becomes visible.
 *
 * Hooked as late as possible but before the activity's own `onCreate`: `Activity.performCreate`
 * (every activity, whatever `Instrumentation` the app installs), else the public
 * `Instrumentation.callActivityOnCreate`. `finish()` runs first, with the close transition turned
 * off, then the original: the SDK's `onCreate` runs as usual (calling `super.onCreate`, so nothing
 * crashes), the framework skips start and resume for a finishing activity, so no window is drawn,
 * and the SDK sees the normal `onDestroy` path it uses for a closed ad. The app's own activities
 * never match ([io.github.hyperprivacy.core.tracker.OriginMatcher] puts the app's roots first).
 *
 * Cost: one volatile read per activity creation while no ad SDK is enabled; otherwise one class
 * name lookup (no stack walk).
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
                val blocked = activity != null && origin.active && closeIfAd(activity)
                val result = chain.proceed()
                if (blocked) safely { noTransition(activity) }
                result
            }
        }
        env.log.info("ad page guard: ${targets.size} entry point(s) in ${env.packageName}")
        return targets.size
    }

    private fun closeIfAd(activity: Activity): Boolean = safely {
        val rule = origin.ruleOfClass(activity.javaClass) ?: return@safely false
        activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        activity.finish()
        origin.hit(rule, "page ${activity.javaClass.simpleName}")
        true
    } ?: false

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
    }
}
