package io.github.hyperprivacy.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.app.AppScreen
import io.github.hyperprivacy.ui.detail.RuleDetailScreen
import io.github.hyperprivacy.ui.health.HealthScreen
import io.github.hyperprivacy.ui.search.SearchScreen
import io.github.hyperprivacy.ui.settings.EndpointsScreen
import io.github.hyperprivacy.ui.settings.SettingsScreen
import io.github.hyperprivacy.ui.settings.rememberBackupActions
import io.github.hyperprivacy.ui.theme.Appearance
import io.github.hyperprivacy.ui.theme.AppearanceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val SLIDE_DIVISOR = 4
private const val BACK_SCALE_DELTA = 0.1f
private val BackShape = RoundedCornerShape(28.dp)

/** Catalogs up to this size open with every category unfolded; larger ones start folded. */
private const val EXPAND_ALL_MAX_RULES = 16

/** Deeper pages slide in from the end; going back reverses the motion. */
private fun AnimatedContentTransitionScope<List<Route>>.pageTransition(): ContentTransform {
    val direction = if (targetState.size >= initialState.size) 1 else -1
    return (slideInHorizontally { direction * it / SLIDE_DIVISOR } + fadeIn()) togetherWith
        (slideOutHorizontally { -direction * it / SLIDE_DIVISOR } + fadeOut())
}

/** The back stack, root first. Pushing the current top again is a no-op (double taps). */
@Stable
internal class Navigator(private val holder: MutableState<List<Route>>) {
    val stack: List<Route> get() = holder.value

    fun push(route: Route) {
        if (stack.last() != route) holder.value = stack + route
    }

    fun pop() {
        if (stack.size > 1) holder.value = stack.dropLast(1)
    }

    /** Back to the tabs, dropping every page pushed over them. */
    fun popToRoot() {
        if (stack.size > 1) holder.value = stack.take(1)
    }
}

/** Everything the pages share: the model, the back stack, the selected tab and each tab's state. */
@Stable
internal class Shell(
    val model: UiModel,
    val nav: Navigator,
    tab: MutableState<Tab>,
    val overview: TabScroll,
    val system: SystemMemory,
    val apps: AppsMemory,
    val snackbar: SnackbarHostState,
) {
    var tab by tab

    /** Selecting the current tab again scrolls it back to the top. */
    fun select(target: Tab, scope: CoroutineScope) {
        if (target != tab) {
            tab = target
            return
        }
        val scroll = when (target) {
            Tab.OVERVIEW -> overview
            Tab.SYSTEM -> system.scroll
            Tab.APPS -> apps.scroll
        }
        scope.launch { scroll.reset() }
    }

    /**
     * Back: a deeper page first, then an open search of the Apps tab, then from another tab to
     * Overview; on Overview the system takes it (exit).
     */
    val canGoBack: Boolean get() = nav.stack.size > 1 || tab != Tab.OVERVIEW

    fun back() {
        when {
            nav.stack.size > 1 -> {
                // Leaving the search forgets what was typed; opening a result and returning does not.
                if (nav.stack.last() == Route.Search) system.query = ""
                nav.pop()
            }

            tab == Tab.APPS && apps.searching -> apps.closeSearch()

            else -> tab = Tab.OVERVIEW
        }
    }
}

@Composable
internal fun AppNavigation(model: UiModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val stackHolder = rememberSaveable(stateSaver = Route.StackSaver) { mutableStateOf(listOf<Route>(Route.Tabs)) }
    val tabHolder = rememberSaveable { mutableStateOf(Tab.OVERVIEW) }
    val overview = rememberTabScroll()
    val system = rememberSystemMemory()
    val apps = rememberAppsMemory(model.trackers)
    val snackbar = remember { SnackbarHostState() }
    val shell = remember(model, stackHolder, tabHolder, overview, system, apps, snackbar) {
        Shell(model, Navigator(stackHolder), tabHolder, overview, system, apps, snackbar)
    }
    ScopeMessages(model.device, snackbar)
    // Default unfolding is a one-off effect of the first state, not a write made while composing.
    LaunchedEffect(state.loaded) {
        if (state.loaded && system.expanded == null) {
            val keys = state.byCategory.map { it.key }
            system.expanded = if (state.rules.size <= EXPAND_ALL_MAX_RULES) keys else emptyList()
        }
    }

    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = shell.canGoBack,
        onBackCancelled = {},
        onBackCompleted = shell::back,
    )
    val backProgress = {
        (backState.transitionState as? NavigationEventTransitionState.InProgress)?.latestEvent?.progress ?: 0f
    }

    AnimatedContent(targetState = shell.nav.stack, transitionSpec = { pageTransition() }, label = "route") { target ->
        when (val route = target.last()) {
            Route.Tabs -> TabsPage(state, shell)
            else -> Page(route, state, shell, Modifier.predictiveBack(backProgress))
        }
    }
}

