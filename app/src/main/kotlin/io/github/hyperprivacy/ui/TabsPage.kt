package io.github.hyperprivacy.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.components.AppIcons
import io.github.hyperprivacy.ui.home.OverviewActions
import io.github.hyperprivacy.ui.home.OverviewScreen
import io.github.hyperprivacy.ui.home.StatusActions
import io.github.hyperprivacy.ui.home.SystemActions
import io.github.hyperprivacy.ui.home.SystemScreen
import kotlinx.coroutines.launch

/* The root page: the three bottom-bar destinations ([Tab]) and the navigation bar. */

/** Material fade-through between tabs. */
private const val TAB_IN_MS = 210
private const val TAB_IN_DELAY_MS = 90
private const val TAB_OUT_MS = 90
private const val TAB_IN_SCALE = 0.96f

private fun tabTransition(): ContentTransform =
    (fadeIn(tween(TAB_IN_MS, delayMillis = TAB_IN_DELAY_MS)) + scaleIn(tween(TAB_IN_MS), TAB_IN_SCALE)) togetherWith
        fadeOut(tween(TAB_OUT_MS))

private fun Tab.icon(): ImageVector = when (this) {
    Tab.OVERVIEW -> AppIcons.Shield
    Tab.SYSTEM -> AppIcons.System
    Tab.APPS -> AppIcons.Apps
}

@StringRes
private fun Tab.labelRes(): Int = when (this) {
    Tab.OVERVIEW -> R.string.tab_overview
    Tab.SYSTEM -> R.string.tab_system
    Tab.APPS -> R.string.tab_apps
}

/** The root page: the selected tab above the navigation bar. */
@Composable
internal fun TabsPage(state: UiState, shell: Shell) {
    val scope = rememberCoroutineScope()
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == shell.tab,
                        onClick = { shell.select(tab, scope) },
                        // The label names the destination; the icon would only repeat it.
                        icon = { Icon(tab.icon(), contentDescription = null) },
                        label = {
                            Text(stringResource(tab.labelRes()), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
            }
        },
        // Each tab has its own Scaffold, which takes the system bar insets itself.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        AnimatedContent(
            targetState = shell.tab,
            modifier = Modifier.padding(inner).consumeWindowInsets(inner),
            transitionSpec = { tabTransition() },
            label = "tab",
        ) { tab ->
            when (tab) {
                Tab.OVERVIEW -> OverviewTab(state, shell)
                Tab.SYSTEM -> SystemTab(state, shell)
                Tab.APPS -> AppsPage(state, shell.model.trackers, shell.nav, shell.apps)
            }
        }
    }
}

@Composable
private fun OverviewTab(state: UiState, shell: Shell) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val actions = remember(shell, scope, context) {
        val model = shell.model
        OverviewActions(
            status = StatusActions(
                onOpenHealth = { shell.nav.push(Route.Health) },
                onEnableScope = model.device::request,
                onPause = model.pause::pause,
                onResume = model.pause::resume,
                onDisableAppListHiding = { scope.launch { disableAppListHiding(model, shell.snackbar, context) } },
                onDismissConflict = model.conflicts::dismiss,
            ),
            onOpenSettings = { shell.nav.push(Route.Settings) },
            onOpenSystem = { shell.tab = Tab.SYSTEM },
            onOpenApps = { shell.tab = Tab.APPS },
        )
    }
    OverviewScreen(
        state = state,
        actions = actions,
        blockedTotal = state.totalHits + state.protectedHits,
        listState = shell.overview.list,
        barState = shell.overview.bar,
        snackbarHostState = shell.snackbar,
    )
}

@Composable
private fun SystemTab(state: UiState, shell: Shell) {
    val scope = rememberCoroutineScope()
    val system = shell.system
    val actions = remember(shell, scope) {
        val model = shell.model
        SystemActions(
            onViewChange = {
                system.view = it
                scope.launch { if (system.scroll.list.firstVisibleItemIndex > 1) system.scroll.list.scrollToItem(1) }
            },
            onToggleExpanded = system::toggle,
            onToggleRule = { id, on -> model.setRulesEnabled(listOf(id), on) },
            onSetRules = model::setRulesEnabled,
            onOpenRule = { shell.nav.push(Route.RuleDetail(it)) },
            onOpenApp = { shell.nav.push(Route.App(it)) },
            onOpenSearch = { shell.nav.push(Route.Search) },
        )
    }
    SystemScreen(
        state = state,
        view = system.view,
        expanded = remember(system.expanded) { system.expanded.orEmpty().toSet() },
        actions = actions,
        listState = system.scroll.list,
        barState = system.scroll.bar,
    )
}

/** Turns off app-list hiding for the overlap hint, and offers to take it back for a while. */
private suspend fun disableAppListHiding(model: UiModel, snackbar: SnackbarHostState, context: Context) {
    val restore = model.conflicts.disableAppListHiding() ?: return
    snackbar.currentSnackbarData?.dismiss()
    val result = snackbar.showSnackbar(
        message = context.getString(R.string.conflict_applist_off),
        actionLabel = context.getString(R.string.action_undo),
        duration = SnackbarDuration.Long,
    )
    if (result == SnackbarResult.ActionPerformed) model.conflicts.restoreAppListHiding(restore)
}
