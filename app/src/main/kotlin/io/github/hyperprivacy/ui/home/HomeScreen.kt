package io.github.hyperprivacy.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.config.ProtectionPause
import io.github.hyperprivacy.ui.CategoryGroup
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.ModuleState
import io.github.hyperprivacy.ui.UiState
import io.github.hyperprivacy.ui.components.AppIcons
import io.github.hyperprivacy.ui.components.GroupedSurface
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.components.listPadding
import io.github.hyperprivacy.ui.sampleState

private const val STATUS_KEY = "status"
private const val VIEW_KEY = "view"
private const val EMPTY_KEY = "empty"

/** Callbacks of the Home page, bundled so the signature stays readable. */
internal class HomeActions(
    val onViewChange: (HomeView) -> Unit,
    val onToggleExpanded: (String) -> Unit,
    val onToggleRule: (String, Boolean) -> Unit,
    val onSetRules: (List<String>, Boolean) -> Unit,
    val onOpenRule: (String) -> Unit,
    val onOpenApp: (String) -> Unit,
    val onOpenSearch: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenHealth: () -> Unit,
    val onEnableScope: () -> Unit = {},
    val onOpenApps: () -> Unit = {},
    val onPause: (ProtectionPause.Option) -> Unit = {},
    val onResume: () -> Unit = {},
    val onDisableAppListHiding: () -> Unit = {},
    val onDismissConflict: () -> Unit = {},
)

/**
 * Home, top to bottom: what protects you now (status, with an attention row only when something
 * needs a look), then the rules — folded per category, or as a list of apps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    state: UiState,
    view: HomeView,
    expanded: Set<String>,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var bulk by remember { mutableStateOf<BulkToggle?>(null) }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { HomeTopBar(scrollBehavior, actions) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = listPadding(inner),
        ) {
            item(key = STATUS_KEY, contentType = STATUS_KEY) {
                StatusCard(state, actions)
            }
            item(key = VIEW_KEY, contentType = VIEW_KEY) {
                ViewSwitcher(view, actions.onViewChange, Modifier.padding(top = Dimens.Gutter + Dimens.Tiny))
            }
            val empty = state.rules.isEmpty() && state.module != ModuleState.CHECKING && state.loaded
            if (empty) item(key = EMPTY_KEY) { EmptyRules() }
            when (view) {
                HomeView.CATEGORY -> categories(state, expanded, actions) { bulk = it }
                HomeView.APP -> apps(state, actions)
            }
        }
    }
    bulk?.let { request ->
        val group = state.byCategory.firstOrNull { it.category == request.category }
        if (group == null) {
            bulk = null
        } else {
            BulkToggleDialog(
                request = request,
                group = group,
                onConfirm = { ids ->
                    bulk = null
                    actions.onSetRules(ids, request.enable)
                },
                onDismiss = { bulk = null },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(scrollBehavior: TopAppBarScrollBehavior, actions: HomeActions) {
    LargeTopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            IconButton(onClick = actions.onOpenApps) {
                Icon(AppIcons.Apps, contentDescription = stringResource(R.string.action_apps))
            }
            IconButton(onClick = actions.onOpenSearch) {
                Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.action_search))
            }
            IconButton(onClick = actions.onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
        scrollBehavior = scrollBehavior,
    )
}

private fun LazyListScope.categories(
    state: UiState,
    expanded: Set<String>,
    actions: HomeActions,
    onBulk: (BulkToggle) -> Unit,
) {
    val canEdit = state.module.canEdit
    state.byCategory.forEach { group ->
        val open = group.key in expanded
        val count = if (open) group.entries.size + 1 else 1
        item(key = group.key, contentType = "category") {
            GroupedSurface(0, count, Modifier.padding(top = Dimens.GroupGap).animateItem()) {
                CategoryHeader(
                    group = group,
                    expanded = open,
                    canEdit = canEdit,
                    onToggleExpanded = { actions.onToggleExpanded(group.key) },
                    onBulk = onBulk,
                )
            }
        }
        if (open) categoryRows(group, canEdit, actions)
    }
}

private fun LazyListScope.categoryRows(group: CategoryGroup, canEdit: Boolean, actions: HomeActions) {
    val count = group.entries.size + 1
    items(
        count = group.entries.size,
        key = { "${group.key}/${group.entries[it].item.id}" },
        contentType = { "rule" },
    ) { index ->
        val entry = group.entries[index]
        GroupedSurface(index + 1, count, Modifier.animateItem()) {
            RuleRow(
                entry = entry,
                switchEnabled = canEdit,
                onToggle = { actions.onToggleRule(entry.item.id, it) },
                onOpenDetails = { actions.onOpenRule(entry.item.id) },
            )
        }
    }
}

private fun LazyListScope.apps(state: UiState, actions: HomeActions) {
    item(key = "apps-gap") { Spacer(Modifier.height(Dimens.GroupGap)) }
    groupedItems(state.apps, key = { "app/${it.packageName}" }) { app ->
        AppSummaryRow(app, onOpen = { actions.onOpenApp(app.packageName) })
    }
}

@Composable
private fun EmptyRules() {
    Text(
        text = stringResource(R.string.rules_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.Gutter * 2),
    )
}

private val previewActions = HomeActions({}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {})

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun HomeScreenPreview() {
    HyperPrivacyTheme {
        val state = sampleState()
        HomeScreen(state, HomeView.CATEGORY, state.byCategory.map { it.key }.toSet(), previewActions)
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun HomeScreenByAppPreview() {
    HyperPrivacyTheme {
        HomeScreen(sampleState(), HomeView.APP, emptySet(), previewActions)
    }
}
