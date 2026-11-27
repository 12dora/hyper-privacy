package io.github.hyperprivacy.ui.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.tracker.TrackerCategory
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.components.AppIcon
import io.github.hyperprivacy.ui.components.AppIcons
import io.github.hyperprivacy.ui.components.BackButton
import io.github.hyperprivacy.ui.components.IconBadge
import io.github.hyperprivacy.ui.components.NavRow
import io.github.hyperprivacy.ui.components.SearchField
import io.github.hyperprivacy.ui.components.SingleCard
import io.github.hyperprivacy.ui.components.TabTopBar
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.components.listPadding

/**
 * The Apps tab: user-installed apps outside the catalog: icon, name, one line on what the offline scan found,
 * and the protection switch. The list fills in while apps are checked; [onlyTrackers] narrows it to
 * apps with at least one detected SDK, and [search] to apps whose label or package matches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppsScreen(
    state: AppsUi,
    canEdit: Boolean,
    onlyTrackers: Boolean,
    search: AppsSearch,
    actions: AppsActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    barState: TopAppBarState = rememberTopAppBarState(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(barState)
    val shown = remember(state.apps, onlyTrackers, search) {
        state.apps.filter { (!onlyTrackers || it.sdkCount > 0) && search.matches(it) }
    }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (search.active) {
                AppsSearchBar(search.query, actions.onQueryChange) {
                    actions.onQueryChange("")
                    actions.onSearchChange(false)
                }
            } else {
                TabTopBar(stringResource(R.string.apps_title), scrollBehavior) {
                    IconButton(onClick = { actions.onSearchChange(true) }) {
                        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.action_search))
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            state = listState,
            contentPadding = listPadding(inner),
        ) {
            if (!search.active) {
                item(key = "intro") { Intro() }
                item(key = "defaults") { DefaultsEntry(actions.onOpenDefaults) }
            }
            item(key = "filter") { FilterRow(state, onlyTrackers, actions.onFilterChange) }
            when {
                !state.loaded -> item(key = "loading") { Loading() }

                shown.isEmpty() && !state.scanning -> item(key = "empty") {
                    val empty = when {
                        search.filtering -> R.string.apps_search_empty
                        onlyTrackers -> R.string.apps_empty_filtered
                        else -> R.string.apps_empty
                    }
                    EmptyText(stringResource(empty))
                }

                else -> groupedItems(shown, key = { "app/${it.packageName}" }) { app ->
                    UserAppRow(
                        app = app,
                        canEdit = canEdit,
                        onToggle = { actions.onToggle(app.packageName, it) },
                        onOpen = { actions.onOpen(app.packageName) },
                    )
                }
            }
        }
    }
}

/** The title bar while searching: back closes the search, the field filters by label or package. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsSearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    TopAppBar(
        navigationIcon = { BackButton(onClose) },
        title = { SearchField(query, onQueryChange, focus, R.string.apps_search_hint) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun Intro() {
    Text(
        text = stringResource(R.string.apps_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = Dimens.Small),
    )
}

/**
 * The way to the default settings: one row at the top of the list, where the switches they feed
 * are, rather than an unlabeled icon in the title bar or a page deep in Settings.
 */
@Composable
private fun DefaultsEntry(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    SingleCard(Modifier.padding(bottom = Dimens.Small)) {
        NavRow(
            title = stringResource(R.string.apps_defaults),
            summary = stringResource(R.string.apps_defaults_summary),
            onClick = onClick,
            leading = { IconBadge(AppIcons.Tune, colors.secondaryContainer, colors.onSecondaryContainer) },
        )
    }
}

@Composable
private fun FilterRow(state: AppsUi, onlyTrackers: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Dimens.Small),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = onlyTrackers,
            onClick = { onChange(!onlyTrackers) },
            label = { Text(stringResource(R.string.apps_filter_trackers)) },
            leadingIcon = if (onlyTrackers) {
                { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
            } else {
                null
            },
        )
        if (state.loaded && state.scanning) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(Dimens.StatusIcon), strokeWidth = Dimens.RowGap)
                Text(
                    text = stringResource(R.string.apps_scanning, state.scanned, state.total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The one secondary line of an app row; [Color.Unspecified] keeps the default tint. */
@Composable
internal fun appSummary(app: UserAppUi): Pair<String, Color> {
    val separator = stringResource(R.string.common_separator)
    val found = when (app.scan) {
        ScanState.PENDING -> stringResource(R.string.apps_scan_pending)

        ScanState.FAILED -> stringResource(R.string.apps_scan_failed)

        ScanState.DONE -> if (app.sdkCount > 0) {
            pluralStringResource(R.plurals.apps_sdk_count, app.sdkCount, app.sdkCount)
        } else {
            stringResource(R.string.apps_no_sdk)
        }
    }
    return when {
        app.awaitingScope -> stringResource(R.string.apps_awaiting_scope) to MaterialTheme.colorScheme.tertiary
        app.protected -> stringResource(R.string.apps_protected) + separator + found to Color.Unspecified
        else -> found to Color.Unspecified
    }
}

@Composable
private fun UserAppRow(app: UserAppUi, canEdit: Boolean, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    val (summary, tint) = appSummary(app)
    val toggleDescription = stringResource(R.string.apps_protect_description, app.label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.TallRowMinHeight)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, Dimens.ListIcon)
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = tint.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant },
            )
        }
        Switch(
            checked = app.protected,
            onCheckedChange = onToggle,
            enabled = canEdit && app.scan != ScanState.PENDING,
            modifier = Modifier.semantics { contentDescription = toggleDescription },
        )
    }
}

@Composable
private fun Loading() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.Gutter * 2),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun EmptyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.Gutter * 2),
    )
}

internal fun sampleApps(): AppsUi {
    val sdk = SdkUi(
        id = "umeng-analytics",
        name = Text("友盟统计", "Umeng Analytics"),
        category = TrackerCategory.ANALYTICS,
        blockable = true,
        defaultBlock = true,
        blocked = true,
        hits = 12,
    )
    val rule = AppRuleUi("mediadrm.device-id", Text("隔离 DRM 设备号", "Isolate DRM device ID"), Text("", ""), true, 0)
    fun app(pkg: String, label: String, scan: ScanState, sdks: List<SdkUi>, protected: Boolean, scoped: Boolean) =
        UserAppUi(
            pkg, label, scan, sdks, listOf(rule), protected, scoped,
            requesting = false, hideApps = false, strict = false,
        )
    return AppsUi(
        loaded = true,
        apps = listOf(
            app("com.example.reader", "阅读", ScanState.DONE, listOf(sdk, sdk.copy(id = "x")), true, true),
            app("com.example.notes", "便签", ScanState.DONE, emptyList(), false, false),
            app("com.example.shop", "购物", ScanState.DONE, listOf(sdk), true, false),
            app("com.example.maps", "地图", ScanState.PENDING, emptyList(), false, false),
        ),
        scanned = 3,
        total = 4,
    )
}

@Preview(showBackground = true, heightDp = 640)
@Composable
private fun AppsScreenPreview() {
    HyperPrivacyTheme {
        AppsScreen(
            sampleApps(),
            canEdit = true,
            onlyTrackers = false,
            search = AppsSearch(),
            actions = AppsActions({}, {}, {}, { _, _ -> }, {}),
        )
    }
}
