package io.github.hyperprivacy.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The collapsed bar, plus the space the title needs below it (the title's line height grows with the font scale). */
private val CollapsedHeight = 64.dp
private val TitleBottom = 12.dp

/**
 * The large, collapsing title bar of a bottom-bar destination; it has no back button. Shorter than
 * Material's 152dp default ([expandedHeight]), so the title sits close to its actions and the first
 * card, as in the system Settings app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    actions: @Composable RowScope.() -> Unit = {},
) {
    LargeTopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = actions,
        expandedHeight = expandedHeight(),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
        scrollBehavior = scrollBehavior,
    )
}

/** 112dp at the default font scale (Material's default is 152dp), growing so a scaled title never clips. */
@Composable
private fun expandedHeight(): Dp {
    val title = with(LocalDensity.current) { MaterialTheme.typography.headlineMedium.lineHeight.toDp() }
    return CollapsedHeight + title + TitleBottom
}
