package io.github.hyperprivacy.core.health

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.status.SinkReport

/**
 * Health of a rule in one package, with the installed and surveyed versions.
 *
 * @property lastActiveVersionCode app version the rule was last ACTIVE with on this device.
 */
public data class PackageHealthRow(
    val packageName: String,
    val health: Health,
    val currentVersionCode: Long?,
    val baselineVersionCode: Long?,
    val lastActiveVersionCode: Long? = null,
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
 * @property deviceModel `Build.MODEL`; a model name, not a serial or id.
 * @property deviceCodename `Build.DEVICE`.
 * @property onSurveyedDevice whether the static baseline applies to this device (else versions are
 *   compared with device-local history).
 * @property missedSinks sinks that reported MISSED this boot.
 * @property includeUserApps the user chose, at export time, to include rows about packages outside
 *   the catalog (protected third-party apps, apps whose Settings reads were observed). Default:
 *   such rows are left out and only counted ([DiagnosticReport.userAppRows]).
 */
public data class DiagnosticInput(
    val moduleVersion: String,
    val sdkInt: Int,
    val romIncremental: String,
    val deviceModel: String = "",
    val deviceCodename: String = "",
    val onSurveyedDevice: Boolean = false,
    val rules: List<RuleHealthRow>,
    val missedSinks: List<SinkReport>,
    val suspectedEndpoints: List<ObservedEndpoint>,
    val includeUserApps: Boolean = false,
)

/**
 * Renders a Markdown report a user can attach to an issue so the catalog can be updated.
 *
 * Privacy: the report contains only catalog-derived data (rule ids, vendor package names,
 * version codes, sink names), the device model and codename (never serials or ids), and endpoint
 * host + path prefix. Free-text fields are reduced to `[A-Za-z0-9._+-]`, invalid hosts are dropped, paths are cut to [ObservedEndpoint.pathPrefixOf]
 * (no query or fragment), and path segments that look like ids (long digit runs, long hex) are
 * masked. No timestamps, device ids or accounts are included, and no user-installed app names or
 * their endpoints unless [DiagnosticInput.includeUserApps] is set.
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

    /** Whether [packageName] is part of the catalog (a vendor target or system_server). */
    public fun isCatalogPackage(packageName: String): Boolean =
        packageName == Catalog.SYSTEM_SCOPE || packageName in Catalog.allPackages

    /** Rows of [input] about packages outside the catalog: what [DiagnosticInput.includeUserApps] decides on. */
    public fun userAppRows(input: DiagnosticInput): Int =
        input.missedSinks.count { !isCatalogPackage(it.packageName) } +
            input.suspectedEndpoints.count { !isCatalogPackage(it.packageName) }

    public fun render(input: DiagnosticInput): String = buildString {
        val keep = { pkg: String -> input.includeUserApps || isCatalogPackage(pkg) }
        val omitted = if (input.includeUserApps) 0 else userAppRows(input)
        appendLine("# HyperPrivacy diagnostic report")
        appendLine()
        appendLine("- module: ${field(input.moduleVersion)}")
        appendLine("- android sdk: ${input.sdkInt}")
        appendLine("- rom: ${field(input.romIncremental)}")
        appendLine("- device: ${field(input.deviceModel)} (${field(input.deviceCodename)})")
        appendLine("- baseline device: ${if (input.onSurveyedDevice) "yes" else "no"}")
        appendLine()
        appendRules(input.rules)
        appendNotApplicable(input.rules)
        appendSinks(input.missedSinks.filter { keep(it.packageName) })
        appendEndpoints(input.suspectedEndpoints.filter { keep(it.packageName) })
        if (omitted > 0) {
            appendLine()
            appendLine("($omitted rows about user-installed apps left out)")
        }
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
                val versions = "installed $current, baseline ${pkg.baselineVersionCode ?: NONE}, " +
                    "last active ${pkg.lastActiveVersionCode ?: NONE}"
                appendLine("  - ${packageName(pkg.packageName)}: ${pkg.health} ($versions)")
            }
        }
        appendLine()
    }

    /** Per rule, how many target packages lack what it hooks: candidates for trimming the catalog. */
    private fun StringBuilder.appendNotApplicable(rules: List<RuleHealthRow>) {
        appendLine("## Not applicable")
        appendLine()
        val rows = rules.mapNotNull { rule ->
            val count = rule.packages.count { it.health == Health.NOT_APPLICABLE }
            Triple(rule.ruleId, count, rule.packages.size).takeIf { count > 0 }
        }.sortedWith(compareBy({ -it.second }, { it.first }))
        if (rows.isEmpty()) appendLine("(none)")
        rows.forEach { (ruleId, count, total) -> appendLine("- ${field(ruleId)}: $count of $total packages") }
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
