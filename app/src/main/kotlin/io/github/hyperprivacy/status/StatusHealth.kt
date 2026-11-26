package io.github.hyperprivacy.status

import android.content.pm.PackageManager
import android.os.Build
import io.github.hyperprivacy.core.health.ActivationHistory
import io.github.hyperprivacy.core.health.DiagnosticInput
import io.github.hyperprivacy.core.health.DiagnosticReport
import io.github.hyperprivacy.core.health.Health
import io.github.hyperprivacy.core.health.HealthClock
import io.github.hyperprivacy.core.health.HealthSignals
import io.github.hyperprivacy.core.health.PackageHealthRow
import io.github.hyperprivacy.core.health.RuleHealth
import io.github.hyperprivacy.core.health.RuleHealthRow
import io.github.hyperprivacy.core.health.TelemetryHeuristics
import io.github.hyperprivacy.core.rule.Baseline
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.DeviceBuild
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.scope.DeviceCoverage
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.status.ProcessMeta

/*
 * Rule health queries over a [StatusStore], for the UI. All of them read SharedPreferences and the
 * PackageManager, so call them off the main thread.
 */

/** Health of [rule] in each of its target packages, plus versions; [RuleHealthRow.health] is the aggregate. */
internal fun StatusStore.ruleHealthRow(rule: Rule, enabled: Boolean = true): RuleHealthRow =
    HealthSnapshot(this).row(rule, enabled)

/** Health of [rule] per target package. */
internal fun StatusStore.ruleHealth(rule: Rule, enabled: Boolean = true): Map<String, Health> =
    ruleHealthRow(rule, enabled).packages.associate { it.packageName to it.health }

/** Number of catalog rules in each aggregate [Health] state; every state is present (possibly 0). */
internal fun StatusStore.healthSummary(isEnabled: (Rule) -> Boolean = { true }): Map<Health, Int> {
    val snapshot = HealthSnapshot(this)
    val counts = Catalog.rules.groupingBy { snapshot.row(it, isEnabled(it)).health }.eachCount()
    return Health.entries.associateWith { counts[it] ?: 0 }
}

/**
 * What system_server has told the module since this boot: whether it reported at all, and the
 * module build it runs ([ProcessMeta.MODULE]; `null` if it reported none, i.e. it runs a build that
 * predates reporting it).
 */
internal data class SystemReport(val reportedThisBoot: Boolean, val moduleVersion: Long?)

internal fun StatusStore.systemReport(): SystemReport {
    val bootAt = bootTimeMillis()
    val meta = meta.get(ProcessMeta.MODULE)?.takeIf { it.lastSeenAt >= bootAt }
    val sinksReported = sinkStates(since = bootAt).any { it.packageName == Catalog.SYSTEM_SCOPE }
    return SystemReport(reportedThisBoot = meta != null || sinksReported, moduleVersion = meta?.versionCode)
}

/** Observed endpoints that no catalog rule covers, were never blocked, and look like reporting. */
internal fun StatusStore.suspectedEndpoints(): List<ObservedEndpoint> =
    TelemetryHeuristics.suspected(observedEndpoints())

/** This device's model, codename and ROM build; no serials or ids. */
private fun currentBuild(): DeviceBuild = DeviceBuild(
    model = Build.MODEL.orEmpty(),
    device = Build.DEVICE.orEmpty(),
    incremental = Build.VERSION.INCREMENTAL.orEmpty(),
)

/**
 * The inputs of the shareable Markdown report ([DiagnosticReport]): device model and ROM build,
 * rule health with versions, sinks MISSED this boot and suspected endpoints. Rows about
 * user-installed apps are left out unless [includeUserApps].
 */
internal fun StatusStore.diagnosticInput(
    moduleVersion: String,
    includeUserApps: Boolean,
    isEnabled: (Rule) -> Boolean,
): DiagnosticInput {
    val snapshot = HealthSnapshot(this)
    val input = DiagnosticInput(
        moduleVersion = moduleVersion,
        sdkInt = Build.VERSION.SDK_INT,
        romIncremental = snapshot.build.incremental,
        deviceModel = snapshot.build.model,
        deviceCodename = snapshot.build.device,
        onSurveyedDevice = snapshot.onSurveyedDevice,
        rules = Catalog.rules.map { snapshot.row(it, isEnabled(it)) },
        missedSinks = sinkStates(since = snapshot.clock.bootAt).filter { it.state == HookState.MISSED },
        suspectedEndpoints = suspectedEndpoints(),
        includeUserApps = includeUserApps,
        sinkPerf = perf.all(),
    )
    return input
}

