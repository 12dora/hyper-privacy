package io.github.hyperprivacy.ui.apps

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.tracker.TrackerCategory
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.components.AppIcon
import io.github.hyperprivacy.ui.components.BackButton
import io.github.hyperprivacy.ui.components.SectionHeader
import io.github.hyperprivacy.ui.components.SingleCard
import io.github.hyperprivacy.ui.components.SwitchRow
import io.github.hyperprivacy.ui.components.countString
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.components.listPadding

private val HeaderIcon = 48.dp

/**
 * One user-installed app: the protection switch, detected SDKs grouped by category with a switch
 * each (opt-in SDKs carry a one-line impact note), suspected ad components (off unless enabled),
 * the app-level protections (DRM device ID, system DNS) with a switch each, and app-list hiding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserAppScreen(
    app: UserAppUi?,
    canEdit: Boolean,
    actions: UserAppActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { BackButton(actions.onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        if (app == null) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.app_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding(inner)) {
            item(key = "header") { Header(app) }
            item(key = "protect") { ProtectionCard(app, canEdit, actions) }
            sdkSections(app, canEdit, actions.onSdk)
            suspectSection(app, canEdit, actions.onSuspect)
            appRuleSection(app, canEdit, actions.onAppRule)
            appListSection(app, canEdit, actions)
        }
    }
}

@Composable
private fun Header(app: UserAppUi) {
    val (summary, tint) = appSummary(app)
    Row(
        modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = Dimens.Small),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, HeaderIcon)
        Column {
            Text(
                text = app.label,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = tint.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant },
            )
        }
    }
}

@Composable
private fun ProtectionCard(app: UserAppUi, canEdit: Boolean, actions: UserAppActions) {
    SingleCard(Modifier.padding(top = Dimens.GroupGap)) {
        SwitchRow(
            title = stringResource(R.string.app_protection),
            summary = stringResource(R.string.app_protection_summary),
            checked = app.protected,
            enabled = canEdit && app.scan != ScanState.PENDING,
            onCheckedChange = actions.onProtect,
        )
        if (app.awaitingScope) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Dimens.Gutter, end = Dimens.Small, bottom = Dimens.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.app_scope_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions.onRetryScope, enabled = canEdit && !app.requesting) {
                    Text(stringResource(R.string.app_scope_retry))
                }
            }
        }
    }
    Note(
        when {
            app.scan == ScanState.PENDING -> R.string.app_note_checking
            app.scan == ScanState.FAILED -> R.string.app_note_failed
            app.sdks.isEmpty() && app.suspects.isNotEmpty() -> R.string.app_note_suspects_only
            app.sdks.isEmpty() -> R.string.app_note_no_sdk
            app.detectionOnly -> R.string.app_note_detect_only
            else -> R.string.app_note_protection
        },
    )
}

@Composable
private fun Note(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = Dimens.Small),
    )
}

private fun LazyListScope.sdkSections(app: UserAppUi, canEdit: Boolean, onSdk: (String, Boolean) -> Unit) {
    // Category order follows the enum; within a category, blockable SDKs first, then by name.
    app.sdks.groupBy { it.category }.toSortedMap().forEach { (category, sdks) ->
        item(key = "cat/${category.name}") { SectionHeader(stringResource(category.label())) }
        val ordered = sdks.sortedWith(compareBy<SdkUi> { !it.blockable }.thenBy { it.name.en.lowercase() })
        groupedItems(ordered, key = { "sdk/${it.id}" }) { sdk ->
            SdkRow(sdk, enabled = canEdit && app.protected, onToggle = { onSdk(sdk.id, it) })
        }
    }
}

@Composable
private fun SdkRow(sdk: SdkUi, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    val separator = stringResource(R.string.common_separator)
    val details = buildList {
        add(sdk.vendor)
        if (!sdk.blockable) add(stringResource(R.string.sdk_detect_only))
        if (sdk.hits > 0) add(countString(R.plurals.rule_hits, sdk.hits))
    }.joinToString(separator)
    val impact = if (sdk.blockable && !sdk.defaultBlock) stringResource(sdk.impactNote()) else null
    SwitchRow(
        title = sdk.name.resolve(language),
        summary = listOfNotNull(details, impact).joinToString("\n"),
        checked = sdk.blocked,
        enabled = enabled && sdk.blockable,
        onCheckedChange = onToggle,
    )
}

@StringRes
private fun SdkUi.impactNote(): Int = when (category) {
    TrackerCategory.IDENTIFICATION -> R.string.sdk_impact_verification
    TrackerCategory.CRASH -> R.string.sdk_impact_updates
    else -> R.string.sdk_impact_features
}

private fun LazyListScope.appRuleSection(app: UserAppUi, canEdit: Boolean, onToggle: (String, Boolean) -> Unit) {
    if (app.appRules.isEmpty()) return
    item(key = "rules-title") { SectionHeader(stringResource(R.string.app_protection_rules)) }
    groupedItems(app.appRules, key = { "rule/${it.id}" }) { rule ->
        val language = LocalConfiguration.current.locales[0].toLanguageTag()
        val separator = stringResource(R.string.common_separator)
        val summary = buildList {
            add(rule.summary.resolve(language))
            if (rule.hits > 0) add(countString(R.plurals.rule_hits, rule.hits))
        }.joinToString(separator)
        SwitchRow(
            title = rule.title.resolve(language),
            summary = summary,
            checked = rule.enabled,
            enabled = canEdit && app.protected,
            onCheckedChange = { onToggle(rule.id, it) },
        )
    }
}

private enum class AppListRow { HIDE, STRICT }

private fun LazyListScope.appListSection(app: UserAppUi, canEdit: Boolean, actions: UserAppActions) {
    item(key = "applist-title") { SectionHeader(stringResource(R.string.app_section_privacy)) }
    groupedItems(AppListRow.entries, key = { "applist/$it" }) { row ->
        when (row) {
            AppListRow.HIDE -> SwitchRow(
                title = stringResource(R.string.app_hide_apps),
                summary = stringResource(R.string.app_hide_apps_summary),
                checked = app.hideApps,
                enabled = canEdit,
                onCheckedChange = actions.onHideApps,
            )

            AppListRow.STRICT -> SwitchRow(
                title = stringResource(R.string.app_strict),
                summary = stringResource(R.string.app_strict_summary),
                checked = app.strict && app.hideApps,
                enabled = canEdit && app.hideApps,
                onCheckedChange = actions.onStrict,
            )
        }
    }
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

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun UserAppScreenPreview() {
    HyperPrivacyTheme {
        val base = sampleApps().apps.first()
        val app = base.copy(
            scoped = false,
            sdks = base.sdks + base.sdks.first().copy(
                id = "risk",
                category = TrackerCategory.IDENTIFICATION,
                defaultBlock = false,
                blocked = false,
                hits = 0,
            ),
        )
        val actions = UserAppActions({}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {}, {})
        UserAppScreen(app, canEdit = true, actions = actions)
    }
}
