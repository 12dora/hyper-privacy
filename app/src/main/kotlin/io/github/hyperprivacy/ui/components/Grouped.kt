package io.github.hyperprivacy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hyperprivacy.ui.Dimens

/** Outer corners on the first and last row of a group, small corners between rows (system-settings look). */
private fun groupedShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) Dimens.CornerOuter else Dimens.CornerInner
    val bottom = if (index == count - 1) Dimens.CornerOuter else Dimens.CornerInner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** One row of a rounded group card. Rows are separate lazy items, so long groups stay virtualised. */
@Composable
internal fun GroupedSurface(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = if (index == count - 1) 0.dp else Dimens.RowGap),
        shape = groupedShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainer,
        content = { Column(content = content) },
    )
}

/** Adds [items] as the rows of one rounded group. */
internal fun <T> LazyListScope.groupedItems(
    items: List<T>,
    key: (T) -> Any,
    content: @Composable LazyItemScope.(T) -> Unit,
) {
    items(count = items.size, key = { key(items[it]) }, contentType = { GROUPED_ROW }) { index ->
        GroupedSurface(index, items.size, Modifier.animateItem()) { content(items[index]) }
    }
}

/** A card with a single row. */
@Composable
internal fun SingleCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    GroupedSurface(index = 0, count = 1, modifier = modifier, content = content)

/** A 36dp group label above a card; [leading] is an optional 20dp icon. */
@Composable
internal fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Dimens.GroupGap)
            .heightIn(min = Dimens.HeaderHeight)
            .padding(horizontal = Dimens.Gutter)
            .semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Content padding that keeps the list inside the system bars and cutouts, plus the 16dp gutter. */
@Composable
internal fun listPadding(inner: PaddingValues, bottomExtra: Dp = Dimens.Gutter): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = inner.calculateStartPadding(direction) + Dimens.Gutter,
        top = inner.calculateTopPadding(),
        end = inner.calculateEndPadding(direction) + Dimens.Gutter,
        bottom = inner.calculateBottomPadding() + bottomExtra,
    )
}

private const val GROUPED_ROW = "grouped-row"
