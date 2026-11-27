package io.github.hyperprivacy.ui.home

import androidx.annotation.StringRes
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.ui.CategoryGroup
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.ModuleState
import io.github.hyperprivacy.ui.UiState
import io.github.hyperprivacy.ui.components.GroupedSurface
import io.github.hyperprivacy.ui.components.TabTopBar
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.components.listPadding
import io.github.hyperprivacy.ui.sampleState

private const val LOCKED_KEY = "locked"
private const val VIEW_KEY = "view"
private const val EMPTY_KEY = "empty"

/** Callbacks of the System tab, bundled so the signature stays readable. */
internal class SystemActions(
    val onViewChange: (SystemView) -> Unit,
    val onToggleExpanded: (String) -> Unit,
    val onToggleRule: (String, Boolean) -> Unit,
    val onSetRules: (List<String>, Boolean) -> Unit,
    val onOpenRule: (String) -> Unit,
    val onOpenApp: (String) -> Unit,
    val onOpenSearch: () -> Unit,
)

/**
 * The System tab: the built-in rules for the system apps on this device, folded per category (with
 * bulk switches), or as a list of apps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SystemScreen(
    state: UiState,
    view: SystemView,
    expanded: Set<String>,
    actions: SystemActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    barState: TopAppBarState = rememberTopAppBarState(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(barState)
    var bulk by rememberSaveable(stateSaver = BulkToggleSaver) { mutableStateOf<BulkToggle?>(null) }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TabTopBar(stringResource(R.string.system_title), scrollBehavior) {
                IconButton(onClick = actions.onOpenSearch) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.action_search))
                }
            }
        },
    ) { inner ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = listPadding(inner),
        ) {
            val settled = state.loaded && state.module != ModuleState.CHECKING
            if (settled && !state.module.canEdit) item(key = LOCKED_KEY) { Hint(R.string.system_locked) }
            item(key = VIEW_KEY, contentType = VIEW_KEY) {
                ViewSwitcher(view, actions.onViewChange, Modifier.padding(top = Dimens.Tiny))
            }
            if (settled && state.rules.isEmpty()) item(key = EMPTY_KEY) { EmptyRules() }
            when (view) {
                SystemView.CATEGORY -> categories(state, expanded, actions) { bulk = it }
                SystemView.APP -> apps(state, actions)
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

/**
 * The list position of [category]'s header in the by-type view of [state] when it is the only one
 * unfolded (`null`: the category has no rules on this device). Mirrors the items above the
 * categories in [SystemScreen]: the locked hint, the view switcher and the empty hint.
 */
internal fun categoryItemIndex(state: UiState, category: Category): Int? {
    val position = state.byCategory.indexOfFirst { it.category == category }.takeIf { it >= 0 } ?: return null
    val settled = state.loaded && state.module != ModuleState.CHECKING
    val above = listOf(settled && !state.module.canEdit, true, settled && state.rules.isEmpty()).count { it }
    return above + position
}

private fun LazyListScope.categories(
    state: UiState,
    expanded: Set<String>,
    actions: SystemActions,
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

private fun LazyListScope.categoryRows(group: CategoryGroup, canEdit: Boolean, actions: SystemActions) {
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

private fun LazyListScope.apps(state: UiState, actions: SystemActions) {
    item(key = "apps-gap") { Spacer(Modifier.height(Dimens.GroupGap)) }
    groupedItems(state.apps, key = { "app/${it.packageName}" }) { app ->
        AppSummaryRow(app, onOpen = { actions.onOpenApp(app.packageName) })
    }
}

/** A quiet one-line note above the list. */
@Composable
private fun Hint(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = Dimens.Small),
    )
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

private val previewActions = SystemActions({}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {})

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun SystemScreenPreview() {
    HyperPrivacyTheme {
        val state = sampleState()
        SystemScreen(state, SystemView.CATEGORY, state.byCategory.map { it.key }.toSet(), previewActions)
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun SystemScreenByAppPreview() {
    HyperPrivacyTheme {
        SystemScreen(sampleState(), SystemView.APP, emptySet(), previewActions)
    }
}
