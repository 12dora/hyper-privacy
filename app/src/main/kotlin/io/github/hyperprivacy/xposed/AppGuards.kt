package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.status.HookState
import io.github.libxposed.api.XposedInterface

/** Installs the in-process guards (DOMAIN and METHOD rules) for one app package. */
internal class AppGuards(
    private val xposed: XposedInterface,
    private val log: HookLog,
    private val packageName: String,
    private val classLoader: ClassLoader,
    private val rules: List<Rule>,
) {
    fun install() {
        val switches = RuleSwitches.open(xposed, log)
        val reporter = StatusReporter(log, Reflection::currentApplication)
        installDomainRules(switches, reporter)
        installMethodRules(switches, reporter)
    }

    private fun installDomainRules(switches: RuleSwitches, reporter: StatusReporter) {
        val domainRules = rules.filter { it.payload is RulePayload.Domains }
        if (domainRules.isEmpty()) return
        val policy = DomainPolicy(domainRules, switches, log)
        val dns = log.guard("DNS guard") { DnsGuard(xposed, log, packageName, policy, reporter).install() } == true
        val tls = log.guard("TLS guard") { TlsGuard(xposed, log, packageName, policy, reporter).install() } == true
        val state = if (dns || tls) HookState.ACTIVE else HookState.MISSED
        domainRules.forEach { reporter.state(packageName, it.id, state) }
    }

    private fun installMethodRules(switches: RuleSwitches, reporter: StatusReporter) {
        val guard = MethodGuard(xposed, log, packageName, classLoader, switches, reporter)
        rules.forEach { rule ->
            val payload = rule.payload as? RulePayload.Method ?: return@forEach
            val count = log.guard(rule.id) { guard.install(rule, payload) } ?: 0
            reporter.state(packageName, rule.id, if (count > 0) HookState.ACTIVE else HookState.MISSED)
        }
    }
}
