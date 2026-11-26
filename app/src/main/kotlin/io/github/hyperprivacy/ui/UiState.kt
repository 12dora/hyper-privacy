package io.github.hyperprivacy.ui

import androidx.compose.runtime.Immutable
import io.github.hyperprivacy.core.health.Health
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

/** Health states the user should look at; the rest are fine or merely informational. */
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
    val key: String get() = "c:${category.name}"
    val enabledCount: Int = entries.count { it.item.enabled }
    val attentionCount: Int = entries.count { it.attention != null }
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
    val attention: Attention = Attention(),
    val diagnosticMode: Boolean = false,
    val observed: List<ObservedEndpointUi> = emptyList(),
    val suspected: List<ObservedEndpointUi> = emptyList(),
) {
    fun rule(id: String): RuleItem? = rules.firstOrNull { it.id == id }

    fun app(packageName: String): AppSummary? = apps.firstOrNull { it.packageName == packageName }

    /** Every rule touching [packageName], with health scoped to that app. */
    fun rulesOf(packageName: String): List<RuleEntry> = rules.mapNotNull { item ->
        val scoped = item.apps.firstOrNull { it.packageName == packageName } ?: return@mapNotNull null
        RuleEntry(item, if (item.apps.size > 1) RowMeta.Apps(item.apps.size) else RowMeta.None, scoped.health)
    }
}
