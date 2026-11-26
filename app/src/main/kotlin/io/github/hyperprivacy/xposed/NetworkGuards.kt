package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.xposed.url.UrlGuard

/**
 * Installs the network sinks of one process, each exactly once however many rules use it, all
 * sharing one [NetworkPolicy] snapshot:
 * - DNS + TLS (host-only) when there are DOMAIN rules;
 * - the URL layer when there are DOMAIN or URL rules (DOMAIN rules are enforced there too, which
 *   covers Cronet-style stacks whose DNS/TLS is native).
 *
 * If diagnostics mode is on when the process starts, every sink is installed even without rules
 * so endpoints can be observed. Toggling diagnostics later is live for installed sinks only.
 */
internal class NetworkGuards(private val env: GuardEnv, rules: List<Rule>) {
    private val domainRules = rules.filter { it.payload is RulePayload.Domains }
    private val urlRules = rules.filter { it.payload is RulePayload.Urls }

    fun install() {
        val observe = env.switches.observe
        if (domainRules.isEmpty() && urlRules.isEmpty() && !observe) return
        val sinks = SinkContext(env, NetworkPolicy(domainRules + urlRules, env.switches, env.log))
        val hostSinks = if (domainRules.isNotEmpty() || observe) installHostSinks(sinks) else false
        val urlSinks = env.log.guard("URL guard") { UrlGuard(sinks).install() } == true
        if (observe) env.log.info("diagnostics mode: observing endpoints in ${env.packageName}")
        domainRules.forEach { report(it, hostSinks || urlSinks) }
        urlRules.forEach { report(it, urlSinks) }
    }

    private fun installHostSinks(sinks: SinkContext): Boolean {
        val dns = env.log.guard("DNS guard") { DnsGuard(sinks).install() } == true
        val tls = env.log.guard("TLS guard") { TlsGuard(sinks).install() } == true
        return dns || tls
    }

    private fun report(rule: Rule, active: Boolean) =
        env.reporter.state(env.packageName, rule.id, if (active) HookState.ACTIVE else HookState.MISSED)
}