/** A page pushed over the tabs. */
@Composable
private fun Page(route: Route, state: UiState, shell: Shell, modifier: Modifier) {
    val model = shell.model
    val nav = shell.nav
    val toggleRule: (String, Boolean) -> Unit = { id, on -> model.setRulesEnabled(listOf(id), on) }
    val openRule: (String) -> Unit = { nav.push(Route.RuleDetail(it)) }
    when (route) {
        Route.Settings -> SettingsPage(state, shell, modifier)

        Route.Search -> SearchPage(state, toggleRule, openRule, shell, modifier)

        Route.Health -> HealthScreen(state, openRule, rememberReportExport(model), nav::pop, modifier)

        Route.Endpoints -> EndpointsScreen(state, model::refreshObserved, model::clearObserved, nav::pop, modifier)

        // Until the first state arrives there is nothing to show, and "not found" would flash.
        is Route.App -> if (state.loaded) {
            AppScreen(route.packageName, state, toggleRule, openRule, nav::pop, modifier)
        }

        Route.AppDefaults -> AppDefaultsPage(state, model.trackers, nav, modifier)

        Route.Allowlist -> AllowlistPage(state, model.trackers, nav, modifier)

        Route.Conflicts -> ConflictsPage(state, shell, modifier)

        is Route.UserApp -> UserAppPage(route.packageName, state, model.trackers, nav, shell.apps, modifier)

        is Route.RuleDetail -> if (state.loaded) {
            RuleDetailScreen(
                item = state.rule(route.ruleId),
                module = state.module,
                onToggle = { toggleRule(route.ruleId, it) },
                onBack = nav::pop,
                modifier = modifier,
            )
        }

        // The root is shown by TabsPage, never as a pushed page.
        Route.Tabs -> Unit
    }
}

@Composable
private fun SettingsPage(state: UiState, shell: Shell, modifier: Modifier) {
    val model = shell.model
    val nav = shell.nav
    val appearance by AppearanceStore.appearance.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        appearance = appearance ?: Appearance(),
        onAppearanceChange = { next -> AppearanceStore.update { next } },
        onBack = nav::pop,
        onOpenHealth = { nav.push(Route.Health) },
        onExport = rememberReportExport(model),
        backup = rememberBackupActions(model.config),
        onDiagnosticChange = { model.setSwitch(ModuleSwitch.DIAGNOSTICS, it) },
        onSelfHideChange = { model.setSwitch(ModuleSwitch.SELF_HIDE, it) },
        onResetDefaults = model::resetDefaults,
        onOpenEndpoints = { nav.push(Route.Endpoints) },
        onOpenAllowlist = { nav.push(Route.Allowlist) },
        onRefreshObserved = model::refreshObserved,
        modifier = modifier,
    )
}

@Composable
private fun SearchPage(
    state: UiState,
    onToggleRule: (String, Boolean) -> Unit,
    onOpenRule: (String) -> Unit,
    shell: Shell,
    modifier: Modifier,
) {
    // The query lives in the System tab's memory: this page is disposed while a result is open.
    val system = shell.system
    SearchScreen(
        state = state,
        query = system.query,
        onQueryChange = { system.query = it },
        onToggleRule = onToggleRule,
        onOpenRule = onOpenRule,
        onOpenApp = { shell.nav.push(Route.App(it)) },
        onBack = shell::back,
        modifier = modifier,
    )
}

/** Shows each scope request outcome once, on Overview's snackbar host (requests are only made there). */
@Composable
private fun ScopeMessages(device: ScopeModel, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(device, snackbar, resources) {
        device.messages.collect { result ->
            snackbar.currentSnackbarData?.dismiss()
            // Launched so a newer outcome can replace one still on screen.
            launch { snackbar.showSnackbar(resources.getString(result.messageRes()), duration = SnackbarDuration.Long) }
        }
    }
}

internal fun ScopeResult.messageRes(): Int = when (this) {
    ScopeResult.APPROVED -> R.string.scope_approved
    ScopeResult.APPROVED_REBOOT -> R.string.scope_approved_reboot
    ScopeResult.DENIED -> R.string.scope_denied
    ScopeResult.NO_RESPONSE -> R.string.scope_no_response
    ScopeResult.UNSUPPORTED -> R.string.scope_unsupported
}

/** Shrinks and rounds the page while the back gesture is in progress. */
private fun Modifier.predictiveBack(progress: () -> Float): Modifier = graphicsLayer {
    val fraction = progress()
    val scale = 1f - BACK_SCALE_DELTA * fraction
    scaleX = scale
    scaleY = scale
    shape = BackShape
    clip = fraction > 0f
}
