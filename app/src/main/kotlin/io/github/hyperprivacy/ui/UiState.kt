package io.github.hyperprivacy.ui

import androidx.compose.runtime.Immutable
import io.github.hyperprivacy.core.config.ProtectionPause
import io.github.hyperprivacy.core.conflict.OverlapArea
import io.github.hyperprivacy.core.health.Health
import io.github.hyperprivacy.core.health.RebootReason
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.status.DisplayStatus

/** Whether the module is working, as far as the app can tell. */
internal enum class ModuleState(val canEdit: Boolean) {
    /** Waiting for the framework binder; shown briefly right after launch. */
    CHECKING(canEdit = false),

    /** The framework has not bound the module: it is not enabled in LSPosed. */
    INACTIVE(canEdit = false),

    /** Bound, but the shared preferences cannot be reached. */
    UNAVAILABLE(canEdit = false),

    /** Rules can be edited, but no hook has reported since boot. */
    NEEDS_REBOOT(canEdit = true),

    ACTIVE(canEdit = true),
}

/** Health states the user should look at; the rest (including NOT_APPLICABLE) are fine or informational. */
internal val Health.needsAttention: Boolean
    get() = this == Health.BROKEN || this == Health.NEEDS_REVIEW || this == Health.IDLE

/** Status of one rule in one target app. */
@Immutable
internal data class AppStatus(
    val packageName: String,
    val label: String,
    val status: DisplayStatus,
    val hits: Long,
    val health: Health,
)

/** A catalog rule together with its live state. [apps] only lists apps installed on this device. */
@Immutable
internal data class RuleItem(
    val rule: Rule,
    val enabled: Boolean,
    val status: DisplayStatus,
    val hits: Long,
    val apps: List<AppStatus>,
    val health: Health,
) {
    val id: String get() = rule.id

    /** Target apps of this rule that are not installed here; shown only as a quiet footnote. */
    val notInstalledCount: Int get() = rule.packages.size - apps.size

    /** Only an enabled rule can need attention: a switched-off rule has nothing to fix. */
    val attention: Health? get() = health.takeIf { enabled && it.needsAttention }
}

/** The part of a rule row that says which app(s) it belongs to. */
internal sealed interface RowMeta {
    data object None : RowMeta

    data class App(val label: String) : RowMeta

    data class Apps(val count: Int) : RowMeta
}

/** One rule as displayed in a list; [health] is scoped to the list's app when it has one. */
@Immutable
internal data class RuleEntry(val item: RuleItem, val meta: RowMeta, val health: Health) {
    val attention: Health? get() = health.takeIf { item.enabled && it.needsAttention }
}

/** All rules of one category. */
@Immutable
internal data class CategoryGroup(val category: Category, val entries: List<RuleEntry>) {
    val key: String get() = keyOf(category)
    val enabledCount: Int = entries.count { it.item.enabled }
    val attentionCount: Int = entries.count { it.attention != null }

    companion object {
        /** The unfolding key of [category]'s group on the System tab. */
        fun keyOf(category: Category): String = "c:${category.name}"
    }
}

/** An installed app with rules, for the by-app list. */
@Immutable
internal data class AppSummary(
    val packageName: String,
    val label: String,
    val ruleCount: Int,
    val enabledCount: Int,
    val attentionCount: Int,
)

/** What the Home attention entry summarises. Empty means the entry is hidden. */
@Immutable
internal data class Attention(val broken: Int = 0, val review: Int = 0, val idle: Int = 0, val suspected: Int = 0) {
    val rules: Int get() = broken + review + idle
    val isEmpty: Boolean get() = rules == 0 && suspected == 0
}

/** The overlapping modules of one [area], by their own labels (shown as is), in a stable order. */
@Immutable
internal data class ConflictGroup(val area: OverlapArea, val labels: List<String>)

/** One overlapping module as found on this device: its own label, its package and what it overlaps in. */
@Immutable
internal data class ConflictModule(val packageName: String, val label: String, val areas: List<OverlapArea>)

/**
 * Other installed modules that overlap with this one, for the Home hint and its detail page:
 * [modules] one by one, and one [groups] entry per overlapping area (in [OverlapArea] order),
 * naming every module in it; a module doing several things is named under each.
 * [canDisableAppList] says one of them hides the app list and this module's own app-list hiding is
 * on (so the one-tap "turn it off" action makes sense).
 */
