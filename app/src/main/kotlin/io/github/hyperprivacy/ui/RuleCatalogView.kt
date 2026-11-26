package io.github.hyperprivacy.ui

import android.content.pm.PackageManager
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.status.DisplayStatus
import io.github.hyperprivacy.status.StatusStore
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Target apps that are installed on this device, with their display names. */
internal class InstalledApps private constructor(
    private val pm: PackageManager,
    private val languageTag: String,
    val ordered: List<AppLabel>,
) {
    private val labels = ConcurrentHashMap<String, String>(ordered.associate { it.packageName to it.label })

    /** Display name of any installed package; falls back to the package name for unknown ones. */
    fun labelOf(packageName: String): String = labels.getOrPut(packageName) {
        systemLabel(pm, packageName)?.let { AppNames.display(packageName, it, languageTag) } ?: packageName
    }

    companion object {
        private fun systemLabel(pm: PackageManager, packageName: String): String? = runCatching {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString()
        }.getOrNull()

        fun load(pm: PackageManager): InstalledApps {
            val locale = Locale.getDefault()
            val tag = locale.toLanguageTag()
            val installed = Catalog.allPackages.mapNotNull { pkg ->
                systemLabel(pm, pkg)?.let { AppLabel(pkg, AppNames.display(pkg, it, tag)) }
            }
            val collator = Collator.getInstance(locale)
            return InstalledApps(pm, tag, installed.sortedWith { a, b -> collator.compare(a.label, b.label) })
        }
    }
}

/** Rule list and groupings derived from the catalog, the user's switches and the status reports. */
internal class RuleSnapshot(val rules: List<RuleItem>, val byCategory: List<RuleGroup>, val byApp: List<RuleGroup>) {
    val enabledCount: Int = rules.count { it.enabled }
    val totalHits: Long = rules.sumOf { it.hits }
    val anyReported: Boolean = rules.any { it.status != DisplayStatus.NOT_RUN }
}

/**
 * Builds [RuleSnapshot]s. Rules whose target apps are all missing from the device are left out,
 * so the list only shows protections that can matter here.
 */
internal class RuleCatalogView(private val statusStore: StatusStore, val apps: InstalledApps) {
    private val packagesByRule: Map<String, List<AppLabel>> = Catalog.rules.associate { rule ->
        rule.id to apps.ordered.filter { it.packageName in rule.packages }
    }
    private val visibleRules: List<Rule> = Catalog.rules.filter { packagesByRule.getValue(it.id).isNotEmpty() }

    fun snapshot(isEnabled: (Rule) -> Boolean): RuleSnapshot {
        val items = visibleRules.map { it.toItem(isEnabled(it)) }
        return RuleSnapshot(items, groupByCategory(items), groupByApp(items, apps.ordered))
    }

    private fun Rule.toItem(enabled: Boolean): RuleItem {
        val perApp = packagesByRule.getValue(id).map { app ->
            val stored = statusStore.get(app.packageName, id)
            AppStatus(
                packageName = app.packageName,
                label = app.label,
                status = if (stored?.isFromCurrentBoot == true) {
                    DisplayStatus.of(
                        listOf(stored.state),
                    )
                } else {
                    DisplayStatus.NOT_RUN
                },
                hits = stored?.totalHits ?: 0,
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
        )
    }
}
