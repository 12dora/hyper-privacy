package io.github.hyperprivacy.ui

import io.github.hyperprivacy.core.rule.Category

/** Rules touching at most this many apps are listed under each app; broader ones go to the common group. */
private const val MAX_APPS_PER_RULE = 3

private const val COMMON_KEY = "a:*"

/** An installed target app. */
internal data class AppLabel(val packageName: String, val label: String)

private fun RuleItem.entry(meta: RowMeta) = RuleEntry(this, status, hits, meta)

private fun RuleItem.categoryMeta(): RowMeta = when (apps.size) {
    0 -> RowMeta.None
    1 -> RowMeta.App(apps.first().label)
    else -> RowMeta.Apps(apps.size)
}

/** One group per category, in declaration order; empty categories are dropped. */
internal fun groupByCategory(items: List<RuleItem>): List<RuleGroup> = Category.entries.mapNotNull { category ->
    val entries = items.filter { it.rule.category == category }.map { it.entry(it.categoryMeta()) }
    entries.takeIf { it.isNotEmpty() }?.let { RuleGroup("c:${category.name}", GroupHeader.OfCategory(category), it) }
}

/**
 * Common group first, then one group per app in [appOrder]. A rule shows up under each of its apps
 * unless it covers more than [MAX_APPS_PER_RULE], in which case it is listed once as common.
 */
internal fun groupByApp(items: List<RuleItem>, appOrder: List<AppLabel>): List<RuleGroup> {
    val (narrow, broad) = items.partition { it.apps.size <= MAX_APPS_PER_RULE }
    val common = broad.map { it.entry(RowMeta.Apps(it.apps.size)) }
    val perApp = appOrder.mapNotNull { app ->
        val entries = narrow.mapNotNull { item ->
            item.apps.firstOrNull { it.packageName == app.packageName }
                ?.let { scoped -> RuleEntry(item, scoped.status, scoped.hits, RowMeta.None) }
        }
        entries.takeIf { it.isNotEmpty() }
            ?.let { RuleGroup("a:${app.packageName}", GroupHeader.OfApp(app.packageName, app.label), it) }
    }
    val commonGroup = common.takeIf { it.isNotEmpty() }?.let { RuleGroup(COMMON_KEY, GroupHeader.Common, it) }
    return listOfNotNull(commonGroup) + perApp
}
