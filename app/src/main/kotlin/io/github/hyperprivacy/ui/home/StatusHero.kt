package io.github.hyperprivacy.ui.home

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.ModuleState
import io.github.hyperprivacy.ui.components.countString

private const val SUBTITLE_ALPHA = 0.72f
private const val ICON_BACKGROUND_ALPHA = 0.12f

private data class HeroStyle(val container: Color, val content: Color, val icon: ImageVector, @StringRes val title: Int)

@Composable
private fun heroStyle(module: ModuleState): HeroStyle {
    val colors = MaterialTheme.colorScheme
    return when (module) {
        ModuleState.ACTIVE -> HeroStyle(
            container = colors.primaryContainer,
            content = colors.onPrimaryContainer,
            icon = Icons.Filled.CheckCircle,
            title = R.string.module_active,
        )

        ModuleState.CHECKING -> HeroStyle(
            container = colors.surfaceContainerHigh,
            content = colors.onSurface,
            icon = Icons.Filled.Info,
            title = R.string.module_checking,
        )

        ModuleState.NEEDS_REBOOT -> attention(colors, Icons.Filled.Refresh, R.string.module_reboot)

        ModuleState.INACTIVE -> attention(colors, Icons.Filled.Warning, R.string.module_inactive)

        ModuleState.UNAVAILABLE -> attention(colors, Icons.Filled.Warning, R.string.module_unavailable)
    }
}

/** States that need the user to do something use the tertiary container: noticeable, not alarming. */
private fun attention(colors: ColorScheme, icon: ImageVector, @StringRes title: Int) =
    HeroStyle(colors.tertiaryContainer, colors.onTertiaryContainer, icon, title)

@Composable
private fun heroDetail(module: ModuleState, enabledCount: Int, totalHits: Long): String = when (module) {
    ModuleState.ACTIVE -> if (enabledCount == 0) {
        stringResource(R.string.module_no_rules)
    } else {
        countString(R.plurals.summary_enabled, enabledCount.toLong()) +
            stringResource(R.string.common_separator) +
            countString(R.plurals.summary_blocked, totalHits)
    }

    ModuleState.NEEDS_REBOOT -> stringResource(R.string.module_reboot_hint)

    ModuleState.INACTIVE -> stringResource(R.string.module_inactive_hint)

    ModuleState.UNAVAILABLE -> stringResource(R.string.module_unavailable_hint)

    ModuleState.CHECKING -> stringResource(R.string.module_checking_hint)
}

/** Module state at a glance: one title, one line of detail, and the scope reminder. */
@Composable
internal fun StatusHero(module: ModuleState, enabledCount: Int, totalHits: Long, modifier: Modifier = Modifier) {
    val style = heroStyle(module)
    val container by animateColorAsState(style.container, label = "heroContainer")
    val content by animateColorAsState(style.content, label = "heroContent")
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(Dimens.CornerHero),
        color = container,
        contentColor = content,
    ) {
        Column(
            modifier = Modifier.animateContentSize().padding(Dimens.Gutter + Dimens.Tiny),
            verticalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
            ) {
                HeroIcon(style.icon, content)
                Column {
                    Text(stringResource(style.title), style = MaterialTheme.typography.titleLarge)
                    Text(heroDetail(module, enabledCount, totalHits), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                text = stringResource(R.string.xposed_description),
                style = MaterialTheme.typography.bodyMedium,
                color = content.copy(alpha = SUBTITLE_ALPHA),
            )
        }
    }
}

@Composable
private fun HeroIcon(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(Dimens.HeroIcon)
            .clip(CircleShape)
            .background(tint.copy(alpha = ICON_BACKGROUND_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusHeroActivePreview() {
    HyperPrivacyTheme { StatusHero(ModuleState.ACTIVE, enabledCount = 12, totalHits = 3482) }
}

@Preview(showBackground = true)
@Composable
private fun StatusHeroInactivePreview() {
    HyperPrivacyTheme { StatusHero(ModuleState.INACTIVE, enabledCount = 0, totalHits = 0) }
}
