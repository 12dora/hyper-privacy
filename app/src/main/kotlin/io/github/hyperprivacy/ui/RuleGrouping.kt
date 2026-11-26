package io.github.hyperprivacy.ui

import io.github.hyperprivacy.core.health.Health
import io.github.hyperprivacy.core.rule.Category

private fun RuleItem.categoryMeta(): RowMeta = when (apps.size) {
    0 -> RowMeta.None
    1 -> RowMeta.App(apps.first().label)
    else -> RowMeta.Apps(apps.size)
}

/** One group per category, in declaration order; empty categories are dropped. */
internal fun groupByCategory(items: List<RuleItem>): List<CategoryGroup> = Category.entries.mapNotNull { category ->
    val entries = items.filter { it.rule.category == category }.map { RuleEntry(it, it.categoryMeta(), it.health) }
    entries.takeIf { it.isNotEmpty() }?.let { CategoryGroup(category, it) }
}

/** One summary per app in [appOrder] that at least one rule targets. */
internal fun summarizeApps(items: List<RuleItem>, appOrder: List<AppLabel>): List<AppSummary> {
    val byPackage = HashMap<String, MutableList<Pair<RuleItem, AppStatus>>>()
    items.forEach { item ->
        item.apps.forEach { byPackage.getOrPut(it.packageName) { mutableListOf() } += item to it }
    }
    return appOrder.mapNotNull { app ->
        val rules = byPackage[app.packageName] ?: return@mapNotNull null
        AppSummary(
            packageName = app.packageName,
            label = app.label,
            ruleCount = rules.size,
            enabledCount = rules.count { (item, _) -> item.enabled },
            attentionCount = rules.count { (item, scoped) -> item.enabled && scoped.health.needsAttention },
        )
    }
}

/** Counts for the Home attention entry. */
internal fun attentionOf(items: List<RuleItem>, suspected: Int): Attention {
    val issues = items.mapNotNull { it.attention }
    return Attention(
        broken = issues.count { it == Health.BROKEN },
        review = issues.count { it == Health.NEEDS_REVIEW },
        idle = issues.count { it == Health.IDLE },
        suspected = suspected,
    )
}
