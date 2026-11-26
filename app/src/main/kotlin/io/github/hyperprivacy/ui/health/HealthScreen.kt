package io.github.hyperprivacy.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.health.Health
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.LocalLanguageTag
import io.github.hyperprivacy.ui.ObservedEndpointUi
import io.github.hyperprivacy.ui.RuleItem
import io.github.hyperprivacy.ui.UiState
import io.github.hyperprivacy.ui.components.AppIcons
import io.github.hyperprivacy.ui.components.BackButton
import io.github.hyperprivacy.ui.components.IconBadge
import io.github.hyperprivacy.ui.components.MonoText
import io.github.hyperprivacy.ui.components.NavRow
import io.github.hyperprivacy.ui.components.SectionHeader
import io.github.hyperprivacy.ui.components.SingleCard
import io.github.hyperprivacy.ui.components.countString
import io.github.hyperprivacy.ui.components.descriptionRes
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.components.labelRes
import io.github.hyperprivacy.ui.components.listPadding

private const val MAX_APP_NAMES = 2
private const val MAX_SUSPECTED = 50
private val ISSUE_ORDER = listOf(Health.BROKEN, Health.NEEDS_REVIEW, Health.IDLE)

/**
 * Rules that stopped working or may be stale, worst first, plus reporting endpoints no rule covers.
 * The report export sits on top: it is what a user acts on after reading this page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HealthScreen(
    state: UiState,
    onOpenRule: (String) -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = onExport) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.health_export))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        val issues = state.rules.filter { it.attention != null }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding(inner)) {
            val okCount = state.rules.count { it.enabled } - issues.size
            item(key = "summary") { Summary(okCount, allOk = issues.isEmpty()) }
            ISSUE_ORDER.forEach { health ->
                issueSection(health, issues.filter { it.attention == health }, onOpenRule)
            }
            suspectedSection(state.suspected)
            item(key = "export-gap") { SectionHeader(stringResource(R.string.settings_diagnostics)) }
            item(key = "export") { ExportCard(onExport) }
        }
    }
}

@Composable
private fun Summary(okCount: Int, allOk: Boolean) {
    val colors = MaterialTheme.colorScheme
    SingleCard {
        StaticRow(
            title = if (allOk) {
                stringResource(R.string.health_all_ok)
            } else {
                pluralStringResource(R.plurals.health_ok_count, okCount, okCount)
            },
        ) {
            IconBadge(AppIcons.Shield, colors.primaryContainer, colors.onPrimaryContainer)
        }
    }
}

/** A static row with a leading badge and a title. */
@Composable
private fun StaticRow(title: String, leading: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.TallRowMinHeight)
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

private fun LazyListScope.issueSection(health: Health, rules: List<RuleItem>, onOpenRule: (String) -> Unit) {
    if (rules.isEmpty()) return
    item(key = "issue-title/$health") { SectionHeader(stringResource(health.labelRes())) }
    groupedItems(rules, key = { "issue/$health/${it.id}" }) { item -> IssueRow(item, health, onOpenRule) }
}

@Composable
private fun IssueRow(item: RuleItem, health: Health, onOpenRule: (String) -> Unit) {
    val tag = LocalLanguageTag.current
    val affected = item.apps.filter { it.health == health }.map { it.label }
    val names = affected.take(MAX_APP_NAMES).joinToString("、")
    val apps = if (affected.size > MAX_APP_NAMES) {
        names + stringResource(R.string.common_separator) + pluralStringResource(
            R.plurals.meta_apps,
            affected.size,
            affected.size,
        )
    } else {
        names
    }
    val description = health.descriptionRes()?.let { stringResource(it) }
    NavRow(
        title = item.rule.title.resolve(tag),
        summary = listOfNotNull(apps.ifEmpty { null }, description).joinToString("\n"),
        onClick = { onOpenRule(item.id) },
        leading = {
            val colors = MaterialTheme.colorScheme
            val urgent = health == Health.BROKEN
            IconBadge(
                icon = Icons.Outlined.Warning,
                container = if (urgent) colors.errorContainer else colors.tertiaryContainer,
                content = if (urgent) colors.onErrorContainer else colors.onTertiaryContainer,
            )
        },
    )
}

private fun LazyListScope.suspectedSection(endpoints: List<ObservedEndpointUi>) {
    if (endpoints.isEmpty()) return
    val shown = endpoints.take(MAX_SUSPECTED)
    item(key = "suspected-title") { SectionHeader(stringResource(R.string.health_section_suspected)) }
    groupedItems(shown, key = { "suspected/${it.appLabel}/${it.host}/${it.path.orEmpty()}" }) { SuspectedRow(it) }
    item(key = "suspected-note") {
        Text(
            text = stringResource(R.string.health_suspected_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = Dimens.Small),
        )
    }
}

@Composable
private fun SuspectedRow(endpoint: ObservedEndpointUi) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowMinHeight)
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical),
    ) {
        MonoText(endpoint.host + endpoint.path.orEmpty())
        Text(
            text = endpoint.appLabel + stringResource(R.string.common_separator) +
                countString(R.plurals.endpoint_count, endpoint.count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ExportCard(onExport: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    SingleCard {
        NavRow(
            title = stringResource(R.string.health_export),
            summary = stringResource(R.string.health_export_summary),
            onClick = onExport,
            leading = { IconBadge(AppIcons.Report, colors.secondaryContainer, colors.onSecondaryContainer) },
        )
    }
}
