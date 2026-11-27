package io.github.hyperprivacy.ui.apps

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.tracker.AppDefaults
import io.github.hyperprivacy.core.tracker.TrackerCategory
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.LocalLanguageTag
import io.github.hyperprivacy.ui.components.BackButton
import io.github.hyperprivacy.ui.components.SectionHeader
import io.github.hyperprivacy.ui.components.SwitchRow
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.components.listPadding

/**
 * What a newly protected app starts with ([AppDefaults]), laid out like an app's own page: tracker
 * SDKs by category, privacy hardening, app-list hiding. Turning protection on for an app applies
 * these once; each app can still be changed afterwards. One note explains that, one more that SDKs
 * which may affect sign-in or payment stay off whatever their category says.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppDefaultsScreen(
    defaults: AppDefaults,
    canEdit: Boolean,
    actions: AppDefaultsActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.apps_defaults)) },
                navigationIcon = { BackButton(actions.onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding(inner)) {
            item(key = "note") { Note(R.string.apps_defaults_note) }
            sdkDefaults(defaults, canEdit, actions.onSdk)
            ruleDefaults(defaults, canEdit, actions.onRule)
            appListDefaults(defaults, canEdit, actions)
        }
    }
}

private fun LazyListScope.sdkDefaults(
    defaults: AppDefaults,
    canEdit: Boolean,
    onSdk: (TrackerCategory, Boolean) -> Unit,
) {
    item(key = "sdk-title") { SectionHeader(stringResource(R.string.app_section_sdk)) }
    groupedItems(AppDefaults.categories, key = { "sdk/${it.name}" }) { category ->
        SwitchRow(
            title = stringResource(category.label()),
            checked = defaults.isCategoryBlocked(category),
            enabled = canEdit,
            onCheckedChange = { onSdk(category, it) },
        )
    }
    item(key = "sdk-note") { Note(R.string.apps_defaults_sdk_note) }
}

private fun LazyListScope.ruleDefaults(defaults: AppDefaults, canEdit: Boolean, onRule: (String, Boolean) -> Unit) {
    item(key = "rules-title") { SectionHeader(stringResource(R.string.app_section_hardening)) }
    groupedItems(Catalog.protectedAppRules, key = { "rule/${it.id}" }) { rule ->
        val language = LocalLanguageTag.current
        SwitchRow(
            title = rule.title.resolve(language),
            summary = rule.summary.resolve(language),
            checked = defaults.isRuleOn(rule.id),
            enabled = canEdit,
            onCheckedChange = { onRule(rule.id, it) },
        )
    }
}

/** One app-list row of the defaults: its copy, its value and where a change goes. */
private class ListDefault(
    @StringRes val title: Int,
    @StringRes val summary: Int,
    val checked: Boolean,
    val onChange: (Boolean) -> Unit,
)

/** Strict mode refines app-list hiding, so its row exists only while hiding is on (as on an app's page). */
private fun LazyListScope.appListDefaults(defaults: AppDefaults, canEdit: Boolean, actions: AppDefaultsActions) {
    item(key = "applist-title") { SectionHeader(stringResource(R.string.app_section_privacy)) }
    val rows = listOfNotNull(
        ListDefault(R.string.app_hide_apps, R.string.app_hide_apps_summary, defaults.hide, actions.onHideApps),
        ListDefault(R.string.app_strict, R.string.app_strict_summary, defaults.strict, actions.onStrict)
            .takeIf { defaults.hide },
    )
    groupedItems(rows, key = { "applist/${it.title}" }) { row ->
        SwitchRow(
            title = stringResource(row.title),
            summary = stringResource(row.summary),
            checked = row.checked,
            enabled = canEdit,
            onCheckedChange = row.onChange,
        )
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun AppDefaultsScreenPreview() {
    HyperPrivacyTheme {
        AppDefaultsScreen(
            defaults = AppDefaults(sdk = mapOf(TrackerCategory.ANALYTICS to false), hide = true),
            canEdit = true,
            actions = AppDefaultsActions({ _, _ -> }, { _, _ -> }, {}, {}, {}),
        )
    }
}
