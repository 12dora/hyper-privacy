package io.github.hyperprivacy.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.AppSummary
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.UiState
import io.github.hyperprivacy.ui.components.AppIcon
import io.github.hyperprivacy.ui.components.BackButton
import io.github.hyperprivacy.ui.components.SectionHeader
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.components.listPadding
import io.github.hyperprivacy.ui.home.RuleRow

private val HeaderIcon = 48.dp

/** Every rule touching one app, including broad ones such as a shared analytics SDK. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppScreen(
    packageName: String,
    state: UiState,
    onToggleRule: (String, Boolean) -> Unit,
    onOpenRule: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = state.app(packageName)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        if (app == null) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.app_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val entries = state.rulesOf(packageName)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding(inner)) {
                item(key = "header") { AppHeader(app) }
                item(key = "rules-title") { SectionHeader(stringResource(R.string.app_section_rules)) }
                groupedItems(entries, key = { "rule/${it.item.id}" }) { entry ->
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
private fun AppHeader(app: AppSummary) {
    Row(
        modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = Dimens.Small),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, HeaderIcon)
        Column {
            Text(
                text = app.label,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = pluralStringResource(R.plurals.app_rule_count, app.ruleCount, app.ruleCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
