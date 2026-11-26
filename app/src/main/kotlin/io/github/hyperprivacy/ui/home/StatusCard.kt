package io.github.hyperprivacy.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.config.ProtectionPause
import io.github.hyperprivacy.core.health.RebootReason
import io.github.hyperprivacy.ui.Attention
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.ModuleState
import io.github.hyperprivacy.ui.ScopeUi
import io.github.hyperprivacy.ui.UiState
import io.github.hyperprivacy.ui.components.AppIcons
import io.github.hyperprivacy.ui.components.GroupedSurface
import io.github.hyperprivacy.ui.components.IconBadge
import io.github.hyperprivacy.ui.components.NavRow
import io.github.hyperprivacy.ui.components.countString
import io.github.hyperprivacy.ui.sampleState

private class BadgeStyle(val icon: ImageVector, val container: Color, val content: Color)

@Composable
private fun badgeStyle(module: ModuleState): BadgeStyle {
    val colors = MaterialTheme.colorScheme
    return when (module) {
        ModuleState.ACTIVE -> BadgeStyle(AppIcons.Shield, colors.primaryContainer, colors.onPrimaryContainer)

        ModuleState.CHECKING -> BadgeStyle(AppIcons.Shield, colors.surfaceContainerHighest, colors.onSurfaceVariant)

        ModuleState.NEEDS_REBOOT, ModuleState.INACTIVE, ModuleState.UNAVAILABLE ->
            BadgeStyle(AppIcons.ShieldAlert, colors.errorContainer, colors.onErrorContainer)
    }
}

private fun ModuleState.titleRes(): Int = when (this) {
    ModuleState.ACTIVE -> R.string.module_active
    ModuleState.CHECKING -> R.string.module_checking
    ModuleState.NEEDS_REBOOT -> R.string.module_reboot
    ModuleState.INACTIVE -> R.string.module_inactive
    ModuleState.UNAVAILABLE -> R.string.module_unavailable
}

private fun RebootReason.hintRes(): Int = when (this) {
    RebootReason.MODULE_UPDATED -> R.string.module_reboot_updated_hint
    RebootReason.GUARDS_INACTIVE -> R.string.module_reboot_guards_hint
    RebootReason.SCOPE_ADDED, RebootReason.NONE -> R.string.module_reboot_hint
}

/** The numbers when working, otherwise the one thing the user should do. */
@Composable
private fun statusDetail(module: ModuleState, enabledCount: Int, totalHits: Long, reboot: RebootReason): String? =
    when (module) {
        ModuleState.ACTIVE -> if (enabledCount == 0) {
            stringResource(R.string.module_no_rules)
        } else if (totalHits == 0L) {
            // "0 blocked" reads like a failure; right after install or reboot it is simply too early.
            countString(R.plurals.summary_enabled, enabledCount.toLong())
        } else {
            countString(R.plurals.summary_enabled, enabledCount.toLong()) +
                stringResource(R.string.common_separator) +
                countString(R.plurals.summary_blocked, totalHits)
        }

        ModuleState.NEEDS_REBOOT -> stringResource(reboot.hintRes())

        ModuleState.INACTIVE -> stringResource(R.string.module_inactive_hint)

        ModuleState.UNAVAILABLE -> stringResource(R.string.module_unavailable_hint)

        ModuleState.CHECKING -> null
    }

/**
 * Home's first card: module state (or, while protection is paused, the paused banner with the way
 * back), and directly beneath it (same card) a row for installed apps missing from the LSPosed
 * scope, the hint about an overlapping module and the attention entry when any rule needs a look.
 * Only the state title is a live region, so TalkBack announces state changes but not every change
 * of the blocked count.
 */
