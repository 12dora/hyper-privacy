package io.github.hyperprivacy.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.components.SectionHeader
import io.github.hyperprivacy.ui.components.SwitchRow
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.isDark
import io.github.hyperprivacy.ui.theme.Appearance
import io.github.hyperprivacy.ui.theme.DarkMode
import io.github.hyperprivacy.ui.theme.ThemePalette

private const val HALF_TURN = 180f
private const val QUARTER_TURN = 90f
private val RingWidth = 2.dp
private val RingGap = 3.dp

private enum class AppearanceRow { MODE, PALETTE, PURE_BLACK }

/** Light/dark mode, color theme and the OLED black option; every change applies at once. */
internal fun LazyListScope.appearanceSection(appearance: Appearance, onChange: (Appearance) -> Unit) {
    item(key = "appearance-title") { SectionHeader(stringResource(R.string.settings_appearance)) }
    groupedItems(AppearanceRow.entries, key = { "appearance/$it" }) { row ->
        when (row) {
            AppearanceRow.MODE -> ModePicker(appearance.mode) { onChange(appearance.copy(mode = it)) }

            AppearanceRow.PALETTE -> PalettePicker(appearance) { onChange(appearance.copy(palette = it)) }

            AppearanceRow.PURE_BLACK -> SwitchRow(
                title = stringResource(R.string.appearance_pure_black),
                summary = stringResource(R.string.appearance_pure_black_summary),
                checked = appearance.pureBlack,
                enabled = appearance.isDark(),
                onCheckedChange = { onChange(appearance.copy(pureBlack = it)) },
            )
        }
    }
}

@Composable
private fun ModePicker(mode: DarkMode, onSelect: (DarkMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.Small + Dimens.Tiny),
    ) {
        DarkMode.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == mode,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, DarkMode.entries.size),
                label = { Text(stringResource(option.label)) },
            )
        }
    }
}

@Composable
private fun PalettePicker(appearance: Appearance, onSelect: (ThemePalette) -> Unit) {
    Column(Modifier.padding(horizontal = Dimens.Tiny, vertical = Dimens.Small + Dimens.Tiny)) {
        Text(
            text = stringResource(R.string.appearance_theme),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = Dimens.Gutter - Dimens.Tiny),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.Small + Dimens.Tiny)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ThemePalette.entries.forEach { palette ->
                Swatch(
                    palette = palette,
                    dark = appearance.isDark(),
                    selected = palette == appearance.palette,
                    onSelect = { onSelect(palette) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** A Pixel-style swatch: primary on top, secondary and tertiary below, ringed when selected. */
@Composable
private fun Swatch(
    palette: ThemePalette,
    dark: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Light: deep primary over pale accents. Dark: the pastel accents themselves, which read
    // better than tone-30 containers on a dark card.
    val colors = remember(palette, dark, context) {
        val scheme = palette.scheme(context, dark)
        if (dark) {
            listOf(scheme.primary, scheme.secondary, scheme.tertiary)
        } else {
            listOf(scheme.primary, scheme.secondaryContainer, scheme.tertiaryContainer)
        }
    }
    val ring = MaterialTheme.colorScheme.primary
    val inset by animateDpAsState(if (selected) RingWidth + RingGap else 0.dp, label = "swatchInset")
    Column(
        modifier = modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = Dimens.Tiny),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(Dimens.Swatch)
                .then(if (selected) Modifier.border(RingWidth, ring, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(Dimens.Swatch - inset * 2)) {
                val arc = Size(size.width, size.height)
                drawArc(colors[0], HALF_TURN, HALF_TURN, useCenter = true, size = arc, topLeft = Offset.Zero)
                drawArc(colors[1], QUARTER_TURN, QUARTER_TURN, useCenter = true, size = arc, topLeft = Offset.Zero)
                drawArc(colors[2], 0f, QUARTER_TURN, useCenter = true, size = arc, topLeft = Offset.Zero)
            }
        }
        Text(
            text = stringResource(palette.label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = Dimens.Small),
        )
    }
}
