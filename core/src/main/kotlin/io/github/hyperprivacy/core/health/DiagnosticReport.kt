package io.github.hyperprivacy.core.health

import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.status.SinkReport

/** Health of a rule in one package, with the installed and surveyed versions. */
public data class PackageHealthRow(
    val packageName: String,
    val health: Health,
    val currentVersionCode: Long?,
    val baselineVersionCode: Long?,
)

/** Health of one rule: [health] is the aggregate over [packages]. */
public data class RuleHealthRow(
    val ruleId: String,
    val enabled: Boolean,
    val health: Health,
    val packages: List<PackageHealthRow>,
)

/**
 * Inputs of [DiagnosticReport.render]. Only these fields can ever reach the report.
 *
 * @property romIncremental `Build.VERSION.INCREMENTAL`, passed in so this stays pure.
 * @property missedSinks sinks that reported MISSED this boot.
 */
public data class DiagnosticInput(
    val moduleVersion: String,
    val sdkInt: Int,
    val romIncremental: String,
    val rules: List<RuleHealthRow>,
    val missedSinks: List<SinkReport>,
    val suspectedEndpoints: List<ObservedEndpoint>,
)

/**
 * Renders a Markdown report a user can attach to an issue so the catalog can be updated.
 *
 * Privacy: the report contains only catalog-derived data (rule ids, vendor package names,
 * version codes, sink names) and endpoint host + path prefix. Free-text fields are reduced to
 * `[A-Za-z0-9._+-]`, invalid hosts are dropped, paths are cut to [ObservedEndpoint.pathPrefixOf]
 * (no query or fragment), and path segments that look like ids (long digit runs, long hex) are
 * masked. No timestamps, device ids, accounts or user-installed app names are included.
 */
public object DiagnosticReport {
    private const val MAX_FIELD_LENGTH = 64
    private const val MAX_DIGIT_RUN = 5
    private const val MIN_HEX_ID_LENGTH = 16
    private const val MASK = "*"
    private const val NONE = "-"

    private val UNSAFE_FIELD_CHARS = Regex("[^A-Za-z0-9._+-]")
    private val LONG_DIGIT_RUN = Regex("[0-9]{${MAX_DIGIT_RUN + 1},}")
    private val HEX_ID = Regex("[0-9a-fA-F-]{$MIN_HEX_ID_LENGTH,}")
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    public fun render(input: DiagnosticInput): String = buildString {
        appendLine("# HyperPrivacy diagnostic report")
        appendLine()
        appendLine("- module: ${field(input.moduleVersion)}")
        appendLine("- android sdk: ${input.sdkInt}")
        appendLine("- rom: ${field(input.romIncremental)}")
        appendLine()
        appendRules(input.rules)
        appendSinks(input.missedSinks)
        appendEndpoints(input.suspectedEndpoints)
    }

    private fun StringBuilder.appendRules(rules: List<RuleHealthRow>) {
        appendLine("## Rules")
        appendLine()
        if (rules.isEmpty()) appendLine("(none)")
        rules.forEach { rule ->
            val state = if (rule.enabled) "on" else "off"
            appendLine("- ${field(rule.ruleId)} [$state] ${rule.health}")
            rule.packages.forEach { pkg ->
                val current = pkg.currentVersionCode ?: NONE
                val versions = "installed $current, baseline ${pkg.baselineVersionCode ?: NONE}"
                appendLine("  - ${packageName(pkg.packageName)}: ${pkg.health} ($versions)")
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendSinks(sinks: List<SinkReport>) {
        appendLine("## Missed sinks")
        appendLine()
        if (sinks.isEmpty()) appendLine("(none)")
        sinks.sortedWith(compareBy({ it.packageName }, { it.sink })).forEach {
            appendLine("- ${packageName(it.packageName)}: ${field(it.sink)}")
        }
        appendLine()
    }

    private fun StringBuilder.appendEndpoints(endpoints: List<ObservedEndpoint>) {
        appendLine("## Suspected reporting endpoints")
        appendLine()
        val rows = endpoints.mapNotNull(::endpoint).distinct().sorted()
        if (rows.isEmpty()) appendLine("(none)")
        rows.forEach { appendLine("- $it") }
    }

    /** `package host/path`, or `null` when the host is not a canonical host name. */
    private fun endpoint(e: ObservedEndpoint): String? {
        if (!ObservedEndpoint.isValidHost(e.host)) return null
        val path = ObservedEndpoint.pathPrefixOf(e.pathPrefix)?.let(::maskPath).orEmpty()
        return "${packageName(e.packageName)} ${e.host}$path"
    }

    private fun maskPath(path: String): String = path.split('/').joinToString("/") { segment ->
        val safe = UNSAFE_FIELD_CHARS.replace(segment, "")
        if (LONG_DIGIT_RUN.containsMatchIn(safe) || HEX_ID.matches(safe)) MASK else safe
    }

    private fun packageName(text: String): String = if (PACKAGE_NAME.matches(text)) text else MASK

    private fun field(text: String): String =
        UNSAFE_FIELD_CHARS.replace(text, "").take(MAX_FIELD_LENGTH).ifEmpty { NONE }
}
