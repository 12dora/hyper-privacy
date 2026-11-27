package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.xposed.url.UrlGuard

/**
 * Installs the network sinks of one process, each exactly once however many rules use it, all
 * sharing one [NetworkPolicy] snapshot:
 * - DNS + TLS (host-only) and the IP connect sink when there are DOMAIN rules;
 * - the URL layer when there are DOMAIN or URL rules (DOMAIN rules are enforced there too, which
 *   covers Cronet-style stacks and WebView, whose DNS/TLS is native).
 *
 * A tracker SDK's URL rule (protected third-party apps) is reported MISSED when the app ships OkHttp
 * under its public names but the OkHttp sink could not be hooked: such rules (reporting paths on
 * shared hosts) have no host-level fallback.
 *
 * The native resolver rule ([RulePayload.NativeResolver], protected third-party apps) shares the
 * same policy: [NativeResolverGuard] hands its host-level patterns to native code.
 *
 * If diagnostics mode is on when the process starts, every sink is installed even without rules
 * so endpoints can be observed. Toggling diagnostics later is live for installed sinks only.
 */
internal class NetworkGuards(private val env: GuardEnv, rules: List<Rule>) {
    // An unexpanded known-trackers rule is a DOMAIN rule here; NetworkPolicy expands it while it is on.
    private val domainRules = rules.filter { rule ->
        rule.payload is RulePayload.Domains || rule.payload == RulePayload.KnownTrackers
    }
    private val urlRules = rules.filter { it.payload is RulePayload.Urls }
    private val nativeRules = rules.filter { it.payload == RulePayload.NativeResolver }

    fun install() {
        val observe = env.switches.observe
        val anyRules = domainRules.isNotEmpty() || urlRules.isNotEmpty() || nativeRules.isNotEmpty()
        if (!anyRules && !observe) return
        val policy = NetworkPolicy(domainRules + urlRules, env.switches, env.log)
        val sinks = SinkContext(env, policy)
        val hostSinks = if (domainRules.isNotEmpty() || observe) installHostSinks(sinks) else false
        val url = env.log.guard("URL guard") { UrlGuard(sinks).install() }
        val urlSinks = url?.any == true
        val incomplete = url?.incomplete == true
        if (observe) env.log.info("diagnostics mode: observing endpoints in ${env.packageName}")
        domainRules.forEach { report(it, hostSinks || urlSinks) }
        urlRules.forEach { rule ->
            // A tracker SDK's path rule only works where its HTTP stack is hooked: if the app ships
            // OkHttp and that sink missed, say so rather than report a rule that silently fails open.
            val trackerRule = TrackerProtection.sdkIdOf(rule.id) != null
            report(rule, urlSinks && !(trackerRule && incomplete))
        }
        nativeRules.forEach { rule ->
            env.log.guard(
                "native resolver",
            ) { NativeResolverGuard(env, policy).install(rule) }
        }
    }

    private fun installHostSinks(sinks: SinkContext): Boolean {
        val dns = env.log.guard("DNS guard") { DnsGuard(sinks).install() } == true
        val tls = env.log.guard("TLS guard") { TlsGuard(sinks).install() } == true
        // Not counted towards rule state: it only complements DNS / TLS for IP-literal connects.
        env.log.guard("connect guard") { ConnectGuard(sinks).install() }
        return dns || tls
    }

    private fun report(rule: Rule, active: Boolean) =
        env.reporter.state(env.packageName, rule.id, if (active) HookState.ACTIVE else HookState.MISSED)
}
