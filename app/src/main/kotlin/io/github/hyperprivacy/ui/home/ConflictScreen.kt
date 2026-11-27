package io.github.hyperprivacy.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.conflict.OverlapArea
import io.github.hyperprivacy.ui.ConflictModule
import io.github.hyperprivacy.ui.ConflictUi
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.apps.EmptyText
import io.github.hyperprivacy.ui.apps.Note
import io.github.hyperprivacy.ui.components.BackButton
import io.github.hyperprivacy.ui.components.MonoText
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.components.listPadding

/** What overlapping in [this] means, and the usual way out; data, so every area reads the same. */
private class AreaCopy(@StringRes val symptom: Int, @StringRes val fix: Int)

private fun OverlapArea.copy(): AreaCopy = when (this) {
    OverlapArea.APP_LIST -> AreaCopy(R.string.conflict_applist_body, R.string.conflict_fix_applist_body)
    OverlapArea.ADS -> AreaCopy(R.string.conflict_ads_body, R.string.conflict_fix_rules_body)
    OverlapArea.PRIVACY -> AreaCopy(R.string.conflict_privacy_body, R.string.conflict_fix_rules_body)
    OverlapArea.DEVICE_ID -> AreaCopy(R.string.conflict_device_body, R.string.conflict_fix_rules_body)
}

/**
 * The overlap hint in full: for each module found on this device, its own label and package, then
 * one row per area it overlaps in with the likely symptom, a suggestion, and the way to the matching
 * switches of this module. Other modules are never changed. Without data (dismissed meanwhile, or
 * nothing usable found) the page says so instead of showing anything partial.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConflictScreen(
    conflict: ConflictUi?,
    canEdit: Boolean,
    actions: ConflictActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val modules = conflict?.modules.orEmpty()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.conflict_detail_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = { BackButton(actions.onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding(inner)) {
            if (modules.isEmpty()) {
                item(key = "empty") { EmptyText(stringResource(R.string.conflict_detail_empty)) }
                return@LazyColumn
            }
            item(key = "intro") { Note(R.string.conflict_detail_intro_body) }
            modules.forEach { module ->
                moduleSection(module, canDisableAppList = canEdit && conflict?.canDisableAppList == true, actions)
            }
        }
    }
}

private fun LazyListScope.moduleSection(module: ConflictModule, canDisableAppList: Boolean, actions: ConflictActions) {
    item(key = "module/${module.packageName}") { ModuleHeader(module) }
    groupedItems(module.areas, key = { "module/${module.packageName}/$it" }) { area ->
        AreaRow(area, canDisableAppList = canDisableAppList && area == OverlapArea.APP_LIST, actions)
    }
}

/** The module as the device names it, with its package (labels can repeat, packages cannot). */
@Composable
private fun ModuleHeader(module: ConflictModule) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Gutter)
            .padding(top = Dimens.GroupGap + Dimens.Small, bottom = Dimens.Small)
            .semantics(mergeDescendants = true) { heading() },
    ) {
        Text(
            text = module.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        MonoText(module.packageName, Modifier.padding(top = Dimens.RowGap))
    }
}

@Composable
private fun AreaRow(area: OverlapArea, canDisableAppList: Boolean, actions: ConflictActions) {
    val copy = area.copy()
    Column(Modifier.fillMaxWidth().padding(start = Dimens.Gutter, end = Dimens.Small, top = Dimens.RowVertical)) {
        Column(
            modifier = Modifier.padding(end = Dimens.Small).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(Dimens.Tiny),
        ) {
            Text(stringResource(area.labelRes()), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(copy.symptom),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(copy.fix),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Small, Alignment.End),
        ) {
            TextButton(onClick = { actions.onOpenRules(area.category) }) {
                Text(stringResource(R.string.conflict_open_rules))
            }
            if (canDisableAppList) {
                FilledTonalButton(onClick = actions.onDisableAppList) {
                    Text(stringResource(R.string.conflict_disable_applist))
                }
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun ConflictScreenPreview() {
    HyperPrivacyTheme {
        val conflict = ConflictUi.of(
            listOf(
                ConflictModule("com.example.hide", "Example Module", listOf(OverlapArea.APP_LIST)),
                ConflictModule("com.example.clean", "Cleaner", listOf(OverlapArea.ADS, OverlapArea.DEVICE_ID)),
            ),
            appListHidingOn = true,
        )
        ConflictScreen(conflict, canEdit = true, actions = ConflictActions({}, {}, {}))
    }
}
