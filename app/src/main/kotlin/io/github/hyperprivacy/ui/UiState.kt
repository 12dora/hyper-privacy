package io.github.hyperprivacy.ui

import androidx.compose.runtime.Immutable
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

/** Status of one rule in one target app. */
@Immutable
internal data class AppStatus(val packageName: String, val label: String, val status: DisplayStatus, val hits: Long)

/** A catalog rule together with its live state. [apps] only lists apps installed on this device. */
@Immutable
internal data class RuleItem(
    val rule: Rule,
    val enabled: Boolean,
    val status: DisplayStatus,
    val hits: Long,
    val apps: List<AppStatus>,
) {
    val id: String get() = rule.id
}

/** The part of a rule row that says which app(s) it belongs to. */
internal sealed interface RowMeta {
    data object None : RowMeta

    data class App(val label: String) : RowMeta

    data class Apps(val count: Int) : RowMeta
}

/** One rule as displayed inside one group; status and hits are scoped to that group's app when it has one. */
@Immutable
internal data class RuleEntry(val item: RuleItem, val status: DisplayStatus, val hits: Long, val meta: RowMeta)

internal sealed interface GroupHeader {
    data class OfCategory(val category: Category) : GroupHeader

    data class OfApp(val packageName: String, val label: String) : GroupHeader

    /** Rules that apply to many apps. */
    data object Common : GroupHeader
}

@Immutable
internal data class RuleGroup(val key: String, val header: GroupHeader, val entries: List<RuleEntry>)

@Immutable
internal data class UiState(
    val loaded: Boolean = false,
    val module: ModuleState = ModuleState.CHECKING,
    val framework: String? = null,
    val versionName: String = "",
    val byCategory: List<RuleGroup> = emptyList(),
    val byApp: List<RuleGroup> = emptyList(),
    val rules: List<RuleItem> = emptyList(),
    val enabledCount: Int = 0,
    val totalHits: Long = 0,
    val diagnosticMode: Boolean = false,
    val observed: List<ObservedEndpointUi> = emptyList(),
) {
    fun rule(id: String): RuleItem? = rules.firstOrNull { it.id == id }
}
