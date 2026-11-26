package io.github.hyperprivacy.core.health

import io.github.hyperprivacy.core.match.UrlMatcher
import io.github.hyperprivacy.core.match.UrlPattern
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.status.CodeLoads
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.system.SettingsObservation

/** Outcome of [TelemetryHeuristics.classify]. */
public enum class EndpointVerdict {
    /** A catalog rule (enabled or not) already covers the endpoint. */
    KNOWN,

    /** Not covered, and its name looks like reporting: a candidate for a new rule. */
    SUSPECTED_REPORTING,

    /** Not covered and nothing suggests reporting. */
    UNREMARKABLE,
}

/**
 * Flags observed endpoints that look like telemetry but no rule covers, so the catalog can grow
 * when a vendor moves a reporting channel.
 *
 * Host and path are split into word tokens on `.`, `/`, `-`, `_`, digits and any other
 * non-letter, then compared whole against [REPORTING_TOKENS]: `log` matches `mqs-log` and
 * `log-sdk` but not `catalog` or `blog`. Any [SERVICE_TOKENS] match vetoes the verdict, because
 * those name vendor services that must never be suggested for blocking.
 */
public object TelemetryHeuristics {
    private val REPORTING_TOKENS: Set<String> = setOf(
        "track", "tracking", "tracker", "stat", "stats", "statistic", "statistics", "mistat", "mistats",
        "log", "logs", "logging", "logger", "applog", "sdklog", "report", "reports", "reporting", "sdkreport",
        "upload", "collect", "collector", "apm", "rum", "crash", "crashlytics", "metric", "metrics",
        "event", "events", "beacon", "beacons", "analytics", "monitor", "rmonitor", "perf",
        "trace", "traces", "ping", "kpi", "ztbox", "telemetry", "ossp", "onetrack",
    )

    /** Vendor services: accounts, push, update/OTA and cloud sync. */
    private val SERVICE_TOKENS: Set<String> = setOf(
        "account", "accounts", "push", "xmpush", "register", "resolver", "update", "updates", "ota", "micloud",
    )

    private val SEPARATOR = Regex("[^a-z]+")

    /** Matcher over every catalog network rule regardless of the user's switches. */
    private val catalogCoverage: UrlMatcher<String> by lazy { coverageOf(Catalog.rules) }

    /** Lower-case word tokens of [text] (see the class doc). */
    public fun tokens(text: String): List<String> = text.lowercase().split(SEPARATOR).filter { it.isNotEmpty() }

    /** Whether [host] + [pathPrefix] look like reporting, ignoring rule coverage. */
    public fun looksLikeReporting(host: String, pathPrefix: String?): Boolean {
        val words = tokens(host) + tokens(pathPrefix.orEmpty())
        return words.none { it in SERVICE_TOKENS } && words.any { it in REPORTING_TOKENS }
    }

    /** Classifies one endpoint against the catalog [rules] (default: the shipped catalog). */
    public fun classify(host: String, pathPrefix: String?, rules: List<Rule> = Catalog.rules): EndpointVerdict {
        val coverage = if (rules === Catalog.rules) catalogCoverage else coverageOf(rules)
        return classify(host, pathPrefix, coverage)
    }

    /**
     * Observed endpoints that were not blocked, are not covered by any rule in [rules], and look
     * like reporting, in input order. Settings reads recorded by system_server
     * ([SettingsObservation]) are not network endpoints and never count.
     */
    public fun suspected(
        endpoints: List<ObservedEndpoint>,
        rules: List<Rule> = Catalog.rules,
    ): List<ObservedEndpoint> {
        val coverage = if (rules === Catalog.rules) catalogCoverage else coverageOf(rules)
        return endpoints.filter {
            !it.blocked && !SettingsObservation.isSettingsHost(it.host) &&
                classify(it.host, it.pathPrefix, coverage) == EndpointVerdict.SUSPECTED_REPORTING
        }
    }

    private fun classify(host: String, pathPrefix: String?, coverage: UrlMatcher<String>): EndpointVerdict = when {
        // Code-load records ride the endpoint channel but are not endpoints.
        CodeLoads.isCodeLoad(host) -> EndpointVerdict.UNREMARKABLE

        coverage.find(host, pathPrefix) != null -> EndpointVerdict.KNOWN

        looksLikeReporting(host, pathPrefix) -> EndpointVerdict.SUSPECTED_REPORTING

        else -> EndpointVerdict.UNREMARKABLE
    }

    private fun coverageOf(rules: List<Rule>): UrlMatcher<String> = UrlMatcher.of(
        rules.flatMap { rule ->
            when (val payload = rule.payload) {
                is RulePayload.Domains -> payload.suffixes.map { UrlPattern(it) to rule.id }
                is RulePayload.Urls -> payload.prefixes.map { UrlPattern(it.host, it.pathPrefix) to rule.id }
                else -> emptyList()
            }
        },
    )
}
