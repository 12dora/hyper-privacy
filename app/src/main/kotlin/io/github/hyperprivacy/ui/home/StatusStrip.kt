package io.github.hyperprivacy.ui.home

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.ModuleState
import io.github.hyperprivacy.ui.components.countString

private const val MAX_LINES = 2

private data class StripStyle(val icon: ImageVector, val tint: Color)

@Composable
private fun stripStyle(module: ModuleState): StripStyle {
    val colors = MaterialTheme.colorScheme
    return when (module) {
        ModuleState.ACTIVE -> StripStyle(Icons.Filled.CheckCircle, colors.primary)

        ModuleState.CHECKING -> StripStyle(Icons.Filled.Info, colors.onSurfaceVariant)

        ModuleState.NEEDS_REBOOT, ModuleState.INACTIVE, ModuleState.UNAVAILABLE ->
            StripStyle(Icons.Filled.Warning, colors.error)
    }
}

/** One line: the state, followed by either the numbers or the short action the user should take. */
@Composable
private fun stripText(module: ModuleState, enabledCount: Int, totalHits: Long): String {
    val separator = stringResource(R.string.common_separator)
    val parts = when (module) {
        ModuleState.ACTIVE -> listOf(
            stringResource(R.string.module_active),
            if (enabledCount == 0) {
                stringResource(R.string.module_no_rules)
            } else {
                countString(R.plurals.summary_enabled, enabledCount.toLong())
            },
        ) + listOfNotNull(if (enabledCount > 0) countString(R.plurals.summary_blocked, totalHits) else null)

        ModuleState.NEEDS_REBOOT -> listOf(
            stringResource(R.string.module_reboot),
            stringResource(R.string.module_reboot_hint),
        )

        ModuleState.INACTIVE -> listOf(
            stringResource(R.string.module_inactive),
            stringResource(R.string.module_inactive_hint),
        )

        ModuleState.UNAVAILABLE ->
            listOf(stringResource(R.string.module_unavailable), stringResource(R.string.module_unavailable_hint))

        ModuleState.CHECKING -> listOf(stringResource(R.string.module_checking))
    }
    return parts.joinToString(separator)
}

/** Module state in a single compact row. */
@Composable
internal fun StatusStrip(module: ModuleState, enabledCount: Int, totalHits: Long, modifier: Modifier = Modifier) {
    val style = stripStyle(module)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
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
                text = stripText(module, enabledCount, totalHits),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
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
