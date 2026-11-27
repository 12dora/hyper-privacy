package io.github.hyperprivacy.ui

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.conflict.OurSide
import io.github.hyperprivacy.core.conflict.OverlapArea
import io.github.hyperprivacy.core.conflict.OverlapTarget
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.tracker.TrackerRules
import io.github.hyperprivacy.ui.apps.AppsUi
import io.github.hyperprivacy.ui.home.ConflictActions
import io.github.hyperprivacy.ui.home.ConflictDetails
import io.github.hyperprivacy.ui.home.ConflictScreen
import io.github.hyperprivacy.ui.home.SystemView
import io.github.hyperprivacy.ui.home.categoryItemIndex
import kotlinx.coroutines.launch

/* The overlap detail page, reached from the hint on Overview, wired to [UiModel.conflicts] and the System tab. */

@Composable
internal fun ConflictsPage(state: UiState, shell: Shell, modifier: Modifier) {
    // The application context: the undo snackbar may still be showing after this page is gone.
    val context = LocalContext.current.applicationContext
    val stateNow = rememberUpdatedState(state)
    val model = shell.model
    val apps by model.trackers.state.collectAsStateWithLifecycle()
    // Declared scopes and protected apps are read only now, off the main thread, never for Overview.
    LaunchedEffect(model) {
        model.conflicts.loadScopes()
        model.trackers.start()
    }
    val actions = remember(shell, context) {
        ConflictActions(
            onBack = shell.nav::pop,
            onDisableAppList = { shell.scope.launch { disableAppListHiding(shell.model, shell.snackbar, context) } },
            onOpenRules = { shell.openRules(stateNow.value, it) },
            onOpenRule = { shell.nav.push(Route.RuleDetail(it)) },
            onOpenApp = { pkg, protected -> shell.nav.push(if (protected) Route.UserApp(pkg) else Route.App(pkg)) },
            onOpenSettings = { shell.nav.push(Route.Settings) },
            onRestoreHint = model.conflicts::restoreHint,
        )
    }
    val ours = remember(state.rules, apps.apps) { ourSide(state, apps) }
    val systemLabel = stringResource(R.string.conflict_system_label)
    val labels = remember(state.apps, apps.apps, systemLabel) {
        state.apps.associate { it.packageName to it.label } + apps.apps.associate { it.packageName to it.label } +
            (Catalog.SYSTEM_SCOPE to systemLabel)
    }
    val categories = remember(state.byCategory) { state.byCategory.mapTo(HashSet()) { it.category } }
    // Until the first state arrives there is nothing to show, and "none found" would flash.
    if (!state.loaded) return
    ConflictScreen(
        details = ConflictDetails(
            overlaps = state.overlaps,
            canEdit = state.module.canEdit,
            ruleCategories = categories,
            ours = ours,
            labels = labels,
            dismissed = state.overlapsDismissed,
            appsLoaded = apps.loaded,
        ),
        actions = actions,
        modifier = modifier,
        snackbarHostState = shell.snackbar,
    )
}

/**
 * What this module does, for intersecting with other modules' scopes: the built-in rules that are
 * on; per app the options that are on (a protected app's SDKs, rules and suspected components, and
 * any app's app-list hiding, which works without protection); and in system_server, hiding this
 * module and hiding the app list, by the overlap area they belong to.
 */
private fun ourSide(state: UiState, apps: AppsUi): OurSide {
    val appOptions = apps.apps.associate { app ->
        val byArea = HashMap<OverlapArea, MutableList<String>>()
        fun add(category: Category?, id: String) {
            category?.let(OverlapArea::of)?.let { byArea.getOrPut(it) { ArrayList() } += id }
        }
        if (app.protected) {
            app.sdks.filter { it.blocked }.forEach { add(TrackerRules.categoryOf(it.category), it.id) }
            app.appRules.filter { it.enabled }.forEach { rule ->
                add(Catalog.protectedAppRules.firstOrNull { it.id == rule.id }?.category, rule.id)
            }
            app.suspects.filter { it.blocked }.forEach { add(Category.ADS, SUSPECT_PREFIX + it.root) }
        }
        if (app.hideApps) add(Category.APP_LIST, OverlapTarget.HIDE_APPS)
        app.packageName to byArea.mapValues { it.value.toList() }
    }.filterValues { it.isNotEmpty() }
    val system = listOfNotNull(
        OverlapTarget.HIDE_SELF.takeIf { state.selfHidden },
        OverlapTarget.HIDE_APPS.takeIf { apps.apps.any { it.hideApps } },
    )
    return OurSide(
        enabledRules = state.rules.filter { it.enabled }.map { it.rule },
        appOptions = appOptions,
        systemOptions = if (system.isEmpty()) emptyMap() else mapOf(OverlapArea.APP_LIST to system),
    )
}

/** Option ids of suspected ad components: the prefix, then the package root. */
internal const val SUSPECT_PREFIX = "suspect:"

/**
 * Shows this module's built-in rules of [category] on the System tab: the pages over the tabs are
 * closed, the list is grouped by type, only that category is unfolded, and it is scrolled to with
 * the title bar expanded.
 */
private fun Shell.openRules(state: UiState, category: Category) {
    nav.popToRoot()
    tab = Tab.SYSTEM
    system.view = SystemView.CATEGORY
    system.expanded = listOf(CategoryGroup.keyOf(category))
    system.scroll.bar.heightOffset = 0f
    system.scroll.bar.contentOffset = 0f
    system.scroll.list.requestScrollToItem(categoryItemIndex(state, category) ?: 0)
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
