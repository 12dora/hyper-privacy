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
import io.github.hyperprivacy.ui.Attention
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.ModuleState
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

/** The numbers when working, otherwise the one thing the user should do. */
@Composable
private fun statusDetail(module: ModuleState, enabledCount: Int, totalHits: Long): String? = when (module) {
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

    ModuleState.NEEDS_REBOOT -> stringResource(R.string.module_reboot_hint)

    ModuleState.INACTIVE -> stringResource(R.string.module_inactive_hint)

    ModuleState.UNAVAILABLE -> stringResource(R.string.module_unavailable_hint)

    ModuleState.CHECKING -> null
}

/**
 * Home's first card: module state, and directly beneath it (same card) the attention entry when
 * any rule needs a look. Only the state title is a live region, so TalkBack announces state
 * changes but not every change of the blocked count.
 */
@Composable
internal fun StatusCard(
    module: ModuleState,
    enabledCount: Int,
    totalHits: Long,
    attention: Attention,
    onOpenHealth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showAttention = !attention.isEmpty && module.canEdit
    val rows = if (showAttention) 2 else 1
    Column(modifier) {
        GroupedSurface(index = 0, count = rows) { StatusRow(module, enabledCount, totalHits) }
        if (showAttention) {
            GroupedSurface(index = 1, count = rows) { AttentionRow(attention, onOpenHealth) }
        }
    }
}

@Composable
private fun StatusRow(module: ModuleState, enabledCount: Int, totalHits: Long) {
    val style = badgeStyle(module)
    val detail = statusDetail(module, enabledCount, totalHits)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.TallRowMinHeight)
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical + Dimens.Tiny)
            .semantics(mergeDescendants = true) {},
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

@Preview(showBackground = true)
@Composable
private fun StatusCardPreview() {
    HyperPrivacyTheme {
        val state = sampleState()
        StatusCard(state.module, state.enabledCount, state.totalHits, state.attention, onOpenHealth = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusCardInactivePreview() {
    HyperPrivacyTheme { StatusCard(ModuleState.INACTIVE, 0, 0, Attention(), onOpenHealth = {}) }
}
