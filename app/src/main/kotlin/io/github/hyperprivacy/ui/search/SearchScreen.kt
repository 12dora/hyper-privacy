package io.github.hyperprivacy.ui.search

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.AppSummary
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.LocalLanguageTag
import io.github.hyperprivacy.ui.RowMeta
import io.github.hyperprivacy.ui.RuleEntry
import io.github.hyperprivacy.ui.UiState
import io.github.hyperprivacy.ui.components.BackButton
import io.github.hyperprivacy.ui.components.SectionHeader
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.components.listPadding
import io.github.hyperprivacy.ui.home.AppSummaryRow
import io.github.hyperprivacy.ui.home.RuleRow

private class SearchResults(val apps: List<AppSummary>, val rules: List<RuleEntry>)

/** Case-insensitive match on what the user can see (titles, summaries, app names) plus package names. */
private fun search(state: UiState, query: String, languageTag: String): SearchResults {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return SearchResults(emptyList(), emptyList())
    fun String.hit() = lowercase().contains(needle)
    val apps = state.apps.filter { it.label.hit() || it.packageName.hit() }
    val rules = state.rules.filter { item ->
        val rule = item.rule
        rule.title.resolve(languageTag).hit() || rule.summary.resolve(languageTag).hit() ||
            item.apps.any { it.label.hit() || it.packageName.hit() }
    }.map { item ->
        val meta = if (item.apps.size == 1) RowMeta.App(item.apps.first().label) else RowMeta.Apps(item.apps.size)
        RuleEntry(item, meta, item.health)
    }
    return SearchResults(apps, rules)
}

/** Search across apps and rules. Results update as the user types; the query survives navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScreen(
    state: UiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onOpenRule: (String) -> Unit,
    onOpenApp: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tag = LocalLanguageTag.current
    val results = remember(state.rules, state.apps, query, tag) { search(state, query, tag) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { SearchField(query, onQueryChange, focus) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = listPadding(inner)) {
            if (query.isNotBlank() && results.apps.isEmpty() && results.rules.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimens.Gutter * 2),
                    )
                }
            }
            if (results.apps.isNotEmpty()) {
                item(key = "apps-title") { SectionHeader(stringResource(R.string.search_apps)) }
                groupedItems(results.apps, key = { "app/${it.packageName}" }) { app ->
                    AppSummaryRow(app, onOpen = { onOpenApp(app.packageName) })
                }
            }
            if (results.rules.isNotEmpty()) {
                item(key = "rules-title") { SectionHeader(stringResource(R.string.search_rules)) }
                groupedItems(results.rules, key = { "rule/${it.item.id}" }) { entry ->
                    RuleRow(
                        entry = entry,
                        switchEnabled = state.module.canEdit,
                        onToggle = { onToggleRule(entry.item.id, it) },
                        onOpenDetails = { onOpenRule(entry.item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, focus: FocusRequester) {
    val keyboard = LocalSoftwareKeyboardController.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                }
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
    )
}