@Composable
internal fun StatusCard(state: UiState, actions: HomeActions, modifier: Modifier = Modifier) {
    val module = state.module
    val now = rememberNow(active = state.pausedUntil != 0L)
    val paused = module.canEdit && ProtectionPause.isPaused(state.pausedUntil, now)
    var choosing by remember { mutableStateOf(false) }
    val rows = buildList {
        add(CardRow.STATUS)
        if (!state.scope.isEmpty) add(CardRow.SCOPE)
        if (state.conflict != null && module.canEdit) add(CardRow.CONFLICT)
        if (!state.attention.isEmpty && module.canEdit) add(CardRow.ATTENTION)
    }
    Column(modifier) {
        rows.forEachIndexed { index, row ->
            GroupedSurface(index = index, count = rows.size) {
                when (row) {
                    CardRow.STATUS -> if (paused) {
                        PausedRow(state.pausedUntil, now, onResume = actions.onResume)
                    } else {
                        StatusRow(state, onPause = { choosing = true })
                    }

                    CardRow.SCOPE -> ScopeRow(state.scope, actions.onEnableScope)

                    CardRow.CONFLICT -> state.conflict?.let {
                        ConflictRow(it, actions.onDisableAppListHiding, actions.onDismissConflict)
                    }

                    CardRow.ATTENTION -> AttentionRow(state.attention, actions.onOpenHealth)
                }
            }
        }
    }
    if (choosing) {
        PauseDialog(
            onChoose = {
                choosing = false
                actions.onPause(it)
            },
            onDismiss = { choosing = false },
        )
    }
}

private enum class CardRow { STATUS, SCOPE, CONFLICT, ATTENTION }

@Composable
private fun StatusRow(state: UiState, onPause: () -> Unit) {
    val module = state.module
    val style = badgeStyle(module)
    val detail = statusDetail(module, state.enabledCount, state.totalHits, state.rebootReason)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.TallRowMinHeight)
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical + Dimens.Tiny),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(style.icon, style.container, style.content)
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(module.titleRes()),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (module.canEdit && state.enabledCount > 0) PauseButton(onPause)
    }
}

@Composable
private fun AttentionRow(attention: Attention, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val separator = stringResource(R.string.common_separator)
    val title = if (attention.rules > 0) {
        pluralStringResource(R.plurals.attention_rules, attention.rules, attention.rules)
    } else {
        pluralStringResource(R.plurals.attention_suspected, attention.suspected, attention.suspected)
    }
    val parts = if (attention.rules > 0) {
        listOf(
            R.plurals.attention_broken to attention.broken,
            R.plurals.attention_review to attention.review,
            R.plurals.attention_idle to attention.idle,
            R.plurals.attention_suspected_short to attention.suspected,
        ).filter { it.second > 0 }.map { (id, count) -> pluralStringResource(id, count, count) }
    } else {
        listOf(stringResource(R.string.attention_suspected_hint))
    }
    val urgent = attention.broken > 0
    NavRow(
        title = title,
        summary = parts.joinToString(separator),
        onClick = onClick,
        leading = {
            IconBadge(
                icon = Icons.Outlined.Warning,
                container = if (urgent) colors.errorContainer else colors.tertiaryContainer,
                content = if (urgent) colors.onErrorContainer else colors.onTertiaryContainer,
            )
        },
    )
}

private val previewActions = HomeActions({}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {})

@Preview(showBackground = true)
@Composable
private fun StatusCardPreview() {
    HyperPrivacyTheme { StatusCard(sampleState(), previewActions) }
}

@Preview(showBackground = true)
@Composable
private fun StatusCardScopePreview() {
    HyperPrivacyTheme {
        StatusCard(sampleState().copy(scope = ScopeUi(missingApps = 3, systemMissing = true)), previewActions)
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusCardPausedPreview() {
    HyperPrivacyTheme {
        val until = System.currentTimeMillis() + PREVIEW_PAUSE_MS
        StatusCard(sampleState().copy(pausedUntil = until), previewActions)
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusCardInactivePreview() {
    HyperPrivacyTheme { StatusCard(UiState(module = ModuleState.INACTIVE), previewActions) }
}

private const val PREVIEW_PAUSE_MS = 12 * 60_000L
