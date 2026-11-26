package io.github.hyperprivacy.ui.apps

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.components.SectionHeader
import io.github.hyperprivacy.ui.components.SwitchRow
import io.github.hyperprivacy.ui.components.countString
import io.github.hyperprivacy.ui.components.groupedItems

/**
 * Suspected ad components of one app: manifest components that look like ad pages but belong to
 * no known SDK, one row per package with the matching component names. Off unless enabled; then
 * code in that package is blocked by origin like a known ad SDK.
 */
internal fun LazyListScope.suspectSection(app: UserAppUi, canEdit: Boolean, onToggle: (String, Boolean) -> Unit) {
    if (app.suspects.isEmpty()) return
    item(key = "suspects-title") { SectionHeader(stringResource(R.string.sdk_suspect_title)) }
    groupedItems(app.suspects, key = { "suspect/${it.root}" }) { suspect ->
        val separator = stringResource(R.string.common_separator)
        val details = buildList {
            add(suspect.root.removeSuffix("."))
            if (suspect.blocked && app.suspectHits > 0) add(countString(R.plurals.rule_hits, app.suspectHits))
        }.joinToString(separator)
        SwitchRow(
            title = suspect.components.joinToString(separator),
            summary = details + "\n" + stringResource(R.string.sdk_suspect_summary),
            checked = suspect.blocked,
            enabled = canEdit && app.protected,
            onCheckedChange = { onToggle(suspect.root, it) },
        )
    }
}
