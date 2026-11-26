package io.github.hyperprivacy.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.components.SectionHeader
import io.github.hyperprivacy.ui.components.countString
import io.github.hyperprivacy.ui.components.groupedItems

/**
 * Suspected ad components of one app: manifest components that look like ad pages but belong to
 * no known SDK, one row per package (its first matching component, `+N` for the others, and the
 * package). Off unless enabled; then code in that package is blocked by origin like a known ad
 * SDK. Hits are counted for all suspected packages together, so the count is shown once, on the
 * section header.
 */
internal fun LazyListScope.suspectSection(app: UserAppUi, canEdit: Boolean, onToggle: (String, Boolean) -> Unit) {
    if (app.suspects.isEmpty()) return
    val showHits = app.suspectHits > 0 && app.suspects.any { it.blocked }
    item(key = "suspects-title") {
        SectionHeader(
            text = stringResource(R.string.sdk_suspect_title),
            trailing = if (showHits) {
                {
                    Text(
                        text = countString(R.plurals.rule_hits, app.suspectHits),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                null
            },
        )
    }
    groupedItems(app.suspects, key = { "suspect/${it.root}" }) { suspect ->
        val first = suspect.components.firstOrNull() ?: suspect.root.removeSuffix(".")
        val more = suspect.components.size - 1
        SuspectRow(
            title = if (more > 0) "$first +$more" else first,
            pkg = suspect.root.removeSuffix("."),
            summary = stringResource(R.string.sdk_suspect_summary),
            checked = suspect.blocked,
            enabled = canEdit && app.protected,
            onCheckedChange = { onToggle(suspect.root, it) },
        )
    }
}

/** A switch row whose class and package names stay on one line each (they have no spaces to wrap at). */
@Composable
private fun SuspectRow(
    title: String,
    pkg: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowMinHeight)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = pkg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
