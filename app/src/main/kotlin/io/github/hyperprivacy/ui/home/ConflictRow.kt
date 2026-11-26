package io.github.hyperprivacy.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.conflict.OverlapArea
import io.github.hyperprivacy.ui.ConflictUi
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.components.IconBadge

private fun OverlapArea.summaryRes(): Int = when (this) {
    OverlapArea.APP_LIST -> R.string.conflict_summary_applist
    OverlapArea.ADS -> R.string.conflict_summary_ads
    OverlapArea.PRIVACY -> R.string.conflict_summary_privacy
}

@Composable
private fun conflictSummary(conflict: ConflictUi): String {
    val first = stringResource(conflict.area.summaryRes(), conflict.label)
    if (conflict.others == 0) return first
    return first + stringResource(R.string.common_separator) +
        pluralStringResource(R.plurals.conflict_more, conflict.others, conflict.others)
}

/**
 * Another installed module also does what this one does. The app's own label is shown as is (it
 * may be long, so the text wraps); the action only changes this module's settings, never the other
 * module. Without an action the hint can still be dismissed.
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
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical),
        verticalArrangement = Arrangement.spacedBy(Dimens.Small),
    ) {
        Row(
            modifier = Modifier.heightIn(min = Dimens.ListIcon),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(Icons.Outlined.Warning, colors.tertiaryContainer, colors.onTertiaryContainer)
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.conflict_title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = conflictSummary(conflict),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.conflict_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.conflict_dismiss)) }
            if (conflict.canDisableAppList) {
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
        ConflictRow(
            conflict = ConflictUi("Example Module", others = 1, area = OverlapArea.APP_LIST, canDisableAppList = true),
            onDisableAppList = {},
            onDismiss = {},
        )
    }
}
