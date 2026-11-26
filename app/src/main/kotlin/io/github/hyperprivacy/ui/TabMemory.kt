package io.github.hyperprivacy.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.hyperprivacy.ui.home.SystemView

/*
 * State of the bottom-bar destinations that must outlive them: a tab is disposed while another tab
 * or a deeper page is shown, so its positions and choices are held by the navigation instead. All
 * of it is saveable, so it survives rotation too.
 */

/** List position and title bar collapse of one tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
internal class TabScroll(val list: LazyListState, val bar: TopAppBarState) {
    /** Back to the top with the title expanded (the tab was selected again). */
    suspend fun reset() {
        bar.heightOffset = 0f
        bar.contentOffset = 0f
        list.animateScrollToItem(0)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberTabScroll(): TabScroll {
    val list = rememberLazyListState()
    val bar = rememberTopAppBarState()
    return remember(list, bar) { TabScroll(list, bar) }
}

/** Saves the unfolded category keys; `null` (not yet defaulted) is simply not saved. */
private val KeysSaver = Saver<List<String>?, ArrayList<String>>(
    save = { keys -> keys?.let { ArrayList(it) } },
    restore = { it },
)

/** The System tab: grouping, unfolded categories, the search text and scroll. */
@Stable
internal class SystemMemory(
    view: MutableState<SystemView>,
    expanded: MutableState<List<String>?>,
    query: MutableState<String>,
    val scroll: TabScroll,
) {
    var view by view

    // The text typed in the search opened from this tab; it outlives the search page (a result
    // pushes a deeper page over it) and is cleared when the search is left by going back.
    var query by query

    // Null until the first state arrives, then defaulted by catalog size; afterwards the user's choice.
    var expanded by expanded

    fun toggle(key: String) {
        val current = expanded.orEmpty()
        expanded = if (key in current) current - key else current + key
    }
}

@Composable
internal fun rememberSystemMemory(): SystemMemory {
    val view = rememberSaveable { mutableStateOf(SystemView.CATEGORY) }
    val expanded = rememberSaveable(stateSaver = KeysSaver) { mutableStateOf<List<String>?>(null) }
    val query = rememberSaveable { mutableStateOf("") }
    val scroll = rememberTabScroll()
    return remember(view, expanded, query, scroll) { SystemMemory(view, expanded, query, scroll) }
}
