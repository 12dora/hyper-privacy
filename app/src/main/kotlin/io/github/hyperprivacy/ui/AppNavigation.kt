package io.github.hyperprivacy.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
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
import io.github.hyperprivacy.ui.home.HomeActions
import io.github.hyperprivacy.ui.home.HomeScreen
import io.github.hyperprivacy.ui.home.HomeView
import io.github.hyperprivacy.ui.search.SearchScreen
import io.github.hyperprivacy.ui.settings.SettingsScreen
import io.github.hyperprivacy.ui.theme.Appearance
import io.github.hyperprivacy.ui.theme.AppearanceStore
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

/** Saves the unfolded category keys; `null` (not yet defaulted) is simply not saved. */
private val KeysSaver = Saver<List<String>?, ArrayList<String>>(
    save = { keys -> keys?.let { ArrayList(it) } },
    restore = { it },
)

/** The back stack, root first. Pushing the current top again is a no-op (double taps). */
@Stable
private class Navigator(private val holder: MutableState<List<Route>>) {
    val stack: List<Route> get() = holder.value

    fun push(route: Route) {
        if (stack.last() != route) holder.value = stack + route
    }

    fun pop() {
        if (stack.size > 1) holder.value = stack.dropLast(1)
    }
}

/** Home state that must outlive the Home page while a deeper page is shown. */
@Stable
private class HomeMemory(
    view: MutableState<HomeView>,
    expanded: MutableState<List<String>?>,
    val listState: LazyListState,
) {
    var view by view

    // Null until the first state arrives, then defaulted by catalog size; afterwards the user's choice.
    var expanded by expanded

    fun toggle(key: String) {
        val current = expanded.orEmpty()
        expanded = if (key in current) current - key else current + key
    }
}

@Composable
internal fun AppNavigation(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stackHolder = rememberSaveable(stateSaver = Route.StackSaver) { mutableStateOf(listOf<Route>(Route.Home)) }
    val nav = remember(stackHolder) { Navigator(stackHolder) }
    val viewHolder = rememberSaveable { mutableStateOf(HomeView.CATEGORY) }
    val expandedHolder = rememberSaveable(stateSaver = KeysSaver) { mutableStateOf<List<String>?>(null) }
    val listState = rememberLazyListState()
    val home = remember(viewHolder, expandedHolder, listState) { HomeMemory(viewHolder, expandedHolder, listState) }
    if (home.expanded == null && state.loaded) {
        val keys = state.byCategory.map { it.key }
        home.expanded = if (state.rules.size <= EXPAND_ALL_MAX_RULES) keys else emptyList()
    }

    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = nav.stack.size > 1,
        onBackCancelled = {},
        onBackCompleted = nav::pop,
    )
    val backProgress = {
        (backState.transitionState as? NavigationEventTransitionState.InProgress)?.latestEvent?.progress ?: 0f
    }

    AnimatedContent(targetState = nav.stack, transitionSpec = { pageTransition() }, label = "route") { target ->
        val page = if (target.size > 1) Modifier.predictiveBack(backProgress) else Modifier
        Page(target.last(), state, viewModel, nav, home, page)
    }
}

@Composable
private fun Page(
    route: Route,
    state: UiState,
    viewModel: MainViewModel,
    nav: Navigator,
    home: HomeMemory,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toggleRule: (String, Boolean) -> Unit = { id, on -> viewModel.setRulesEnabled(listOf(id), on) }
    val openRule: (String) -> Unit = { nav.push(Route.RuleDetail(it)) }
    val openApp: (String) -> Unit = { nav.push(Route.App(it)) }
    val export: () -> Unit = {
        scope.launch {
            val report = viewModel.diagnosticReport()
            if (report == null) toast(context, R.string.health_export_failed) else share(context, report)
        }
    }
    when (route) {
        Route.Home -> HomeScreen(
            state = state,
            view = home.view,
            expanded = home.expanded.orEmpty().toSet(),
            actions = HomeActions(
                onViewChange = {
                    home.view = it
                    scope.launch { if (home.listState.firstVisibleItemIndex > 1) home.listState.scrollToItem(1) }
                },
                onToggleExpanded = home::toggle,
                onToggleRule = toggleRule,
                onSetRules = viewModel::setRulesEnabled,
                onOpenRule = openRule,
                onOpenApp = openApp,
                onOpenSearch = { nav.push(Route.Search) },
                onOpenSettings = { nav.push(Route.Settings) },
                onOpenHealth = { nav.push(Route.Health) },
            ),
            listState = home.listState,
        )

        Route.Settings -> SettingsPage(state, viewModel, nav, export, modifier)

        Route.Search -> SearchPage(state, toggleRule, openRule, openApp, nav, modifier)

        Route.Health -> HealthScreen(state, openRule, export, nav::pop, modifier)

        // Until the first state arrives there is nothing to show, and "not found" would flash.
        is Route.App -> if (state.loaded) {
            AppScreen(route.packageName, state, toggleRule, openRule, nav::pop, modifier)
        }

        is Route.RuleDetail -> if (state.loaded) {
            RuleDetailScreen(
                item = state.rule(route.ruleId),
                module = state.module,
                onToggle = { toggleRule(route.ruleId, it) },
                onBack = nav::pop,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun SettingsPage(
    state: UiState,
    viewModel: MainViewModel,
    nav: Navigator,
    onExport: () -> Unit,
    modifier: Modifier,
) {
    val appearance by AppearanceStore.appearance.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        appearance = appearance ?: Appearance(),
        onAppearanceChange = { next -> AppearanceStore.update { next } },
        onBack = nav::pop,
        onOpenHealth = { nav.push(Route.Health) },
        onExport = onExport,
        onDiagnosticChange = viewModel::setDiagnosticMode,
        onResetDefaults = viewModel::resetDefaults,
        onRefreshObserved = viewModel::refreshObserved,
        onClearObserved = viewModel::clearObserved,
        modifier = modifier,
    )
}

@Composable
private fun SearchPage(
    state: UiState,
    onToggleRule: (String, Boolean) -> Unit,
    onOpenRule: (String) -> Unit,
    onOpenApp: (String) -> Unit,
    nav: Navigator,
    modifier: Modifier,
) {
    // Survives opening a result and coming back.
    var query by rememberSaveable { mutableStateOf("") }
    SearchScreen(
        state = state,
        query = query,
        onQueryChange = { query = it },
        onToggleRule = onToggleRule,
        onOpenRule = onOpenRule,
        onOpenApp = onOpenApp,
        onBack = nav::pop,
        modifier = modifier,
    )
}

private fun share(context: Context, report: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.health_report_subject))
        putExtra(Intent.EXTRA_TEXT, report)
    }
    runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.health_export))) }
        .onFailure { toast(context, R.string.health_export_failed) }
}

private fun toast(context: Context, message: Int) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
