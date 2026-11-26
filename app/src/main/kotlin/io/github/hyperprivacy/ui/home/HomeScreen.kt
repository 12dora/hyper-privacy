package io.github.hyperprivacy.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.GroupHeader
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.ModuleState
import io.github.hyperprivacy.ui.RuleGroup
import io.github.hyperprivacy.ui.UiState
import io.github.hyperprivacy.ui.components.AppIcon
import io.github.hyperprivacy.ui.components.SectionHeader
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.components.labelRes
import io.github.hyperprivacy.ui.components.listPadding
import io.github.hyperprivacy.ui.sampleState

private const val HERO_KEY = "hero"
private const val VIEW_KEY = "view"
private const val EMPTY_KEY = "empty"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    state: UiState,
    view: HomeView,
    onViewChange: (HomeView) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onOpenRule: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { inner ->
        // Switching the view replaces every group; start the new list at its first group.
        LaunchedEffect(view) {
            if (listState.firstVisibleItemIndex > 1) listState.scrollToItem(1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = listPadding(inner),
        ) {
            item(key = HERO_KEY, contentType = HERO_KEY) {
                StatusHero(state.module, state.enabledCount, state.totalHits, Modifier.padding(bottom = Dimens.Gutter))
            }
            item(key = VIEW_KEY, contentType = VIEW_KEY) {
                ViewSwitcher(view, onViewChange)
            }
            val groups = if (view == HomeView.CATEGORY) state.byCategory else state.byApp
            if (groups.isEmpty() && state.module != ModuleState.CHECKING) {
                item(key = EMPTY_KEY) { EmptyRules() }
            }
            ruleGroups(groups, state.module.canEdit, onToggleRule, onOpenRule)
        }
    }
}

private fun LazyListScope.ruleGroups(
    groups: List<RuleGroup>,
    canEdit: Boolean,
    onToggleRule: (String, Boolean) -> Unit,
    onOpenRule: (String) -> Unit,
) {
    groups.forEach { group ->
        item(key = "header/${group.key}", contentType = "header") {
            GroupTitle(group.header, Modifier.animateItem())
        }
        groupedItems(group.entries, key = { "${group.key}/${it.item.id}" }) { entry ->
            RuleRow(
                entry = entry,
                switchEnabled = canEdit,
                onToggle = { onToggleRule(entry.item.id, it) },
                onOpenDetails = { onOpenRule(entry.item.id) },
            )
        }
    }
}

@Composable
private fun ViewSwitcher(view: HomeView, onViewChange: (HomeView) -> Unit) {
    val options = listOf(HomeView.CATEGORY to R.string.view_by_category, HomeView.APP to R.string.view_by_app)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (option, label) ->
            SegmentedButton(
                selected = view == option,
                onClick = { onViewChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(stringResource(label)) },
            )
        }
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
            .padding(vertical = Dimens.Section),
    )
}

@Composable
private fun GroupTitle(header: GroupHeader, modifier: Modifier = Modifier) {
    when (header) {
        is GroupHeader.OfCategory -> SectionHeader(stringResource(header.category.labelRes()), modifier)
        GroupHeader.Common -> SectionHeader(stringResource(R.string.group_common), modifier)
        is GroupHeader.OfApp -> AppGroupTitle(header, modifier)
    }
}

@Composable
private fun AppGroupTitle(header: GroupHeader.OfApp, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Dimens.Gutter, top = Dimens.Section, end = Dimens.Gutter, bottom = Dimens.Small)
            .semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(header.packageName, Dimens.AppIconSmall)
        Text(header.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun HomeScreenPreview() {
    HyperPrivacyTheme {
        HomeScreen(sampleState(), HomeView.CATEGORY, {}, { _, _ -> }, {}, {})
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun HomeScreenByAppPreview() {
    HyperPrivacyTheme {
        HomeScreen(sampleState(), HomeView.APP, {}, { _, _ -> }, {}, {})
    }
}
