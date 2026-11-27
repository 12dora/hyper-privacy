package io.github.hyperprivacy.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Observes `ActivityManagerService#systemReady` in system_server: every concrete overload, matched
 * by name only so signature changes across releases do not matter (bridge, synthetic and abstract
 * ones are skipped, as hooking them fails). The original runs unchanged, then [onReady] runs
 * guarded; nothing touches arguments or results. Each overload is hooked on its own, so one that
 * cannot be hooked does not cost the others.
 */
internal object SystemReady {
    private const val AMS_CLASS = "com.android.server.am.ActivityManagerService"
    private const val SYSTEM_READY = "systemReady"

    /** Returns the number of overloads hooked; [what] names the caller in the log. */
    fun observe(
        xposed: XposedInterface,
        log: HookLog,
        classLoader: ClassLoader,
        what: String,
        onReady: () -> Unit,
    ): Int {
        val ams = Reflection.loadClass(AMS_CLASS, classLoader)
        val targets = ams?.let { log.guard("$what: list $SYSTEM_READY") { it.declaredMethods.filter(::isTarget) } }
        val hooked = targets.orEmpty().count { method ->
            log.guard("$what: hook $SYSTEM_READY") {
                xposed.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    log.guard("$what bootstrap") { onReady() }
                    result
                }
            } != null
        }
        if (hooked == 0) log.warn("$what: $AMS_CLASS#$SYSTEM_READY not found")
        return hooked
    }

    private fun isTarget(method: Method): Boolean = method.name == SYSTEM_READY && !method.isBridge &&
        !method.isSynthetic && !Modifier.isAbstract(method.modifiers)
}
