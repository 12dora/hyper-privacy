package io.github.hyperprivacy.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme

/** A light two-option toggle, 36dp high: a quiet track with a tonal pill under the selected label. */
@Composable
internal fun ViewSwitcher(view: HomeView, onViewChange: (HomeView) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(HomeView.CATEGORY to R.string.view_by_category, HomeView.APP to R.string.view_by_app)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.SwitcherHeight),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.selectableGroup().padding(Dimens.RowGap)) {
            options.forEach { (option, label) ->
                SwitcherOption(
                    label = stringResource(label),
                    selected = view == option,
                    onSelect = { onViewChange(option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SwitcherOption(label: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.secondaryContainer else colors.surfaceContainer,
        label = "switcherContainer",
    )
    val content by animateColorAsState(
        if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        label = "switcherContent",
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = CircleShape, color = container, modifier = Modifier.matchParentSize()) {}
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

@Preview(showBackground = true)
@Composable
private fun ViewSwitcherPreview() {
    HyperPrivacyTheme {
        ViewSwitcher(HomeView.CATEGORY, {}, Modifier.padding(Dimens.Gutter))
    }
}
