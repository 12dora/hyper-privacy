package io.github.hyperprivacy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.status.DisplayStatus

private val ScreenPadding = 16.dp
private val ItemSpacing = 12.dp
private val CardPadding = 16.dp
private val TextSpacing = 2.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreen(state: UiState, onToggle: (String, Boolean) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = ScreenPadding,
                end = ScreenPadding,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + ScreenPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(ItemSpacing),
        ) {
            item { StatusCard(state.module) }
            items(state.groups, key = { it.key }) { group ->
                RuleGroupCard(group, enabled = state.module is ModuleState.Active, onToggle = onToggle)
            }
        }
    }
}

@Composable
private fun StatusCard(module: ModuleState) {
    val active = module is ModuleState.Active
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active) colors.primaryContainer else colors.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(TextSpacing)) {
            Text(
                text = stringResource(if (active) R.string.status_active else R.string.status_inactive),
                style = MaterialTheme.typography.titleMedium,
            )
            val detail = when (module) {
                is ModuleState.Active ->
                    stringResource(
                        R.string.status_active_hint,
                        module.frameworkName,
                        module.frameworkVersion,
                        module.apiVersion,
                    )

                ModuleState.Inactive -> stringResource(R.string.status_inactive_hint)
            }
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.xposed_description), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RuleGroupCard(group: RuleGroup, enabled: Boolean, onToggle: (String, Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = ItemSpacing)) {
            Text(
                text = group.label ?: stringResource(R.string.group_multiple),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = CardPadding),
            )
            group.rules.forEach { rule -> RuleRow(rule, enabled, onToggle) }
        }
    }
}

@Composable
private fun RuleRow(rule: RuleItem, enabled: Boolean, onToggle: (String, Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardPadding, vertical = ItemSpacing / 2),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TextSpacing)) {
            Text(rule.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                rule.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rule.enabled) RuleStatusLine(rule)
        }
        Switch(checked = rule.enabled, enabled = enabled, onCheckedChange = { onToggle(rule.id, it) })
    }
}

@Composable
private fun RuleStatusLine(rule: RuleItem) {
    val (text, color) = when (rule.status) {
        DisplayStatus.ACTIVE -> stringResource(R.string.rule_active) to MaterialTheme.colorScheme.primary
        DisplayStatus.MISSED -> stringResource(R.string.rule_missed) to MaterialTheme.colorScheme.error
        DisplayStatus.NOT_RUN -> stringResource(R.string.rule_not_run) to MaterialTheme.colorScheme.outline
    }
    val count = rule.hits.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val hits = if (count > 0) " · " + pluralStringResource(R.plurals.rule_hits, count, count) else ""
    Text(text + hits, style = MaterialTheme.typography.labelSmall, color = color)
}
