package io.github.hyperprivacy.ui.apps

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.tracker.TrackerCategory
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.components.SectionHeader
import io.github.hyperprivacy.ui.components.SwitchRow
import io.github.hyperprivacy.ui.components.TitleSummary
import io.github.hyperprivacy.ui.components.countString
import io.github.hyperprivacy.ui.components.groupedItems

/*
 * The tracker SDK section of an app's page: one short note when the list alone would not explain
 * itself, then the detected SDKs under a caption per category, each with its switch.
 */

/** The one note of the SDK section, when the list alone would not explain itself. */
@StringRes
private fun UserAppUi.sdkNote(): Int? = when {
    scan == ScanState.PENDING -> R.string.app_note_checking
    scan == ScanState.FAILED -> R.string.app_note_failed
    sdks.isEmpty() -> R.string.app_note_no_sdk
    detectionOnly -> R.string.app_note_detect_only
    else -> null
}

@Composable
internal fun Note(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = Dimens.Small),
    )
}

internal fun LazyListScope.sdkSection(app: UserAppUi, canEdit: Boolean, onSdk: (String, Boolean) -> Unit) {
    item(key = "sdk-title") { SectionHeader(stringResource(R.string.app_section_sdk)) }
    app.sdkNote()?.let { note -> item(key = "sdk-note") { Note(note) } }
    // Category order follows the enum; within a category, blockable SDKs first, then by name.
    app.sdks.groupBy { it.category }.toSortedMap().forEach { (category, sdks) ->
        item(key = "cat/${category.name}") { Caption(stringResource(category.label())) }
        val ordered = sdks.sortedWith(compareBy<SdkUi> { !it.blockable }.thenBy { it.name.en.lowercase() })
        groupedItems(ordered, key = { "sdk/${it.id}" }) { sdk ->
            SdkRow(sdk, enabled = canEdit && app.protected, onToggle = { onSdk(sdk.id, it) })
        }
    }
}

/** A category label inside a section, quieter than the section header. */
@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = Dimens.Gutter, end = Dimens.Gutter, top = Dimens.Small, bottom = Dimens.Tiny)
            .semantics { heading() },
    )
}

/**
 * One detected SDK. The subtitle is the hit count, then the impact note of an SDK that is off by
 * default; the vendor is not repeated (the title already names it). A detection-only SDK has no
 * switch, just a trailing label.
 */
@Composable
private fun SdkRow(sdk: SdkUi, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    val summary = listOfNotNull(
        if (sdk.hits > 0) countString(R.plurals.rule_hits, sdk.hits) else null,
        if (sdk.blockable && !sdk.defaultBlock) stringResource(sdk.impactNote()) else null,
    ).joinToString("\n").ifEmpty { null }
    val title = sdk.name.resolve(language)
    if (sdk.blockable) {
        SwitchRow(
            title = title,
            summary = summary,
            checked = sdk.blocked,
            enabled = enabled,
            onCheckedChange = onToggle,
        )
    } else {
        DetectOnlyRow(title, summary)
    }
}

@Composable
private fun DetectOnlyRow(title: String, summary: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowMinHeight)
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TitleSummary(title, summary, Modifier.weight(1f))
        Text(
            text = stringResource(R.string.sdk_detect_only),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@StringRes
private fun SdkUi.impactNote(): Int = when (category) {
    TrackerCategory.IDENTIFICATION -> R.string.sdk_impact_verification
    TrackerCategory.CRASH -> R.string.sdk_impact_updates
    else -> R.string.sdk_impact_features
}

@StringRes
internal fun TrackerCategory.label(): Int = when (this) {
    TrackerCategory.ADS -> R.string.tracker_cat_ads
    TrackerCategory.ANALYTICS -> R.string.tracker_cat_analytics
    TrackerCategory.CRASH -> R.string.tracker_cat_crash
    TrackerCategory.IDENTIFICATION -> R.string.tracker_cat_identification
    TrackerCategory.PROFILING -> R.string.tracker_cat_profiling
    TrackerCategory.LOCATION -> R.string.tracker_cat_location
    TrackerCategory.PUSH -> R.string.tracker_cat_push
    TrackerCategory.ATTRIBUTION -> R.string.tracker_cat_attribution
}