/** The shareable report; see [diagnosticInput]. */
internal fun StatusStore.diagnosticReport(
    moduleVersion: String,
    includeUserApps: Boolean,
    isEnabled: (Rule) -> Boolean,
): String = DiagnosticReport.render(diagnosticInput(moduleVersion, includeUserApps, isEnabled))

/** Whether the report would hold rows about user-installed apps, which the user must opt in to share. */
internal fun StatusStore.reportMentionsUserApps(): Boolean {
    val bootAt = HealthSnapshot(this).clock.bootAt
    val missed = sinkStates(since = bootAt).filter { it.state == HookState.MISSED }
    return missed.any { !DiagnosticReport.isCatalogPackage(it.packageName) } ||
        suspectedEndpoints().any { !DiagnosticReport.isCatalogPackage(it.packageName) }
}

/** The inputs of [RuleHealth] at one instant; package lookups are memoised for the pass. */
private class HealthSnapshot(private val store: StatusStore) {
    val clock: HealthClock = System.currentTimeMillis().let { now -> HealthClock(now, bootAt = bootTimeMillis()) }
    val build: DeviceBuild = currentBuild()

    /** Whether the static [Baseline] pins apply here; elsewhere versions are compared with local history. */
    val onSurveyedDevice: Boolean = Baseline.isSurveyed(build)

    /** Installed versionCode per package; a `null` value means not installed. */
    private val installed = HashMap<String, Long?>()

    private val sinksByPackage: Map<String, Map<String, HookState>> by lazy {
        store.sinkStates(since = clock.bootAt)
            .groupBy { it.packageName }
            .mapValues { (_, reports) -> reports.associate { it.sink to it.state } }
    }

    private val sinksEverActive: Map<String, Set<String>> by lazy { store.sinksEverActive() }

    fun row(rule: Rule, enabled: Boolean): RuleHealthRow {
        val packages = rule.packages.sorted().map { pkg ->
            val signals = signals(rule, pkg, enabled)
            PackageHealthRow(
                packageName = pkg,
                health = RuleHealth.evaluate(rule, signals, clock),
                currentVersionCode = signals.currentVersionCode,
                baselineVersionCode = signals.baselineVersionCode,
                lastActiveVersionCode = signals.history.lastActiveVersion,
            )
        }
        return RuleHealthRow(rule.id, enabled, Health.worst(packages.map { it.health }), packages)
    }

    private fun signals(rule: Rule, pkg: String, enabled: Boolean): HealthSignals {
        val version = installedVersion(pkg)
        val status = store.get(pkg, rule.id)
        val meta = store.meta.get(pkg)
        return HealthSignals(
            installed = version != null,
            currentVersionCode = version,
            baselineVersionCode = Baseline.of(pkg)?.versionCode,
            onSurveyedDevice = onSurveyedDevice,
            history = status?.history ?: ActivationHistory.NEVER,
            ruleState = status?.state,
            ruleReportedAt = status?.reportedAt,
            sinkStates = sinksByPackage[pkg].orEmpty(),
            sinksEverActive = sinksEverActive[pkg].orEmpty(),
            lastProcessSeenAt = meta?.lastSeenAt,
            activeSince = status?.activeSince,
            lastHitAt = status?.lastHitAt,
            enabled = enabled,
        )
    }

    private fun installedVersion(pkg: String): Long? {
        if (pkg in installed) return installed[pkg]
        val version = runCatching {
            val installedPkg = DeviceCoverage.versionPackage(pkg)
            store.packageManager.getPackageInfo(installedPkg, PackageManager.PackageInfoFlags.of(0)).longVersionCode
        }.getOrNull()
        installed[pkg] = version
        return version
    }
}
