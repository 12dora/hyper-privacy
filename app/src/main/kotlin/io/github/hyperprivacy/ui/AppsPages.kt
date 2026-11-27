package io.github.hyperprivacy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hyperprivacy.ui.apps.AppDefaultsActions
import io.github.hyperprivacy.ui.apps.AppDefaultsScreen
import io.github.hyperprivacy.ui.apps.AppSwitch
import io.github.hyperprivacy.ui.apps.AppsActions
import io.github.hyperprivacy.ui.apps.AppsScreen
import io.github.hyperprivacy.ui.apps.AppsSearch
import io.github.hyperprivacy.ui.apps.UserAppActions
import io.github.hyperprivacy.ui.apps.UserAppScreen

/* The Apps tab and an app's page (user-installed apps and their protection), wired to [UiModel.trackers]. */

@Composable
internal fun AppsPage(state: UiState, trackers: AppsModel, nav: Navigator, memory: AppsMemory) {
    val apps by trackers.state.collectAsStateWithLifecycle()
    LaunchedEffect(trackers) { trackers.start() }
    val actions = remember(memory, trackers, nav) {
        AppsActions(
            onFilterChange = { memory.onlyTrackers = it },
            onSearchChange = { memory.searching = it },
            onQueryChange = { memory.query = it },
            onToggle = trackers::setProtected,
            onOpen = { nav.push(Route.UserApp(it)) },
            onOpenDefaults = { nav.push(Route.AppDefaults) },
        )
    }
    val search = remember(memory.searching, memory.query) { AppsSearch(memory.searching, memory.query) }
    AppsScreen(
        state = apps,
        canEdit = state.module.canEdit,
        onlyTrackers = memory.onlyTrackers,
        search = search,
        actions = actions,
        listState = memory.scroll.list,
        barState = memory.scroll.bar,
        snackbarHostState = memory.snackbar,
    )
}

@Composable
internal fun UserAppPage(
    packageName: String,
    state: UiState,
    trackers: AppsModel,
    nav: Navigator,
    memory: AppsMemory,
    modifier: Modifier,
) {
    val apps by trackers.state.collectAsStateWithLifecycle()
    LaunchedEffect(trackers) { trackers.start() }
    val actions = remember(packageName, trackers, nav) {
        UserAppActions(
            onProtect = { trackers.setProtected(packageName, it) },
            onRetryScope = { trackers.retryScope(packageName) },
            onSdk = { sdk, on -> trackers.set(AppSwitch.Sdk(packageName, sdk), on) },
            onAppRule = { rule, on -> trackers.set(AppSwitch.AppRule(packageName, rule), on) },
            onSuspect = { root, on -> trackers.set(AppSwitch.Suspect(packageName, root), on) },
            onHideApps = { trackers.set(AppSwitch.HideApps(packageName), it) },
            onStrict = { trackers.set(AppSwitch.Strict(packageName), it) },
            onBack = nav::pop,
        )
    }
    // Until the first list arrives there is nothing to show, and "not found" would flash.
    if (!apps.loaded) return
    UserAppScreen(
        app = apps.app(packageName),
        canEdit = state.module.canEdit,
        actions = actions,
        modifier = modifier,
        snackbarHostState = memory.snackbar,
    )
}

/** The default settings of newly protected apps, reached from the Apps tab. */
@Composable
internal fun AppDefaultsPage(state: UiState, trackers: AppsModel, nav: Navigator, modifier: Modifier) {
    val apps by trackers.state.collectAsStateWithLifecycle()
    LaunchedEffect(trackers) { trackers.start() }
    val actions = remember(trackers, nav) {
        AppDefaultsActions(
            onSdk = { category, on -> trackers.set(AppSwitch.DefaultSdk(category), on) },
            onRule = { rule, on -> trackers.set(AppSwitch.DefaultRule(rule), on) },
            onHideApps = { trackers.set(AppSwitch.DefaultHide, it) },
            onStrict = { trackers.set(AppSwitch.DefaultStrict, it) },
            onBack = nav::pop,
        )
    }
    AppDefaultsScreen(
        defaults = apps.defaults,
        canEdit = state.module.canEdit && apps.loaded,
        actions = actions,
        modifier = modifier,
    )
}
