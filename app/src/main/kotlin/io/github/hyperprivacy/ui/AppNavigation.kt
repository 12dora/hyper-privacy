package io.github.hyperprivacy.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.hyperprivacy.ui.detail.RuleDetailScreen
import io.github.hyperprivacy.ui.home.HomeScreen
import io.github.hyperprivacy.ui.home.HomeView
import io.github.hyperprivacy.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private const val SLIDE_DIVISOR = 4
private const val BACK_SCALE_DELTA = 0.1f
private val BackShape = RoundedCornerShape(28.dp)

/** Home → page transitions slide in from the end; going back reverses them. */
private fun AnimatedContentTransitionScope<Route>.pageTransition(): ContentTransform {
    val forward = targetState != Route.Home
    val direction = if (forward) 1 else -1
    return (slideInHorizontally { direction * it / SLIDE_DIVISOR } + fadeIn()) togetherWith
        (slideOutHorizontally { -direction * it / SLIDE_DIVISOR } + fadeOut())
}

@Composable
internal fun AppNavigation(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var route by rememberSaveable(stateSaver = Route.Saver) { mutableStateOf<Route>(Route.Home) }
    var homeView by rememberSaveable { mutableStateOf(HomeView.CATEGORY) }
    val homeListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = route != Route.Home,
        onBackCancelled = {},
        onBackCompleted = { route = Route.Home },
    )
    val backProgress = {
        (backState.transitionState as? NavigationEventTransitionState.InProgress)?.latestEvent?.progress ?: 0f
    }

    AnimatedContent(targetState = route, transitionSpec = { pageTransition() }, label = "route") { target ->
        val pageModifier = if (target == Route.Home) Modifier else Modifier.predictiveBack(backProgress)
        when (target) {
            Route.Home -> HomeScreen(
                state = state,
                view = homeView,
                onViewChange = {
                    homeView = it
                    scope.launch { if (homeListState.firstVisibleItemIndex > 1) homeListState.scrollToItem(1) }
                },
                onToggleRule = viewModel::setRuleEnabled,
                onOpenRule = { route = Route.RuleDetail(it) },
                onOpenSettings = { route = Route.Settings },
                listState = homeListState,
            )

            Route.Settings -> SettingsScreen(
                state = state,
                onBack = { route = Route.Home },
                onDiagnosticChange = viewModel::setDiagnosticMode,
                onResetDefaults = viewModel::resetDefaults,
                onRefreshObserved = viewModel::refreshObserved,
                onClearObserved = viewModel::clearObserved,
                modifier = pageModifier,
            )

            is Route.RuleDetail -> {
                val item = state.rule(target.ruleId)
                // Until the first state arrives there is nothing to show, and "not found" would flash.
                if (state.loaded) {
                    RuleDetailScreen(
                        item = item,
                        module = state.module,
                        onToggle = { viewModel.setRuleEnabled(target.ruleId, it) },
                        onBack = { route = Route.Home },
                        modifier = pageModifier,
                    )
                }
            }
        }
    }
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
