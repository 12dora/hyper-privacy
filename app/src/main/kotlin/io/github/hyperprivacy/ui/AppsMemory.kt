package io.github.hyperprivacy.ui

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
 * Apps tab state that must outlive the tab while another tab or an app's page is shown (pages are
 * swapped by the navigation's `AnimatedContent`): the filter, the search, the list and title bar positions, and
 * the one snackbar host that scope request outcomes are collected into.
 */
@Stable
internal class AppsMemory(
    onlyTrackers: MutableState<Boolean>,
    searching: MutableState<Boolean>,
    query: MutableState<String>,
    val scroll: TabScroll,
) {
    var onlyTrackers by onlyTrackers
    var searching by searching
    var query by query
    val snackbar = SnackbarHostState()

    /** Leaves search mode and forgets the query. */
    fun closeSearch() {
        searching = false
        query = ""
    }
}

@Composable
internal fun rememberAppsMemory(model: AppsModel): AppsMemory {
    val onlyTrackers = rememberSaveable { mutableStateOf(false) }
    val searching = rememberSaveable { mutableStateOf(false) }
    val query = rememberSaveable { mutableStateOf("") }
    val scroll = rememberTabScroll()
    val memory = remember(onlyTrackers, searching, query, scroll) { AppsMemory(onlyTrackers, searching, query, scroll) }
    TrackerMessages(model, memory.snackbar)
    return memory
}

/** Scope request outcomes of tracker protection: one collector, on the host both Apps pages show. */
@Composable
private fun TrackerMessages(model: AppsModel, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(model, snackbar, resources) {
        model.messages.collect { result ->
            snackbar.currentSnackbarData?.dismiss()
            launch { snackbar.showSnackbar(resources.getString(result.messageRes()), duration = SnackbarDuration.Long) }
        }
    }
}
