package io.github.hyperprivacy.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hyperprivacy.ui.apps.AppsActions
import io.github.hyperprivacy.ui.apps.AppsScreen
import io.github.hyperprivacy.ui.apps.UserAppActions
import io.github.hyperprivacy.ui.apps.UserAppScreen
import kotlinx.coroutines.launch

/* The Apps pages (user-installed apps and tracker protection), wired to [MainViewModel.trackers]. */

@Composable
internal fun AppsPage(state: UiState, viewModel: MainViewModel, nav: Navigator, modifier: Modifier) {
    val apps by viewModel.trackers.state.collectAsStateWithLifecycle()
    var onlyTrackers by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.trackers.refresh(start = true) }
    TrackerMessages(viewModel, snackbar)
    AppsScreen(
        state = apps,
        canEdit = state.module.canEdit,
        onlyTrackers = onlyTrackers,
        actions = AppsActions(
            onFilterChange = { onlyTrackers = it },
            onToggle = viewModel.trackers::setProtected,
            onOpen = { nav.push(Route.UserApp(it)) },
            onBack = nav::pop,
        ),
        modifier = modifier,
        snackbarHostState = snackbar,
    )
}

@Composable
internal fun UserAppPage(
    packageName: String,
    state: UiState,
    viewModel: MainViewModel,
    nav: Navigator,
    modifier: Modifier,
) {
    val apps by viewModel.trackers.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.trackers.refresh(start = true) }
    TrackerMessages(viewModel, snackbar)
    // Until the first list arrives there is nothing to show, and "not found" would flash.
    if (!apps.loaded) return
    val trackers = viewModel.trackers
    UserAppScreen(
        app = apps.app(packageName),
        canEdit = state.module.canEdit,
        actions = UserAppActions(
            onProtect = { trackers.setProtected(packageName, it) },
            onRetryScope = { trackers.retryScope(packageName) },
            onSdk = { sdk, on -> trackers.setSdkBlocked(packageName, sdk, on) },
            onHideApps = { trackers.setHideApps(packageName, it) },
            onStrict = { trackers.setStrict(packageName, it) },
            onBack = nav::pop,
        ),
        modifier = modifier,
        snackbarHostState = snackbar,
    )
}

/** Scope request outcomes of tracker protection, on the snackbar of the Apps page that is showing. */
@Composable
private fun TrackerMessages(viewModel: MainViewModel, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(viewModel, snackbar, resources) {
        viewModel.trackers.messages.collect { result ->
            snackbar.currentSnackbarData?.dismiss()
            launch { snackbar.showSnackbar(resources.getString(result.messageRes()), duration = SnackbarDuration.Long) }
        }
    }
}
