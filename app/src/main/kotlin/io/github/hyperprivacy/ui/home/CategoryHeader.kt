package io.github.hyperprivacy.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.ui.CategoryGroup
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.components.IconBadge
import io.github.hyperprivacy.ui.components.icon
import io.github.hyperprivacy.ui.components.labelRes

private const val EXPANDED_ROTATION = 180f

/** A bulk switch the user asked for and has yet to confirm. */
internal data class BulkToggle(val category: Category, val enable: Boolean)

/** Keeps a pending bulk request across rotation; `null` (no request) is simply not saved. */
internal val BulkToggleSaver = Saver<BulkToggle?, List<Any>>(
    save = { request -> request?.let { listOf(it.category.name, it.enable) } },
    restore = { saved -> BulkToggle(Category.valueOf(saved[0] as String), saved[1] as Boolean) },
)

/**
 * The first row of a category card: icon, name, "3/4 on", and a chevron. The whole row folds the
 * card; the overflow menu (only while unfolded, to keep the folded overview quiet) switches all.
 */
@Composable
internal fun CategoryHeader(
    group: CategoryGroup,
    expanded: Boolean,
    canEdit: Boolean,
    onToggleExpanded: () -> Unit,
    onBulk: (BulkToggle) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val name = stringResource(group.category.labelRes())
    val rotation by animateFloatAsState(if (expanded) EXPANDED_ROTATION else 0f, label = "categoryChevron")
    val stateLabel = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    val separator = stringResource(R.string.common_separator)
    val enabled = stringResource(R.string.category_enabled, group.enabledCount, group.entries.size)
    val attention = group.attentionCount.takeIf { it > 0 }?.let {
        pluralStringResource(R.plurals.app_attention, it, it)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.TallRowMinHeight)
            .clickable(role = Role.Button, onClick = onToggleExpanded)
            .semantics {
                heading()
                stateDescription = stateLabel
            }
            .padding(start = Dimens.Gutter, end = Dimens.Tiny, top = Dimens.RowVertical, bottom = Dimens.RowVertical),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(group.category.icon(), colors.secondaryContainer, colors.onSecondaryContainer)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = buildAnnotatedString {
                    append(enabled)
                    if (attention != null) {
                        append(separator)
                        withStyle(SpanStyle(color = colors.tertiary)) { append(attention) }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        if (expanded && canEdit) BulkMenu(onBulk = { onBulk(BulkToggle(group.category, it)) })
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier
                .padding(end = Dimens.Small)
                .rotate(rotation),
        )
    }
}

@Composable
private fun BulkMenu(onBulk: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.action_more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.category_enable_all)) },
                onClick = {
                    open = false
                    onBulk(true)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.category_disable_all)) },
                onClick = {
                    open = false
                    onBulk(false)
                },
            )
        }
    }
}

/** A rule with a side effect that is not a high-risk leak: bulk "turn on" leaves it to a deliberate choice. */
private val Rule.skippedByBulkEnable: Boolean get() = impact != null && !highRisk

private fun targetsSkipped(group: CategoryGroup): Int =
    group.entries.count { entry -> !entry.item.enabled && entry.item.rule.skippedByBulkEnable }

/**
 * Confirms a bulk switch. Turning on skips rules with a side effect unless they are high-risk leaks:
 * those stay a deliberate, one-by-one choice. [onConfirm] receives the rule ids to switch.
 */
@Composable
internal fun BulkToggleDialog(
    request: BulkToggle,
    group: CategoryGroup,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val name = stringResource(request.category.labelRes())
    val targets = group.entries.map { it.item }.filter { item ->
        item.enabled != request.enable && (!request.enable || !item.rule.skippedByBulkEnable)
    }
    val skipped = if (request.enable) targetsSkipped(group) else 0
    // High-risk rules are turned on despite their side effect; say so rather than hide it.
    val sideEffects = if (request.enable) targets.count { it.rule.impact != null } else 0
    val base = when {
        !request.enable -> pluralStringResource(R.plurals.category_disable_body, targets.size, targets.size)
        skipped > 0 -> pluralStringResource(R.plurals.category_enable_body_impact, targets.size, targets.size, skipped)
        else -> pluralStringResource(R.plurals.category_enable_body, targets.size, targets.size)
    }
    val body = if (sideEffects > 0) {
        base + "\n" + pluralStringResource(R.plurals.category_enable_side_effects, sideEffects, sideEffects)
    } else {
        base
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (request.enable) R.string.category_enable_title else R.string.category_disable_title,
                    name,
                ),
            )
        },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onConfirm(targets.map { it.id }) }, enabled = targets.isNotEmpty()) {
                Text(
                    stringResource(
                        if (request.enable) R.string.category_enable_confirm else R.string.category_disable_confirm,
                    ),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
