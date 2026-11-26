package io.github.hyperprivacy.ui.apps

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.components.SectionHeader
import io.github.hyperprivacy.ui.components.SwitchRow
import io.github.hyperprivacy.ui.components.countString
import io.github.hyperprivacy.ui.components.groupedItems

/**
 * Suspected ad components of one app: manifest components that look like ad pages but belong to
 * no known SDK, one row per package: the simple name of its first matching component (`+N` for the
 * others) over the package. Both wrap rather than truncate. The note that these are not known SDKs
 * is shown once under the header. Off unless enabled; then code in that package is blocked by
 * origin like a known ad SDK. Hits are counted for all suspected packages together, so the count
 * is shown once, on the section header.
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
    item(key = "suspects-note") { Note(R.string.sdk_suspect_summary) }
    groupedItems(app.suspects, key = { "suspect/${it.root}" }) { suspect ->
        val first = (suspect.components.firstOrNull() ?: suspect.root.removeSuffix(".")).substringAfterLast('.')
        val more = suspect.components.size - 1
        SwitchRow(
            title = if (more > 0) "$first +$more" else first,
            summary = suspect.root.removeSuffix("."),
            checked = suspect.blocked,
            enabled = canEdit && app.protected,
            onCheckedChange = { onToggle(suspect.root, it) },
        )
    }
}
