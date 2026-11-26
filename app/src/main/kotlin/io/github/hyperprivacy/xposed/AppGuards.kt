package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.xposed.id.IdentifierGuard
import io.github.libxposed.api.XposedInterface

/**
 * Installs the in-process guards (DOMAIN, URL, METHOD and IDENTIFIER rules) for one app process.
 * All work is proportional to [rules], the rules targeting this package.
 */
internal class AppGuards(
    private val xposed: XposedInterface,
    private val log: HookLog,
    private val packageName: String,
    private val classLoader: ClassLoader,
    private val rules: List<Rule>,
) {
    fun install() {
        val switches = RuleSwitches.open(xposed, log, rules)
        log.verbose = { switches.observe }
        val reporter = StatusReporter(log, Reflection::currentApplication)
        val env = GuardEnv(xposed, log, packageName, classLoader, switches, reporter)
        log.guard("network guards") { NetworkGuards(env, rules).install() }
        installMethodRules(env)
        installIdentifierRules(env)
    }

    private fun installMethodRules(env: GuardEnv) {
        val guard by lazy { MethodGuard(env) }
        rules.forEach { rule ->
            val payload = rule.payload as? RulePayload.Method ?: return@forEach
            val count = log.guard(rule.id) { guard.install(rule, payload) } ?: 0
            env.reporter.state(packageName, rule.id, if (count > 0) HookState.ACTIVE else HookState.MISSED)
        }
    }

    private fun installIdentifierRules(env: GuardEnv) {
        val guard by lazy { IdentifierGuard(env) }
        rules.forEach { rule ->
            val payload = rule.payload as? RulePayload.Identifiers ?: return@forEach
            val count = log.guard(rule.id) { guard.install(rule, payload) } ?: 0
            env.reporter.state(packageName, rule.id, if (count > 0) HookState.ACTIVE else HookState.MISSED)
        }
    }
}
