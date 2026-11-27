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
 * effect live. Native methods are different: running the original before the class's own
 * initialiser has bound the library could fail with `UnsatisfiedLinkError`, and forcing that
 * initialiser early can poison the class for the whole process. So a native method is hooked only
 * when its rule is on as the process starts, and its hook never calls through; a change of the
 * switch applies after the app restarts.
 */
internal class MethodGuard(private val env: GuardEnv) {
    private val xposed get() = env.xposed
    private val log get() = env.log
    private val packageName get() = env.packageName

    /** Returns the number of methods hooked, or [DEFERRED] when native targets wait for the rule to be on at start. */
    fun install(rule: Rule, payload: RulePayload.Method): Int {
        val type = Reflection.loadClass(payload.className, env.classLoader) ?: run {
            log.warn("${rule.id}: class ${payload.className} not found in $packageName")
            return 0
        }
        val candidates = type.declaredMethods.filter { it.isCandidate(payload) }
        val enabledAtStart = env.switches.isEnabled(rule.id)
        var deferred = false
        val hooked = candidates.count { method ->
            val native = Modifier.isNative(method.modifiers)
            if (native && !enabledAtStart) {
                deferred = true
                return@count false
            }
            when (val replacement = DefaultReturn.forType(method.returnType, payload.allowNullReturn)) {
                Replacement.Skip -> false

                is Replacement.Return -> {
                    if (!DefaultReturn.isValidFor(method.returnType, replacement.value)) {
                        log.warn("${rule.id}: ${method.name} returns ${method.returnType.name}; not hooked")
                        false
                    } else {
                        hook(rule.id, method, replacement.value, native)
                    }
                }
            }
        }
        log.info("${rule.id}: hooked $hooked method(s) of ${payload.className} in $packageName")
        return if (hooked == 0 && deferred) DEFERRED else hooked
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
