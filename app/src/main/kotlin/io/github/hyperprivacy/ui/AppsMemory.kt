package io.github.hyperprivacy.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.launch

/**
 * Apps page state that must outlive the page while an app's page is shown (the two are swapped by
 * the navigation's `AnimatedContent`): the filter, the list position, and the one snackbar host
 * that scope request outcomes are collected into.
 */
@Stable
internal class AppsMemory(onlyTrackers: MutableState<Boolean>, val listState: LazyListState) {
    var onlyTrackers by onlyTrackers
    val snackbar = SnackbarHostState()
}

@Composable
internal fun rememberAppsMemory(viewModel: MainViewModel): AppsMemory {
    val onlyTrackers = rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val memory = remember(onlyTrackers, listState) { AppsMemory(onlyTrackers, listState) }
    TrackerMessages(viewModel, memory.snackbar)
    return memory
}

/** Scope request outcomes of tracker protection: one collector, on the host both Apps pages show. */
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
