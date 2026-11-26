package io.github.hyperprivacy.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.UiState
import io.github.hyperprivacy.ui.components.AppIcons
import io.github.hyperprivacy.ui.components.IconBadge
import io.github.hyperprivacy.ui.components.NavRow
import io.github.hyperprivacy.ui.components.SectionHeader
import io.github.hyperprivacy.ui.components.TabTopBar
import io.github.hyperprivacy.ui.components.countString
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.components.listPadding
import io.github.hyperprivacy.ui.sampleState

private const val STATUS_KEY = "status"
private const val COVERAGE_KEY = "coverage-title"

/** Where each coverage row leads. */
internal class OverviewActions(
    val status: StatusActions,
    val onOpenSettings: () -> Unit,
    val onOpenSystem: () -> Unit,
    val onOpenApps: () -> Unit,
)

private enum class CoverageRow { SYSTEM, APPS, BLOCKED }

/**
 * The Overview tab: the status card (state, pause, and the hints that need a tap), then what is
 * covered: rules on for system apps, protected installed apps, and the blocked total once there is
 * one. Each row opens its tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OverviewScreen(
    state: UiState,
    actions: OverviewActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    barState: TopAppBarState = rememberTopAppBarState(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(barState)
    val rows = remember(state.totalHits > 0) {
        CoverageRow.entries.filter { it != CoverageRow.BLOCKED || state.totalHits > 0 }
    }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TabTopBar(stringResource(R.string.app_name), scrollBehavior) {
                IconButton(onClick = actions.onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = listPadding(inner)) {
            item(key = STATUS_KEY, contentType = STATUS_KEY) { StatusCard(state, actions.status) }
            if (state.loaded) {
                item(key = COVERAGE_KEY) { SectionHeader(stringResource(R.string.overview_coverage)) }
                groupedItems(rows, key = { "coverage/$it" }) { row -> CoverageItem(row, state, actions) }
            }
        }
    }
}

@Composable
private fun CoverageItem(row: CoverageRow, state: UiState, actions: OverviewActions) {
    when (row) {
        CoverageRow.SYSTEM -> CoverageNavRow(
            icon = AppIcons.System,
            title = stringResource(R.string.system_title),
            summary = stringResource(R.string.overview_rules_summary, state.enabledCount, state.rules.size),
            onClick = actions.onOpenSystem,
        )

        CoverageRow.APPS -> CoverageNavRow(
            icon = AppIcons.Apps,
            title = stringResource(R.string.apps_title),
            summary = if (state.protectedApps > 0) {
                pluralStringResource(R.plurals.overview_apps_protected, state.protectedApps, state.protectedApps)
            } else {
                stringResource(R.string.overview_apps_none)
            },
            onClick = actions.onOpenApps,
        )

        CoverageRow.BLOCKED -> CoverageNavRow(
            icon = AppIcons.Shield,
            title = stringResource(R.string.overview_blocked),
            summary = countString(R.plurals.summary_blocked, state.totalHits),
            onClick = actions.onOpenSystem,
        )
    }
}

@Composable
private fun CoverageNavRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    NavRow(
        title = title,
        summary = summary,
        onClick = onClick,
        leading = { IconBadge(icon, colors.secondaryContainer, colors.onSecondaryContainer) },
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun OverviewScreenPreview() {
    HyperPrivacyTheme {
        OverviewScreen(
            state = sampleState().copy(loaded = true, protectedApps = 3),
            actions = OverviewActions(StatusActions(), {}, {}, {}),
        )
    }
}
