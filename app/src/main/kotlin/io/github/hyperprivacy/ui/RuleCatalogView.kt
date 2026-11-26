package io.github.hyperprivacy.ui

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import io.github.hyperprivacy.core.health.Health
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.scope.DeviceCoverage
import io.github.hyperprivacy.core.status.DisplayStatus
import io.github.hyperprivacy.status.StatusStore
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** An installed target app. */
internal data class AppLabel(val packageName: String, val label: String)

/** Target apps that are installed on this device, with their display names. */
internal class InstalledApps private constructor(
    private val pm: PackageManager,
    private val languageTag: String,
    val ordered: List<AppLabel>,
) {
    private val labels = ConcurrentHashMap<String, String>(ordered.associate { it.packageName to it.label })

    /** Installed catalog packages; compared across loads to notice installs and removals. */
    val packages: Set<String> = ordered.mapTo(HashSet()) { it.packageName }

    /** Display name of any installed package; falls back to the package name for unknown ones. */
    fun labelOf(packageName: String): String = labels.getOrPut(packageName) {
        systemLabel(pm, packageName)?.let { AppNames.display(packageName, it, languageTag) } ?: packageName
    }

    /** Whether this load still matches [installed] packages and the current locale (labels are localised). */
    fun matches(installed: Set<String>): Boolean =
        installed == packages && languageTag == Locale.getDefault().toLanguageTag()

    companion object {
        /**
         * Which of [packages] are installed for this user, in one package manager call. Visibility comes
         * from the generated `<queries>` manifest, which lists every catalog package; lint cannot see
         * that build-time manifest, and only catalog packages are kept anyway.
         */
        @SuppressLint("QueryPermissionsNeeded")
        fun installed(pm: PackageManager, packages: Set<String>): Set<String> = runCatching {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                .mapNotNullTo(HashSet()) { info -> info.packageName.takeIf { it in packages } }
        }.getOrElse { packages.filterTo(HashSet()) { systemLabel(pm, it) != null } }

        private fun systemLabel(pm: PackageManager, packageName: String): String? = runCatching {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString()
        }.getOrNull()

        fun load(pm: PackageManager, packages: Set<String>): InstalledApps {
            val locale = Locale.getDefault()
            val tag = locale.toLanguageTag()
            val installed = packages.mapNotNull { pkg ->
                systemLabel(pm, pkg)?.let { AppLabel(pkg, AppNames.display(pkg, it, tag)) }
            }
            val collator = Collator.getInstance(locale)
            return InstalledApps(pm, tag, installed.sortedWith { a, b -> collator.compare(a.label, b.label) })
        }
    }
}

/** Rule list and groupings derived from the catalog, the user's switches and the status reports. */
internal class RuleSnapshot(val rules: List<RuleItem>, val apps: List<AppSummary>) {
    val byCategory: List<CategoryGroup> = groupByCategory(rules)
    val enabledCount: Int = rules.count { it.enabled }
    val totalHits: Long = rules.sumOf { it.hits }
    val anyReported: Boolean = rules.any { it.status != DisplayStatus.NOT_RUN }
}

/**
 * Builds [RuleSnapshot]s. Rules whose target apps are all missing from the device are left out,
 * so the list only shows protections that can matter here.
 *
 * Health is the expensive part (package manager lookups per target), so each rule's result is
 * reused for [HEALTH_TTL_MS] unless its switch changed. Confined to the view model's worker.
 */
internal class RuleCatalogView(
    private val statusStore: StatusStore,
    private val healthSource: RuleHealthSource,
    val apps: InstalledApps,
    val rules: List<Rule>,
) {
    private class CachedHealth(val enabled: Boolean, val at: Long, val byPackage: Map<String, Health>)

    val visibleRules: List<Rule> = DeviceCoverage.applicableRules(rules, apps.packages)
    private val packagesByRule: Map<String, List<AppLabel>> = visibleRules.associate { rule ->
        rule.id to apps.ordered.filter { it.packageName in rule.packages }
    }
    private val healthCache = HashMap<String, CachedHealth>()

    fun snapshot(isEnabled: (Rule) -> Boolean): RuleSnapshot {
        val now = System.currentTimeMillis()
        val items = visibleRules.map { it.toItem(isEnabled(it), now) }
        return RuleSnapshot(items, summarizeApps(items, apps.ordered))
    }

    private fun healthOf(rule: Rule, enabled: Boolean, now: Long): Map<String, Health> {
        val cached = healthCache[rule.id]
        if (cached != null && cached.enabled == enabled && now - cached.at < HEALTH_TTL_MS) return cached.byPackage
        val fresh = runCatching { healthSource.health(rule, enabled) }.getOrDefault(emptyMap())
        healthCache[rule.id] = CachedHealth(enabled, now, fresh)
        return fresh
    }

    private fun Rule.toItem(enabled: Boolean, now: Long): RuleItem {
        val health = healthOf(this, enabled, now)
        val perApp = packagesByRule.getValue(id).map { app ->
            val stored = statusStore.get(app.packageName, id)
            val status = if (stored?.isFromCurrentBoot == true) {
                DisplayStatus.of(listOf(stored.state))
            } else {
                DisplayStatus.NOT_RUN
            }
            AppStatus(
                packageName = app.packageName,
                label = app.label,
                status = status,
                hits = stored?.totalHits ?: 0,
                health = health[app.packageName] ?: fallbackHealth(status),
            )
        }
        val reported = perApp.map { it.status }.filter { it != DisplayStatus.NOT_RUN }
        return RuleItem(
            rule = this,
            enabled = enabled,
            status = if (reported.any { it == DisplayStatus.ACTIVE }) {
                DisplayStatus.ACTIVE
            } else {
                reported.firstOrNull() ?: DisplayStatus.NOT_RUN
            },
            hits = perApp.sumOf { it.hits },
            apps = perApp,
            health = Health.worst(perApp.map { it.health }),
        )
    }

    private companion object {
        const val HEALTH_TTL_MS = 10_000L

        /** When the health source has no answer, the hook status is the best evidence. */
        fun fallbackHealth(status: DisplayStatus): Health = when (status) {
            DisplayStatus.ACTIVE -> Health.OK
            DisplayStatus.MISSED -> Health.BROKEN
            DisplayStatus.NOT_RUN -> Health.NOT_RUN
        }
    }
}