@Immutable
internal data class ConflictUi(
    val groups: List<ConflictGroup>,
    val canDisableAppList: Boolean,
    val modules: List<ConflictModule> = emptyList(),
) {
    val appListOverlap: Boolean get() = groups.any { it.area == OverlapArea.APP_LIST }

    companion object {
        /**
         * The hint for [found] modules, or `null` when none overlaps in anything. A blank label
         * falls back to the package; areas are deduplicated and kept in [OverlapArea] order.
         */
        fun of(found: List<ConflictModule>, appListHidingOn: Boolean): ConflictUi? {
            val modules = found
                .map { module ->
                    module.copy(
                        label = module.label.ifBlank { module.packageName },
                        areas = OverlapArea.entries.filter(module.areas::contains),
                    )
                }
                .filter { it.areas.isNotEmpty() && it.packageName.isNotBlank() }
                .distinctBy { it.packageName }
                .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
            val groups = OverlapArea.entries.mapNotNull { area ->
                modules.filter { area in it.areas }.map { it.label }.distinct().takeIf { it.isNotEmpty() }
                    ?.let { ConflictGroup(area, it) }
            }
            if (groups.isEmpty()) return null
            val appList = groups.any { it.area == OverlapArea.APP_LIST }
            return ConflictUi(groups, canDisableAppList = appList && appListHidingOn, modules = modules)
        }
    }
}

@Immutable
internal data class UiState(
    val loaded: Boolean = false,
    val module: ModuleState = ModuleState.CHECKING,
    val framework: String? = null,
    val versionName: String = "",
    val byCategory: List<CategoryGroup> = emptyList(),
    val apps: List<AppSummary> = emptyList(),
    val rules: List<RuleItem> = emptyList(),
    val enabledCount: Int = 0,
    val totalHits: Long = 0,
    /** Hits reported by protected user apps, read from the status store (not from the Apps tab). */
    val protectedHits: Long = 0,
    val attention: Attention = Attention(),
    val diagnosticMode: Boolean = false,
    /** `applist.self.hide`: the module's package is hidden from third-party apps (system_server). */
    val selfHidden: Boolean = true,
    val observed: List<ObservedEndpointUi> = emptyList(),
    val suspected: List<ObservedEndpointUi> = emptyList(),
    val scope: ScopeUi = ScopeUi(),
    /** Why a restart is due, when [module] is [ModuleState.NEEDS_REBOOT]; [RebootReason.NONE] for the generic case. */
    val rebootReason: RebootReason = RebootReason.NONE,
    /** `pause.until` ([ProtectionPause]): `0` not paused, `UNTIL_MANUAL`, or the deadline in wall-clock millis. */
    val pausedUntil: Long = 0L,
    /**
     * Something protects right now besides the catalog rules counted in [enabledCount]: app-list or
     * module hiding, or a protected third-party app. Pausing is offered whenever this or a rule is on.
     */
    val otherProtectionOn: Boolean = false,
    /** User-installed apps with protection turned on, as stored. */
    val protectedApps: Int = 0,
    val conflict: ConflictUi? = null,
) {
    /**
     * A timed pause is shown, so the banner's countdown ticks. A pause until resumed shows no time
     * and so wakes nothing.
     */
    val pauseCountdown: Boolean get() = pausedUntil != 0L && pausedUntil != ProtectionPause.UNTIL_MANUAL

    /** Pausing is offered while the module can be edited and anything protects. */
    val canPause: Boolean get() = module.canEdit && (enabledCount > 0 || otherProtectionOn)

    fun rule(id: String): RuleItem? = rules.firstOrNull { it.id == id }

    fun app(packageName: String): AppSummary? = apps.firstOrNull { it.packageName == packageName }

    /** Every rule touching [packageName], with health scoped to that app. */
    fun rulesOf(packageName: String): List<RuleEntry> = rules.mapNotNull { item ->
        val scoped = item.apps.firstOrNull { it.packageName == packageName } ?: return@mapNotNull null
        RuleEntry(item, if (item.apps.size > 1) RowMeta.Apps(item.apps.size) else RowMeta.None, scoped.health)
    }
}
