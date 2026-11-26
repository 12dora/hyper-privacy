package io.github.hyperprivacy.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.status.DisplayStatus
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.LocalLanguageTag
import io.github.hyperprivacy.ui.RowMeta
import io.github.hyperprivacy.ui.RuleEntry
import io.github.hyperprivacy.ui.components.appCountString
import io.github.hyperprivacy.ui.components.countString
import io.github.hyperprivacy.ui.sampleEntries

private const val DETAIL_ICON_ALPHA = 0.64f

/**
 * One rule: title, summary, optional side effect, a compact status line and a switch.
 * The whole row toggles the switch; the info button (and a TalkBack custom action) opens the details.
 */
@Composable
internal fun RuleRow(
    entry: RuleEntry,
    switchEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rule = entry.item.rule
    val tag = LocalLanguageTag.current
    val title = rule.title.resolve(tag)
    val detailsLabel = stringResource(R.string.rule_open_details)
    val detailsAction = CustomAccessibilityAction(detailsLabel) {
        onOpenDetails()
        true
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowMinHeight)
            .toggleable(
                value = entry.item.enabled,
                enabled = switchEnabled,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .semantics { customActions = listOf(detailsAction) }
            .padding(start = Dimens.Gutter, top = Dimens.Small, bottom = Dimens.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.Tiny / 2)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = rule.summary.resolve(tag),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            rule.impact?.let { impact ->
                Text(
                    text = stringResource(R.string.rule_impact, impact.resolve(tag)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusLine(entry)
        }
        DetailsButton(title, onOpenDetails)
        Switch(
            checked = entry.item.enabled,
            onCheckedChange = null,
            enabled = switchEnabled,
            modifier = Modifier.padding(end = Dimens.Gutter),
        )
    }
}

@Composable
private fun DetailsButton(title: String, onClick: () -> Unit) {
    // Hidden from TalkBack: the row exposes the same action as a custom action.
    IconButton(onClick = onClick, modifier = Modifier.size(Dimens.TouchTarget).clearAndSetSemantics { }) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = stringResource(R.string.rule_open_details_for, title),
            modifier = Modifier.size(Dimens.InfoIcon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DETAIL_ICON_ALPHA),
        )
    }
}

/** "Mail · Active" / "Blocked 12" — shown only when there is something worth saying. */
@Composable
private fun StatusLine(entry: RuleEntry) {
    val separator = stringResource(R.string.common_separator)
    val enabled = entry.item.enabled
    val meta = when (val meta = entry.meta) {
        RowMeta.None -> null
        is RowMeta.App -> meta.label
        is RowMeta.Apps -> appCountString(meta.count)
    }
    val status = if (enabled) statusText(entry.status, entry.hits) else null
    if (meta == null && status == null) return
    val colors = MaterialTheme.colorScheme
    val statusColor = if (entry.status == DisplayStatus.ACTIVE) colors.primary else colors.onSurfaceVariant
    Text(
        text = buildAnnotatedString {
            if (meta != null) append(meta)
            if (meta != null && status != null) append(separator)
            if (status != null) withStyle(SpanStyle(color = statusColor)) { append(status) }
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Blocked count wins over "active" because it implies it; a quiet rule just says it is active. */
@Composable
internal fun statusText(status: DisplayStatus, hits: Long): String = when {
    hits > 0 && status != DisplayStatus.MISSED -> countString(R.plurals.rule_hits, hits)
    status == DisplayStatus.ACTIVE -> stringResource(R.string.rule_active)
    status == DisplayStatus.MISSED -> stringResource(R.string.rule_missed)
    else -> stringResource(R.string.rule_not_run)
}

@Preview(showBackground = true)
@Composable
private fun RuleRowPreview() {
    HyperPrivacyTheme {
        Column(Modifier.padding(Dimens.Gutter)) {
            sampleEntries().forEach { RuleRow(it, switchEnabled = true, onToggle = {}, onOpenDetails = {}) }
        }
    }
}
