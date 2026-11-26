package io.github.hyperprivacy.ui

import io.github.hyperprivacy.core.status.DisplayStatus

internal sealed interface ModuleState {
    data object Inactive : ModuleState

    data class Active(val frameworkName: String, val frameworkVersion: String, val apiVersion: Int) : ModuleState
}

internal data class RuleItem(
    val id: String,
    val title: String,
    val summary: String,
    val enabled: Boolean,
    val status: DisplayStatus,
    val hits: Long,
)

/** Rules sharing one target app; [label] is null for rules spanning several apps. */
internal data class RuleGroup(val key: String, val label: String?, val rules: List<RuleItem>)

internal data class UiState(val module: ModuleState = ModuleState.Inactive, val groups: List<RuleGroup> = emptyList())
