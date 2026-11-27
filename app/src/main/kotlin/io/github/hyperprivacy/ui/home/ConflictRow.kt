package io.github.hyperprivacy.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.conflict.OverlapArea
import io.github.hyperprivacy.ui.ConflictGroup
import io.github.hyperprivacy.ui.ConflictUi
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.components.IconBadge

/** Modules named per area; more are counted, so many modules never make the hint grow without bound. */
private const val MAX_NAMES = 3

private fun OverlapArea.labelRes(): Int = when (this) {
    OverlapArea.APP_LIST -> R.string.conflict_area_applist
    OverlapArea.ADS -> R.string.conflict_area_ads
    OverlapArea.PRIVACY -> R.string.conflict_area_privacy
}

/**
 * One line per area: "广告拦截：A、B、C 及另外 2 个" (the count is of the modules not named). Labels
 * are the modules' own, so lines may wrap.
 */
@Composable
private fun groupLine(group: ConflictGroup): String {
    val shown = group.labels.take(MAX_NAMES).joinToString(stringResource(R.string.list_separator))
    val rest = group.labels.size - MAX_NAMES
    val names = if (rest > 0) pluralStringResource(R.plurals.conflict_names_more, rest, shown, rest) else shown
    return stringResource(R.string.conflict_line, stringResource(group.area.labelRes()), names)
}

/**
 * Other installed modules also do what this one does: every one of them is named, grouped by what
 * they overlap in. The action only changes this module's settings, never the other modules. Without
 * it, "dismiss" sits at the end of the title row, so the card has no empty button band.
 */
@Composable
internal fun ConflictRow(
    conflict: ConflictUi,
    onDisableAppList: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Dimens.Gutter, end = Dimens.Small, top = Dimens.RowVertical, bottom = Dimens.Tiny),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
            verticalAlignment = Alignment.Top,
        ) {
            IconBadge(
                icon = Icons.Outlined.Warning,
                container = colors.tertiaryContainer,
                content = colors.onTertiaryContainer,
                modifier = Modifier.padding(top = Dimens.Tiny),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = if (conflict.canDisableAppList) 0.dp else Dimens.Small)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(Dimens.RowGap),
            ) {
                Text(stringResource(R.string.conflict_title), style = MaterialTheme.typography.bodyLarge)
                conflict.groups.forEach { group ->
                    Text(text = groupLine(group), style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
                }
                Text(
                    text = stringResource(R.string.conflict_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (!conflict.canDisableAppList) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.conflict_dismiss)) }
            }
        }
        if (conflict.canDisableAppList) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Small, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.conflict_dismiss)) }
                FilledTonalButton(onClick = onDisableAppList) {
                    Text(stringResource(R.string.conflict_disable_applist))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConflictRowPreview() {
    HyperPrivacyTheme {
        val conflict = ConflictUi(
            groups = listOf(
                ConflictGroup(OverlapArea.APP_LIST, listOf("Example Module")),
                ConflictGroup(OverlapArea.ADS, listOf("Ad Module", "Another", "Third", "Fourth")),
            ),
            canDisableAppList = true,
        )
        Column {
            ConflictRow(conflict, onDisableAppList = {}, onDismiss = {})
            ConflictRow(conflict.copy(canDisableAppList = false), onDisableAppList = {}, onDismiss = {})
        }
    }
}
