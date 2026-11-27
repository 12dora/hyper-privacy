package io.github.hyperprivacy.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.system.AppListConfig
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.components.AppIcon
import io.github.hyperprivacy.ui.components.BackButton
import io.github.hyperprivacy.ui.components.SearchField
import io.github.hyperprivacy.ui.components.SingleCard
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.components.listPadding

/** One installed app on the allowlist page. */
private class AllowRow(val packageName: String, val label: String, val allowed: Boolean, val builtIn: Boolean)

/**
 * The apps restricted apps still see ([AppListConfig.ALLOW_KEY]), so sharing, sign-in and payment
 * keep working; strict mode hides them too. Installed apps with a checkbox each, checked ones first;
 * the built-in entries are marked, and "reset" goes back to them. Entries for apps not installed
 * here are kept as they are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AllowlistScreen(
    state: AppsUi,
    canEdit: Boolean,
    actions: AllowlistActions,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val search = AppsSearch(active = true, query = query)
    // Checked apps first, as they were when the page opened: a tap never makes the row jump away.
    val opened = remember(state.loaded) { state.allowlist }
    val rows = remember(state.apps, state.allowlist, query) {
        state.apps.filter(search::matches)
            .sortedWith(compareBy<UserAppUi> { it.packageName !in opened }.thenBy { it.label.lowercase() })
            .map { AllowRow(it.packageName, it.label, it.packageName in state.allowlist, it.packageName in BUILT_IN) }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.apps_allow_title)) },
                navigationIcon = { BackButton(actions.onBack) },
                actions = {
                    if (state.allowlistCustom) {
                        TextButton(onClick = actions.onReset, enabled = canEdit) {
                            Text(stringResource(R.string.apps_allow_reset))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = listPadding(inner)) {
            item(key = "note") { Note(R.string.apps_allow_note) }
            item(key = "search") {
                SingleCard(Modifier.padding(bottom = Dimens.GroupGap)) {
                    Row(Modifier.padding(horizontal = Dimens.Gutter).heightIn(min = Dimens.RowMinHeight)) {
                        SearchField(query, { query = it }, focus, R.string.apps_search_hint)
                    }
                }
            }
            when {
                !state.loaded -> Unit

                rows.isEmpty() -> item(key = "empty") { EmptyText(stringResource(R.string.apps_search_empty)) }

                else -> groupedItems(rows, key = { "allow/${it.packageName}" }) { row ->
                    AllowAppRow(row, canEdit) { actions.onToggle(row.packageName, it) }
                }
            }
        }
    }
}

private val BUILT_IN: Set<String> get() = AppListConfig.DEFAULT_ALLOWLIST

@Composable
private fun AllowAppRow(row: AllowRow, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowMinHeight)
            .toggleable(value = row.allowed, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle)
            .padding(start = Dimens.Gutter, end = Dimens.Small, top = Dimens.Tiny, bottom = Dimens.Tiny),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(row.packageName, Dimens.AppIcon)
        Column(Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.builtIn) {
                Text(
                    text = stringResource(R.string.apps_allow_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Checkbox(checked = row.allowed, onCheckedChange = null, enabled = enabled)
    }
}

@Preview(showBackground = true, heightDp = 640)
@Composable
private fun AllowlistScreenPreview() {
    HyperPrivacyTheme {
        val apps = sampleApps()
        AllowlistScreen(
            state = apps.copy(allowlist = setOf(apps.apps.first().packageName)),
            canEdit = true,
            actions = AllowlistActions({ _, _ -> }, {}, {}),
        )
    }
}
