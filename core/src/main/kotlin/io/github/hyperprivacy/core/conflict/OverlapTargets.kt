package io.github.hyperprivacy.core.conflict

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Rule

/**
 * One app where another module and this one both act in one [OverlapArea]: this module's enabled
 * built-in rules there ([rules], for apps the catalog covers), or its options for a protected app
 * ([options], ids the app resolves to names: SDK ids, protected-app rule ids, [HIDE_APPS]).
 */
public data class OverlapTarget(val packageName: String, val rules: List<Rule>, val options: List<String>) {
    public companion object {
        /** The option id of a protected app's app-list hiding. */
        public const val HIDE_APPS: String = "applist.hide"
    }
}

/**
 * What this module does, as far as an overlap is concerned: its enabled built-in rules, and for
 * each protected app the options that are on, by the area they belong to.
 */
public class OurSide(
    public val enabledRules: List<Rule>,
    public val protectedOptions: Map<String, Map<OverlapArea, List<String>>>,
)

/** Intersects another module's [DeclaredScope] with [OurSide], per area. Pure; bounded by the inputs. */
public object OverlapTargets {
    /** system_server as module scopes name it. */
    private val SYSTEM_NAMES = setOf("android", "system")

    /**
     * The apps where a module with [scope] and this one both act in [area]; `null` when the scope
     * is unknown (the page then explains the overlap at area level only). An all-apps scope lists
     * the protected apps only: every built-in rule would otherwise match, which says nothing.
     */
    public fun of(scope: DeclaredScope, area: OverlapArea, ours: OurSide): List<OverlapTarget>? {
        val packages = when (scope) {
            DeclaredScope.Unknown -> return null
            DeclaredScope.AllApps -> null
            is DeclaredScope.Apps -> scope.packages
        }
        val rulesByApp = LinkedHashMap<String, MutableList<Rule>>()
        if (packages != null) {
            ours.enabledRules.filter { area.covers(it.category) && !it.isProtectedAppRule }.forEach { rule ->
                rule.packages.forEach { target ->
                    val name = when (target) {
                        Catalog.SYSTEM_SCOPE -> SYSTEM_NAMES.firstOrNull { it in packages }
                        else -> target
                    }
                    if (name != null && name in packages) rulesByApp.getOrPut(name) { ArrayList() } += rule
                }
            }
        }
        val options = ours.protectedOptions
            .filterKeys { packages == null || it in packages }
            .mapNotNull { (pkg, byArea) -> byArea[area]?.takeIf { it.isNotEmpty() }?.let { pkg to it } }
            .toMap()
        val apps = (rulesByApp.keys + options.keys).sorted()
        return apps.map { pkg ->
            OverlapTarget(pkg, rulesByApp[pkg].orEmpty().distinctBy { it.id }, options[pkg].orEmpty().distinct())
        }
    }
}
