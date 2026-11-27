package io.github.hyperprivacy.ui.home

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.conflict.LabelGap
import io.github.hyperprivacy.core.conflict.OverlapArea
import io.github.hyperprivacy.ui.ConflictGroup
import io.github.hyperprivacy.ui.ConflictUi
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.LocalLanguageTag
import io.github.hyperprivacy.ui.components.IconBadge

/** Modules named per area; more are counted, so many modules never make the hint grow without bound. */
private const val MAX_NAMES = 3

internal fun OverlapArea.labelRes(): Int = when (this) {
    OverlapArea.APP_LIST -> R.string.conflict_area_applist
    OverlapArea.ADS -> R.string.conflict_area_ads
    OverlapArea.PRIVACY -> R.string.conflict_area_privacy
    OverlapArea.DEVICE_ID -> R.string.conflict_area_device
}

/**
 * One line per area: "广告拦截：A、B、C 及另外 2 个" (the count is of the modules not named). Labels
 * are the modules' own, so lines may wrap.
 */
@Composable
private fun groupLine(group: ConflictGroup): String {
    val shown = group.labels.take(MAX_NAMES).joinToString(stringResource(R.string.list_separator))
    val rest = group.labels.size - MAX_NAMES
    // Chinese runs on from the names without a space, unless the last name ends in a Latin letter or digit.
    val lead = if (LocalLanguageTag.current.startsWith("zh")) LabelGap.beforeCjk(shown) else shown
    val names = if (rest > 0) pluralStringResource(R.plurals.conflict_names_more, rest, lead, rest) else shown
    return stringResource(R.string.conflict_line, stringResource(group.area.labelRes()), names)
}

/**
 * Other installed modules also do what this one does: every one of them is named, grouped by what
 * they overlap in. The card opens the detail page ([onOpen]: what each module overlaps in, the
 * likely symptom and how to resolve it); "dismiss" hides it for these modules.
 */
@Composable
internal fun ConflictRow(
    conflict: ConflictUi,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val details = stringResource(R.string.conflict_details)
    val openLabel = stringResource(R.string.rule_open_details)
    Column(
        modifier = modifier
            .fillMaxWidth()
            // The whole card opens the details and is one TalkBack stop that reads the hint.
            .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
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
                    .padding(end = Dimens.Small),
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
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Small, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.conflict_dismiss)) }
            // Same action as the card itself, which TalkBack already offers: no second stop for it.
            FilledTonalButton(onClick = onOpen, modifier = Modifier.semantics { hideFromAccessibility() }) {
                Text(details)
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
        ConflictRow(conflict, onOpen = {}, onDismiss = {})
    }
}
