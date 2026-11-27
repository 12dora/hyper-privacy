package io.github.hyperprivacy.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.conflict.OverlapTarget
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.tracker.Trackers
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.LocalLanguageTag
import io.github.hyperprivacy.ui.SUSPECT_PREFIX
import io.github.hyperprivacy.ui.components.MonoText

/** Apps listed before "show all"; a declared scope can name many. */
private const val FIRST_TARGETS = 3

/**
 * The apps both modules act on in one area, from the other module's declared scope: per app its
 * label and package, then this module's rules (each opens its detail page, where it can be turned
 * off) or, for a protected app, its options that are on (opening the app's page). Long lists show
 * the first few and "show all". An empty list says the declared apps have nothing of this module on.
 */
@Composable
internal fun TargetList(
    key: String,
    targets: List<OverlapTarget>,
    allApps: Boolean,
    labels: Map<String, String>,
    actions: ConflictActions,
) {
    var all by rememberSaveable(key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = Dimens.Small)) {
        Text(
            text = stringResource(if (allApps) R.string.conflict_targets_all else R.string.conflict_targets_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (targets.isEmpty()) {
            Text(
                text = stringResource(R.string.conflict_targets_none_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Dimens.Tiny),
            )
            return@Column
        }
        val shown = if (all) targets else targets.take(FIRST_TARGETS)
        shown.forEach { target -> Target(target, labels[target.packageName], actions) }
        val hidden = targets.size - shown.size
        if (hidden > 0) {
            TextButton(onClick = { all = true }) {
                Text(pluralStringResource(R.plurals.conflict_targets_more, targets.size, targets.size))
            }
        }
    }
}

@Composable
private fun Target(target: OverlapTarget, label: String?, actions: ConflictActions) {
    val language = LocalLanguageTag.current
    Column(Modifier.fillMaxWidth().padding(top = Dimens.Small)) {
        Text(
            text = label ?: target.packageName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // system_server has no package of its own to show.
        if (label != null && target.packageName != Catalog.SYSTEM_SCOPE) MonoText(target.packageName)
        target.rules.forEach { rule ->
            ItemRow(rule.title.resolve(language)) { actions.onOpenRule(rule.id) }
        }
        // Options in system_server are switched in Settings (self-hiding) or on each app's page.
        val system = target.packageName == Catalog.SYSTEM_SCOPE
        target.options.forEach { option ->
            ItemRow(optionName(option, language)) {
                if (system) actions.onOpenSettings() else actions.onOpenApp(target.packageName, true)
            }
        }
    }
}

/** An option by id: an SDK, a protected-app rule, a suspected component, app-list or self hiding. */
@Composable
private fun optionName(id: String, language: String): String = when {
    id == OverlapTarget.HIDE_APPS -> stringResource(R.string.app_hide_apps)

    id == OverlapTarget.HIDE_SELF -> stringResource(R.string.settings_self_hide)

    id.startsWith(SUSPECT_PREFIX) ->
        stringResource(R.string.sdk_suspect_title) + " " + id.removePrefix(SUSPECT_PREFIX).trimEnd('.')

    else -> Trackers.byId(id)?.displayName?.resolve(language)
        ?: Catalog.protectedAppRules.firstOrNull { it.id == id }?.title?.resolve(language)
        ?: id
}

/** One rule or option that is on: a full-width 48dp row opening where it can be turned off. */
@Composable
private fun ItemRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.TouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = Dimens.Small),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
