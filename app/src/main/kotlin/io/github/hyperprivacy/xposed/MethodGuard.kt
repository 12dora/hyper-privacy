package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.method.DefaultReturn
import io.github.hyperprivacy.core.method.Replacement
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * No-ops public SDK entry points named by METHOD rules. Targets are resolved once here: the class
 * is loaded without running its static initialiser, and a method is hooked only if its name, the
 * rule's pinned signature ([RulePayload.Method.shape]) and a type-correct replacement value
 * ([DefaultReturn.isValidFor]) all agree; anything else is left alone (the rule just stays off).
 *
 * Java methods are hooked regardless of the switch and consult it per call, so toggling takes
 * effect live. Native methods are different: they are hooked only after the library binding them
 * ([RulePayload.Method.nativeLibrary]) has loaded ([NativeLibraries]; at once if it already is),
 * so the hook can never stand in the way of the class's own `System.loadLibrary` /
 * `RegisterNatives`, and the hook never calls through (an original reached before binding could
 * fail with `UnsatisfiedLinkError`). They are hooked only when the rule is on as the process
 * starts and then stay replaced: pause and switch changes apply after the app restarts.
 */
internal class MethodGuard(private val env: GuardEnv) {
    private val xposed get() = env.xposed
    private val log get() = env.log
    private val packageName get() = env.packageName

    /** Returns the number of methods hooked or armed, or [DEFERRED] when native targets wait for the next start. */
    fun install(rule: Rule, payload: RulePayload.Method): Int {
        val type = Reflection.loadClass(payload.className, env.classLoader) ?: run {
            log.warn("${rule.id}: class ${payload.className} not found in $packageName")
            return 0
        }
        val targets = type.declaredMethods.filter { it.isCandidate(payload) }.mapNotNull { method ->
            val replacement = DefaultReturn.forType(method.returnType, payload.allowNullReturn) as? Replacement.Return
            when {
                replacement == null -> null

                !DefaultReturn.isValidFor(method.returnType, replacement.value) -> null.also {
                    log.warn("${rule.id}: ${method.name} returns ${method.returnType.name}; not hooked")
                }

                else -> method to replacement.value
            }
        }
        val (natives, java) = targets.partition { (method, _) -> Modifier.isNative(method.modifiers) }
        val hooked = java.count { (method, value) -> hook(rule.id, method, value, native = false) }
        val armed = installNatives(rule, payload, natives)
        log.info("${rule.id}: hooked $hooked method(s) of ${payload.className} in $packageName, $armed native")
        return if (hooked == 0 && armed == DEFERRED) DEFERRED else hooked + maxOf(armed, 0)
    }

    /** Hooks [natives] once their library is loaded; returns how many are hooked or armed, or [DEFERRED]. */
    private fun installNatives(rule: Rule, payload: RulePayload.Method, natives: List<Pair<Method, Any?>>): Int {
        if (natives.isEmpty()) return 0
        val library = payload.nativeLibrary ?: run {
            log.warn("${rule.id}: native target without a library to wait for; not hooked")
            return 0
        }
        if (!env.switches.isEnabled(rule.id)) return DEFERRED
        val hookAll = { natives.forEach { (method, value) -> hook(rule.id, method, value, native = true) } }
        if (NativeLibraries.isMapped(library)) {
            hookAll()
            return natives.size
        }
        return if (NativeLibraries.afterLoad(xposed, log, library, hookAll)) natives.size else 0
    }

    private fun hook(ruleId: String, method: Method, value: Any?, native: Boolean): Boolean = log.guard(
        "hook $ruleId ${method.name}",
    ) {
        val hooker = if (native) {
            NativeStub(ruleId, method.name, value)
        } else {
            FailOpenHooker(log, ruleId) {
                if (!env.switches.isEnabled(ruleId)) return@FailOpenHooker Verdict.Proceed
                env.reporter.hit(packageName, ruleId, method.name)
                Verdict.Return(value)
            }.measuredAs(Sinks.METHOD)
        }
        xposed.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(hooker)
        true
    } == true

    /**
     * Replaces a native method outright: it never proceeds, not even when reporting fails, since the
     * original may not be bound yet (see the class comment). Installed only while the rule is on.
     */
    private inner class NativeStub(private val ruleId: String, private val name: String, private val value: Any?) :
        Hooker {
        override fun intercept(chain: Chain): Any? {
            try {
                env.reporter.hit(packageName, ruleId, name)
            } catch (_: Throwable) {
                // Counting is best effort; the call is still answered.
            }
            return value
        }
    }

    private fun Method.isCandidate(payload: RulePayload.Method): Boolean =
        payload.method.matches(name) && !isBridge && !isSynthetic && !Modifier.isAbstract(modifiers) &&
            payload.shape.accepts(parameterTypes.map { it.name }, returnType.name)

    companion object {
        /** [install] found only native targets while the rule was off; nothing to report until a restart. */
        const val DEFERRED = -1
    }
}
