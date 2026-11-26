package io.github.hyperprivacy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hyperprivacy.ui.apps.AppSwitch
import io.github.hyperprivacy.ui.apps.AppsActions
import io.github.hyperprivacy.ui.apps.AppsScreen
import io.github.hyperprivacy.ui.apps.UserAppActions
import io.github.hyperprivacy.ui.apps.UserAppScreen

/* The Apps tab and an app's page (user-installed apps and their protection), wired to [UiModel.trackers]. */

@Composable
internal fun AppsPage(state: UiState, trackers: AppsModel, nav: Navigator, memory: AppsMemory) {
    val apps by trackers.state.collectAsStateWithLifecycle()
    LaunchedEffect(trackers) { trackers.start() }
    AppsScreen(
        state = apps,
        canEdit = state.module.canEdit,
        onlyTrackers = memory.onlyTrackers,
        actions = AppsActions(
            onFilterChange = { memory.onlyTrackers = it },
            onToggle = trackers::setProtected,
            onOpen = { nav.push(Route.UserApp(it)) },
        ),
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
    // Until the first list arrives there is nothing to show, and "not found" would flash.
    if (!apps.loaded) return
    UserAppScreen(
        app = apps.app(packageName),
        canEdit = state.module.canEdit,
        actions = UserAppActions(
            onProtect = { trackers.setProtected(packageName, it) },
            onRetryScope = { trackers.retryScope(packageName) },
            onSdk = { sdk, on -> trackers.set(AppSwitch.Sdk(packageName, sdk), on) },
            onAppRule = { rule, on -> trackers.set(AppSwitch.AppRule(packageName, rule), on) },
            onSuspect = { root, on -> trackers.set(AppSwitch.Suspect(packageName, root), on) },
            onHideApps = { trackers.set(AppSwitch.HideApps(packageName), it) },
            onStrict = { trackers.set(AppSwitch.Strict(packageName), it) },
            onBack = nav::pop,
        ),
        modifier = modifier,
        snackbarHostState = memory.snackbar,
    )
}
