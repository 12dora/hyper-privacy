package io.github.hyperprivacy.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.config.ProtectionPause
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.components.AppIcons
import io.github.hyperprivacy.ui.components.GroupedSurface
import io.github.hyperprivacy.ui.components.IconBadge
import kotlinx.coroutines.delay

private const val TICK_MS = 15_000L
private const val PREVIEW_PAUSE_MS = 12 * 60_000L

/** Material's dialog inset, so title and options line up with other dialogs. */
private val DialogPadding = 24.dp

/** The wall clock, refreshed while [active], so the remaining time of a pause counts down. */
@Composable
internal fun rememberNow(active: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            now = System.currentTimeMillis()
            delay(TICK_MS)
        }
    }
    return now
}

private fun ProtectionPause.Option.labelRes(): Int = when (this) {
    ProtectionPause.Option.QUARTER_HOUR -> R.string.pause_15m
    ProtectionPause.Option.HOUR -> R.string.pause_1h
    ProtectionPause.Option.UNTIL_RESUMED -> R.string.pause_manual
}

/** The banner shown while protection is paused: what is happening, how long, and the way back. */
@Composable
internal fun PausedRow(until: Long, now: Long, onResume: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val minutes = ProtectionPause.remainingMinutes(until, now)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.TallRowMinHeight)
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(AppIcons.ShieldPaused, colors.tertiaryContainer, colors.onTertiaryContainer)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(
                text = stringResource(R.string.pause_active),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                text = if (minutes == null) {
                    stringResource(R.string.pause_until_resumed)
                } else {
                    pluralStringResource(R.plurals.pause_remaining, minutes, minutes)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = onResume) { Text(stringResource(R.string.pause_resume)) }
    }
}

/** The "pause" action at the end of the status row. */
@Composable
internal fun PauseButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(R.string.pause_action)) }
}

/**
 * Asks how long to pause; each option applies at once. The options form one rounded group, like the
 * app's lists (each whole row is the target), with only "cancel" below them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PauseDialog(onChoose: (ProtectionPause.Option) -> Unit, onDismiss: () -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            // Scrolls at large font scales or in landscape, so cancel and the last option stay reachable.
            Column(Modifier.verticalScroll(rememberScrollState()).padding(top = DialogPadding, bottom = Dimens.Tiny)) {
                Text(
                    text = stringResource(R.string.pause_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = DialogPadding).semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.pause_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = DialogPadding, end = DialogPadding, top = Dimens.Small),
                )
                val options = ProtectionPause.Option.entries
                // Inset so the option text lines up with the title.
                Column(Modifier.padding(start = Dimens.Small, end = Dimens.Small, top = Dimens.Gutter)) {
                    options.forEachIndexed { index, option ->
                        GroupedSurface(index, options.size, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Text(
                                text = stringResource(option.labelRes()),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button) { onChoose(option) }
                                    .heightIn(min = Dimens.RowMinHeight)
                                    .wrapContentHeight(Alignment.CenterVertically)
                                    .padding(horizontal = Dimens.Gutter),
                            )
                        }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End).padding(end = Dimens.Small + Dimens.Tiny),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PausedRowPreview() {
    HyperPrivacyTheme {
        val now = System.currentTimeMillis()
        Column {
            PausedRow(now + PREVIEW_PAUSE_MS, now, onResume = {})
            PausedRow(ProtectionPause.UNTIL_MANUAL, now, onResume = {})
        }
    }
}
