package io.github.hyperprivacy.ui

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.ui.home.ConflictActions
import io.github.hyperprivacy.ui.home.ConflictScreen
import io.github.hyperprivacy.ui.home.SystemView
import kotlinx.coroutines.launch

/* The overlap detail page, reached from the hint on Overview, wired to [UiModel.conflicts] and the System tab. */

@Composable
internal fun ConflictsPage(state: UiState, shell: Shell, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val actions = remember(shell, scope, context) {
        ConflictActions(
            onBack = shell.nav::pop,
            onDisableAppList = { scope.launch { disableAppListHiding(shell.model, shell.snackbar, context) } },
            onOpenRules = { shell.openRules(it) },
        )
    }
    ConflictScreen(
        conflict = state.conflict,
        canEdit = state.module.canEdit,
        actions = actions,
        modifier = modifier,
        snackbarHostState = shell.snackbar,
    )
}

/**
 * Shows this module's built-in rules of [category] on the System tab: the pages over the tabs are
 * closed, the list is grouped by type, and only that category is unfolded, at the top.
 */
private fun Shell.openRules(category: Category) {
    nav.popToRoot()
    tab = Tab.SYSTEM
    system.view = SystemView.CATEGORY
    system.expanded = listOf(CategoryGroup.keyOf(category))
    system.scroll.list.requestScrollToItem(0)
}

/** Turns off app-list hiding for the overlap hint, and offers to take it back for a while. */
internal suspend fun disableAppListHiding(model: UiModel, snackbar: SnackbarHostState, context: Context) {
    val restore = model.conflicts.disableAppListHiding() ?: return
    snackbar.currentSnackbarData?.dismiss()
    val result = snackbar.showSnackbar(
        message = context.getString(R.string.conflict_applist_off),
        actionLabel = context.getString(R.string.action_undo),
        duration = SnackbarDuration.Long,
    )
    if (result == SnackbarResult.ActionPerformed) model.conflicts.restoreAppListHiding(restore)
}
