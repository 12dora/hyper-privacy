package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.method.DefaultReturn
import io.github.hyperprivacy.core.method.Replacement
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * No-ops public SDK entry points named by METHOD rules. Hooks are installed regardless of the
 * switch and consult it per call, so toggling takes effect live.
 */
internal class MethodGuard(private val env: GuardEnv) {
    private val xposed get() = env.xposed
    private val log get() = env.log
    private val packageName get() = env.packageName

    /** Returns the number of methods hooked for [rule]. */
    fun install(rule: Rule, payload: RulePayload.Method): Int {
        val type = runCatching { Class.forName(payload.className, false, env.classLoader) }.getOrNull() ?: run {
            log.warn("${rule.id}: class ${payload.className} not found in $packageName")
            return 0
        }
        val hooked = type.declaredMethods.filter { it.isCandidate(payload) }.count { method ->
            when (val replacement = DefaultReturn.forType(method.returnType, payload.allowNullReturn)) {
                Replacement.Skip -> false

                is Replacement.Return -> {
                    hook(rule.id, method, replacement.value)
                    true
                }
            }
        }
        log.info("${rule.id}: hooked $hooked method(s) of ${payload.className} in $packageName")
        return hooked
    }

    private fun hook(ruleId: String, method: Method, value: Any?) {
        xposed.hook(method).intercept(
            FailOpenHooker(log, ruleId) {
                if (!env.switches.isEnabled(ruleId)) return@FailOpenHooker Verdict.Proceed
                env.reporter.hit(packageName, ruleId, method.name)
                Verdict.Return(value)
            }.measuredAs(Sinks.METHOD),
        )
    }

    private fun Method.isCandidate(payload: RulePayload.Method): Boolean =
        payload.method.matches(name) && !isBridge && !isSynthetic && !Modifier.isAbstract(modifiers)
}
