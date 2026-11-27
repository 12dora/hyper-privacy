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
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ad page guard: an activity of an enabled ad SDK (its class under the SDK's prefixes, a relocated
 * root from the manifest anchors, an enabled suspected package, or defined by a class loader the
 * SDK created) is finished before its window is ever shown.
 *
 * The class is looked up when the activity is created (`Activity.performCreate`, whatever
 * `Instrumentation` the app installs, else the public `Instrumentation.callActivityOnCreate`) and
 * the page is finished right after it has resumed (`Activity.performResume`, resp.
 * `Instrumentation.callActivityOnResume`), with the close transition off. The SDK thus runs the
 * complete normal lifecycle (create, start, resume, then pause, stop, destroy, as for an ad closed
 * at once), so no SDK code meets a state it never saw, while the framework adds the window only
 * after resume and skips that for a finishing activity: nothing is drawn. Without a resume entry
 * point nothing is finished at all.
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

    /** Ad pages created and not yet resumed, with their rule; main thread in practice, locked anyway. */
    private val pending = WeakHashMap<Activity, String>()

    /** Returns the number of hooked entry points. */
    fun install(): Int {
        val perform = activityMethod(PERFORM_CREATE) { it.parameterTypes.firstOrNull() == Bundle::class.java }
        val resume = perform?.let { activityMethod(PERFORM_RESUME) { true } }
        val (creates, resumes) = if (perform != null && resume != null) {
            listOf(perform) to listOf(resume)
        } else {
            instrumentation(CALL_ON_CREATE) to instrumentation(CALL_ON_RESUME)
        }
        if (creates.isEmpty() || resumes.isEmpty()) {
            env.log.warn("ad page guard: no create/resume entry points in ${env.packageName}")
            return 0
        }
        val hooked = resumes.count { hook(it, ::onResumed) } + creates.count { hook(it, ::onCreating) }
        env.log.info("ad page guard: $hooked entry point(s) in ${env.packageName}")
        return hooked
    }

    /** Hooks [method]: [step] decides before the original, and what it returns runs, guarded, after it. */
    private fun hook(method: Method, step: (Activity) -> (() -> Unit)?): Boolean {
        val ownedByActivity = method.declaringClass == Activity::class.java
        return env.log.guard("ad page hook ${method.name}") {
            env.xposed.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val after = if (origin.active) {
                    safely {
                        val activity = if (ownedByActivity) chain.thisObject else chain.getArg(0)
                        (activity as? Activity)?.let(step)
                    }
                } else {
                    null
                }
                val result = chain.proceed()
                if (after != null) safely { after() }
                result
            }
        } != null
    }

    /** Creation: remembers an ad page; the original then runs untouched. */
    private fun onCreating(activity: Activity): (() -> Unit)? {
        val rule = origin.ruleOfClass(activity.javaClass) ?: return null
        return { synchronized(pending) { pending[activity] = rule } }
    }

    /** Resume: an ad page created earlier is finished once its own resume has returned. */
    private fun onResumed(activity: Activity): (() -> Unit)? {
        val rule = synchronized(pending) { if (pending.isEmpty()) null else pending.remove(activity) } ?: return null
        return { close(activity, rule) }
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
            it.name == name && !Modifier.isAbstract(it.modifiers) && !it.isBridge && accept(it)
        }.maxByOrNull { it.parameterCount }
    }.getOrNull()

    private fun instrumentation(name: String): List<Method> = runCatching {
        Instrumentation::class.java.declaredMethods.filter {
            it.name == name && it.parameterTypes.firstOrNull() == Activity::class.java
        }
    }.getOrNull().orEmpty()

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
        const val CALL_ON_CREATE = "callActivityOnCreate"
        const val CALL_ON_RESUME = "callActivityOnResume"
        const val MAX_LOGGED_FAILURES = 5
        val SINGLE_MODES = setOf(
            ActivityInfo.LAUNCH_SINGLE_TASK,
            ActivityInfo.LAUNCH_SINGLE_INSTANCE,
            ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK,
        )
    }
}
