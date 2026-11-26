package io.github.hyperprivacy.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import java.text.NumberFormat

/**
 * One rule, 56dp minimum: tapping the text opens the details, the switch toggles the rule.
 * Between them sits at most one small label: the blocked count, or "not effective" when a hook failed.
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
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = Dimens.RowMinHeight)
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.rule_open_details),
                    onClick = onOpenDetails,
                )
                .padding(start = Dimens.Gutter, top = Dimens.RowVertical, bottom = Dimens.RowVertical),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = summaryLine(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Copy is length-gated in CatalogTest to fit one line; two lines are only a safety net.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.item.enabled) StatusLabel(entry.status, entry.hits, Modifier.padding(start = Dimens.Small))
        Switch(
            checked = entry.item.enabled,
            onCheckedChange = onToggle,
            enabled = switchEnabled,
            modifier = Modifier
                .padding(horizontal = Dimens.Small + Dimens.Tiny)
                .semantics { contentDescription = title },
        )
    }
}

/** App scope, then the side effect when there is one, otherwise what the rule does. */
@Composable
private fun summaryLine(entry: RuleEntry): String {
    val tag = LocalLanguageTag.current
    val rule = entry.item.rule
    // A rule titled after its only app (e.g. a whole-app firewall) needs no app prefix.
    val meta = when (val meta = entry.meta) {
        RowMeta.None -> null
        is RowMeta.App -> meta.label.takeUnless { it == rule.title.resolve(tag) }
        is RowMeta.Apps -> appCountString(meta.count)
    }
    val text = rule.impact?.let { stringResource(R.string.rule_impact, it.resolve(tag)) } ?: rule.summary.resolve(tag)
    return listOfNotNull(meta, text).joinToString(stringResource(R.string.common_separator))
}

/** Blocked count, or a quiet error tint for a hook that could not be installed. Nothing otherwise. */
@Composable
private fun StatusLabel(status: DisplayStatus, hits: Long, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    when {
        status == DisplayStatus.MISSED -> Text(
            text = stringResource(R.string.rule_missed),
            style = MaterialTheme.typography.labelSmall,
            color = colors.error,
            modifier = modifier,
        )

        hits > 0 -> {
            val description = countString(R.plurals.rule_hits, hits)
            Text(
                text = NumberFormat.getIntegerInstance().format(hits),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = modifier.semantics { contentDescription = description },
            )
        }
    }
}

/** Long-form status for the detail page. */
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
