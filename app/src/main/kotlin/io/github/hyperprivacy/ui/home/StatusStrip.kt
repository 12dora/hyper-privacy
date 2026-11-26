package io.github.hyperprivacy.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.ModuleState
import io.github.hyperprivacy.ui.components.countString

private data class StripStyle(val icon: ImageVector, val tint: Color, @StringRes val title: Int)

@Composable
private fun stripStyle(module: ModuleState): StripStyle {
    val colors = MaterialTheme.colorScheme
    return when (module) {
        ModuleState.ACTIVE -> StripStyle(Icons.Filled.CheckCircle, colors.primary, R.string.module_active)
        ModuleState.CHECKING -> StripStyle(Icons.Filled.Info, colors.onSurfaceVariant, R.string.module_checking)
        ModuleState.NEEDS_REBOOT -> StripStyle(Icons.Filled.Warning, colors.error, R.string.module_reboot)
        ModuleState.INACTIVE -> StripStyle(Icons.Filled.Warning, colors.error, R.string.module_inactive)
        ModuleState.UNAVAILABLE -> StripStyle(Icons.Filled.Warning, colors.error, R.string.module_unavailable)
    }
}

/** What follows the state: the numbers when working, otherwise the short action the user should take. */
@Composable
private fun stripDetail(module: ModuleState, enabledCount: Int, totalHits: Long): String? = when (module) {
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

    ModuleState.CHECKING -> null
}

/**
 * Module state in a single compact row. Only the state word is a live region, so TalkBack
 * announces state changes but not every change of the blocked count.
 */
@Composable
internal fun StatusStrip(module: ModuleState, enabledCount: Int, totalHits: Long, modifier: Modifier = Modifier) {
    val style = stripStyle(module)
    val detail = stripDetail(module, enabledCount, totalHits)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.CornerOuter),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = Dimens.RowMinHeight)
                .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Small + Dimens.Tiny),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(style.icon, contentDescription = null, tint = style.tint, modifier = Modifier.size(Dimens.StatusIcon))
            Text(
                text = stringResource(style.title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (detail != null) {
                Text(
                    text = stringResource(R.string.common_separator).trimStart() + detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusStripActivePreview() {
    HyperPrivacyTheme { StatusStrip(ModuleState.ACTIVE, enabledCount = 3, totalHits = 125) }
}

@Preview(showBackground = true)
@Composable
private fun StatusStripInactivePreview() {
    HyperPrivacyTheme { StatusStrip(ModuleState.INACTIVE, enabledCount = 0, totalHits = 0) }
}
