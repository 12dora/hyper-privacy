package io.github.hyperprivacy.xposed.origin

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import io.github.hyperprivacy.xposed.GuardEnv
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ad page guard: an activity of an enabled ad SDK (its class under the SDK's prefixes, a relocated
 * root from the manifest anchors, an enabled suspected package, or defined by a class loader the
 * SDK created) is finished as soon as its window is up, before the user can interact with it.
 *
 * The class is looked up when the activity is created (`Activity.performCreate`, whatever
 * `Instrumentation` the app installs) and remembered; once `Activity.performResume` has returned,
 * a close is posted to the page's decor view, which runs only after the window is attached and its
 * first traversal is done. The SDK therefore runs its complete normal lifecycle (create, start,
 * resume with a real window, so dialogs and popups it shows there have a valid token, then pause,
 * stop, destroy, as for an ad closed at once). Right before closing, the page's class is looked up
 * again, so pausing protection or turning the SDK's rule off in between keeps it open; a page is
 * remembered only from its creation to its next resume, whatever happens to the switches. Without
 * both entry points, or without a decor view, nothing is closed.
 *
 * Fail-open, and never where finishing could take the app with it: an activity that is already
 * finishing, the root of its task (a splash flow that launched the ad and finished itself), a
 * launcher entry, a `singleTask` / `singleInstance` page, or one started for a result by code that
 * is not the SDK's is left alone. The app's own activities never match
 * ([io.github.hyperprivacy.core.tracker.OriginMatcher]: own roots win, and derived roots never
 * reach inside the app's roots).
 *
 * Cost: one volatile read per activity creation and resume while no ad SDK is enabled; otherwise
 * one class name lookup (no stack walk) per creation and one map probe per resume. The safety
 * checks (a few binder calls) run only for an ad page.
 */
internal class AdPageGuard(private val env: GuardEnv, private val origin: AdOrigin) {
    private val failures = AtomicInteger()

    /** Ad pages created and not yet resumed; main thread in practice, locked anyway. */
    private val pending = WeakHashMap<Activity, Unit>()

    /** Returns the number of hooked entry points. */
    fun install(): Int {
        val create = activityMethod(PERFORM_CREATE) { it.parameterTypes.firstOrNull() == Bundle::class.java }
        val resume = activityMethod(PERFORM_RESUME) { true }
        if (create == null || resume == null) {
            env.log.warn("ad page guard: no create/resume entry points in ${env.packageName}")
            return 0
        }
        // Resume first: a page may only be remembered at creation when its resume will see it.
        val hooked = hook(resume, always = true, step = ::onResumed) &&
            hook(create, always = false, step = ::onCreating)
        env.log.info("ad page guard: hooked=$hooked in ${env.packageName}")
        return if (hooked) 2 else 0
    }

    /**
     * Hooks [method]: [step] decides before the original (only while an ad SDK is enabled, unless
     * [always]), and what it returns runs, guarded, after the original returned.
     */
    private fun hook(method: Method, always: Boolean, step: (Activity) -> (() -> Unit)?): Boolean =
        env.log.guard("ad page hook ${method.name}") {
            env.xposed.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val after = if (always || origin.active) safely { (chain.thisObject as? Activity)?.let(step) } else null
                val result = chain.proceed()
                if (after != null) safely { after() }
                result
            }
        } != null

    /** Creation: remembers an ad page; the original then runs untouched. */
    private fun onCreating(activity: Activity): (() -> Unit)? {
        origin.ruleOfClass(activity.javaClass) ?: return null
        return { synchronized(pending) { pending[activity] = Unit } }
    }

    /** Resume: forgets the page and, once its window is attached, closes it if it is still an ad page. */
    private fun onResumed(activity: Activity): (() -> Unit)? {
        val remembered = synchronized(pending) { pending.isNotEmpty() && pending.remove(activity) != null }
        if (!remembered) return null
        return {
            // peek: never creates a decor view the page did not make; none means nothing to wait for.
            activity.window?.peekDecorView()?.post { safely { closeIfStillAd(activity) } }
        }
    }

    private fun closeIfStillAd(activity: Activity) {
        if (!origin.active) return
        val rule = origin.ruleOfClass(activity.javaClass) ?: return
        close(activity, rule)
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

    /**
     * The concrete `Activity` method [name] (`performCreate(Bundle, …)`, `performResume(…)`) with the
     * most parameters: the others delegate to it.
     */
    private fun activityMethod(name: String, accept: (Method) -> Boolean): Method? = runCatching {
        Activity::class.java.declaredMethods.filter {
            it.name == name && !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                !it.isBridge && !it.isSynthetic && accept(it)
        }.maxByOrNull { it.parameterCount }
    }.getOrNull()

    // A failure here only means the page is shown as usual.
    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        if (failures.incrementAndGet() <= MAX_LOGGED_FAILURES) env.log.error("ad page guard failed", t)
        null
    }

    private companion object {
        const val PERFORM_CREATE = "performCreate"
        const val PERFORM_RESUME = "performResume"
        const val MAX_LOGGED_FAILURES = 5
        val SINGLE_MODES = setOf(
            ActivityInfo.LAUNCH_SINGLE_TASK,
            ActivityInfo.LAUNCH_SINGLE_INSTANCE,
            ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK,
        )
    }
}
