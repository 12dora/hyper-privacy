package io.github.hyperprivacy.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.health.Health
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.ui.AppStatus
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.LocalLanguageTag
import io.github.hyperprivacy.ui.ModuleState
import io.github.hyperprivacy.ui.RuleItem
import io.github.hyperprivacy.ui.components.AppIcon
import io.github.hyperprivacy.ui.components.BackButton
import io.github.hyperprivacy.ui.components.IconBadge
import io.github.hyperprivacy.ui.components.MonoText
import io.github.hyperprivacy.ui.components.SectionHeader
import io.github.hyperprivacy.ui.components.SingleCard
import io.github.hyperprivacy.ui.components.SwitchRow
import io.github.hyperprivacy.ui.components.descriptionRes
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.components.labelRes
import io.github.hyperprivacy.ui.components.listPadding
import io.github.hyperprivacy.ui.components.tint
import io.github.hyperprivacy.ui.components.titleRes
import io.github.hyperprivacy.ui.home.statusText
import io.github.hyperprivacy.ui.needsAttention
import io.github.hyperprivacy.ui.sampleItem

private const val EXPANDED_ROTATION = 180f
private const val COLLAPSED_APPS = 5

/** Everything about one rule: what it blocks, how, which apps it touches and where it came from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleDetailScreen(
    item: RuleItem?,
    module: ModuleState,
    onToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        if (item == null) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.detail_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            var showAllApps by rememberSaveable(item.id) { mutableStateOf(false) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding(inner)) {
                detailItems(item, module, onToggle, showAllApps) { showAllApps = true }
            }
        }
    }
}

private fun LazyListScope.detailItems(
    item: RuleItem,
    module: ModuleState,
    onToggle: (Boolean) -> Unit,
    showAllApps: Boolean,
    onShowAllApps: () -> Unit,
) {
    val rule = item.rule
    item(key = "header") { Header(rule) }
    item(key = "enable") { EnableCard(item, module, onToggle) }
    item.attention?.let { health -> item(key = "health") { HealthCard(health) } }
    rule.impact?.let { impact ->
        item(key = "impact-title") { SectionHeader(stringResource(R.string.detail_section_impact)) }
        item(key = "impact") {
            SingleCard {
                Column(
                    Modifier.padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical + Dimens.Tiny),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Tiny),
                ) {
                    Text(
                        text = impact.resolve(LocalLanguageTag.current),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (rule.highRisk) {
                        Text(
                            text = stringResource(R.string.detail_high_risk),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
    item(key = "method-title") { SectionHeader(stringResource(R.string.detail_section_method)) }
    item(key = "method") { MethodCard(rule) }
    item(key = "apps-title") { SectionHeader(stringResource(R.string.detail_section_apps)) }
    appRows(item, showAllApps, onShowAllApps)
    item(key = "evidence") { EvidenceCard(rule.evidence, Modifier.padding(top = Dimens.GroupGap + Dimens.Small)) }
}

@Composable
private fun Header(rule: Rule) {
    val tag = LocalLanguageTag.current
    Column(Modifier.padding(horizontal = Dimens.Gutter, vertical = Dimens.Small)) {
        Text(rule.title.resolve(tag), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = rule.summary.resolve(tag),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Dimens.Tiny),
        )
    }
}

@Composable
private fun EnableCard(item: RuleItem, module: ModuleState, onToggle: (Boolean) -> Unit) {
    SingleCard(Modifier.padding(top = Dimens.Small)) {
        SwitchRow(
            title = stringResource(R.string.detail_enable),
            checked = item.enabled,
            enabled = module.canEdit,
            onCheckedChange = onToggle,
            summary = if (item.enabled) statusText(item.health, item.status, item.hits) else null,
        )
    }
}

@Composable
private fun MethodCard(rule: Rule) {
    val targets = rule.blockedTargets()
    SingleCard {
        Column(
            Modifier.padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical + Dimens.Tiny),
            verticalArrangement = Arrangement.spacedBy(Dimens.Tiny),
        ) {
            Text(stringResource(rule.kind.titleRes()), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(rule.kind.descriptionRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (targets.isNotEmpty()) {
                Column(Modifier.padding(top = Dimens.Tiny), verticalArrangement = Arrangement.spacedBy(Dimens.Tiny)) {
                    targets.forEach { MonoText(it) }
                }
            }
        }
    }
}

/**
 * Long app lists (a shared SDK can span 100 apps) show the first few, then a "show all" row. Targets
 * not installed here are only counted in a quiet footnote: they cannot matter on this device.
 */
private fun LazyListScope.appRows(item: RuleItem, showAll: Boolean, onShowAll: () -> Unit) {
    val collapsed = !showAll && item.apps.size > COLLAPSED_APPS + 1
    val shown = if (collapsed) item.apps.take(COLLAPSED_APPS) else item.apps
    val rows: List<AppStatus?> = if (collapsed) shown + null else shown
    groupedItems(rows, key = { it?.let { app -> "app/${app.packageName}" } ?: "app/more" }) { app ->
        if (app == null) {
            ShowAllRow(item.apps.size, onShowAll)
        } else {
            AppRow(app, item.enabled)
        }
    }
    val missing = item.notInstalledCount
    if (missing > 0) {
        item(key = "apps-not-installed") {
            Text(
                text = pluralStringResource(R.plurals.detail_not_installed, missing, missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = Dimens.Small),
            )
        }
    }
}

@Composable
private fun ShowAllRow(count: Int, onClick: () -> Unit) {
    Text(
        text = pluralStringResource(R.plurals.detail_show_all_apps, count, count),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .wrapContentHeight()
            .padding(horizontal = Dimens.Gutter),
    )
}

/** Why the rule needs attention, in one sentence, right under its switch. */
@Composable
private fun HealthCard(health: Health) {
    val colors = MaterialTheme.colorScheme
    val urgent = health == Health.BROKEN
    SingleCard(Modifier.padding(top = Dimens.Small)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.TallRowMinHeight)
                .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical)
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon = Icons.Outlined.Warning,
                container = if (urgent) colors.errorContainer else colors.tertiaryContainer,
                content = if (urgent) colors.onErrorContainer else colors.onTertiaryContainer,
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(health.labelRes()), style = MaterialTheme.typography.bodyLarge)
                health.descriptionRes()?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppStatus, ruleEnabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowMinHeight)
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, Dimens.AppIcon)
        Text(app.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (ruleEnabled) {
            val issue = app.health.takeIf { it.needsAttention }
            Text(
                text = issue?.let { stringResource(it.labelRes()) } ?: statusText(app.health, app.status, app.hits),
                style = MaterialTheme.typography.labelMedium,
                color = issue?.tint() ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Collapsed by default; the source of a rule is for people who want to verify it. */
@Composable
private fun EvidenceCard(evidence: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.detail_section_evidence)
    val stateLabel = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    val rotation by animateFloatAsState(if (expanded) EXPANDED_ROTATION else 0f, label = "evidenceChevron")
    SingleCard(modifier) {
        Column(Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.RowMinHeight)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.detail_evidence_toggle)) {
                        expanded = !expanded
                    }
                    .semantics { stateDescription = stateLabel }
                    .padding(horizontal = Dimens.Gutter, vertical = Dimens.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.rotate(rotation),
                )
            }
            if (expanded) {
                Text(
                    text = evidence,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = Dimens.Gutter,
                        end = Dimens.Gutter,
                        bottom = Dimens.RowVertical,
                    ),
                )
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun RuleDetailPreview() {
    HyperPrivacyTheme { RuleDetailScreen(sampleItem(), ModuleState.ACTIVE, {}, {}) }
}
