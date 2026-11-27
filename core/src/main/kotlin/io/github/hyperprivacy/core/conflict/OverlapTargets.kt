package io.github.hyperprivacy.core.conflict

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Rule

/**
 * One app where another module and this one both act in one [OverlapArea]: this module's enabled
 * built-in rules there ([rules], for apps the catalog covers), or its options for that app
 * ([options], ids the app resolves to names: SDK ids, protected-app rule ids, suspected-component
 * ids, [HIDE_APPS], [HIDE_SELF]). system_server is always [Catalog.SYSTEM_SCOPE], whatever name
 * the other module's scope uses.
 */
public data class OverlapTarget(val packageName: String, val rules: List<Rule>, val options: List<String>) {
    public companion object {
        /** An app's app-list hiding (on an app) or, on system_server, that some app hides the list. */
        public const val HIDE_APPS: String = "applist.hide"

        /** Hiding this module from other apps (enforced in system_server). */
        public const val HIDE_SELF: String = "applist.self"
    }
}

/**
 * What this module does, as far as an overlap is concerned: its enabled built-in rules, per app the
 * options that are on ([appOptions]: protected apps' SDKs and rules, and app-list hiding of any app)
 * and what it does in system_server itself ([systemOptions]), each by the area it belongs to.
 */
public class OurSide(
    public val enabledRules: List<Rule>,
    public val appOptions: Map<String, Map<OverlapArea, List<String>>>,
    public val systemOptions: Map<OverlapArea, List<String>> = emptyMap(),
)

/** Intersects another module's [DeclaredScope] with [OurSide], per area. Pure; bounded by the inputs. */
public object OverlapTargets {
    /** system_server as module scopes name it. */
    private val SYSTEM_NAMES = setOf("android", Catalog.SYSTEM_SCOPE)

    /**
     * The apps where a module with [scope] and this one both act in [area]; `null` when the scope
     * is unknown (the page then explains the overlap at area level only). An all-apps scope lists
     * the apps with options of this module (and system_server): every built-in rule would
     * otherwise match, which says nothing.
     */
    public fun of(scope: DeclaredScope, area: OverlapArea, ours: OurSide): List<OverlapTarget>? {
        val packages = when (scope) {
            DeclaredScope.Unknown -> return null
            DeclaredScope.AllApps -> null
            is DeclaredScope.Apps -> scope.packages.mapTo(HashSet(), ::normalize)
        }
        val rules = if (packages == null) emptyMap() else rulesIn(packages, area, ours.enabledRules)
        val options = optionsIn(packages, area, ours)
        return (rules.keys + options.keys).sorted().map { pkg ->
            OverlapTarget(pkg, rules[pkg].orEmpty().distinctBy { it.id }, options[pkg].orEmpty().distinct())
        }
    }

    private fun normalize(name: String) = if (name in SYSTEM_NAMES) Catalog.SYSTEM_SCOPE else name

    /** [enabled] rules of [area] by the [packages] they target (protected-app rules are per app options). */
    private fun rulesIn(packages: Set<String>, area: OverlapArea, enabled: List<Rule>): Map<String, List<Rule>> {
        val byApp = LinkedHashMap<String, MutableList<Rule>>()
        enabled.filter { area.covers(it.category) && !it.isProtectedAppRule }.forEach { rule ->
            rule.packages.filter { it in packages }.forEach { byApp.getOrPut(it) { ArrayList() } += rule }
        }
        return byApp
    }

    /** Options of [area] by app, system_server included; [packages] `null` means every app. */
    private fun optionsIn(packages: Set<String>?, area: OverlapArea, ours: OurSide): Map<String, List<String>> {
        val covered = { pkg: String -> packages == null || pkg in packages }
        val options = HashMap<String, List<String>>()
        ours.appOptions.forEach { (pkg, byArea) ->
            val list = byArea[area].orEmpty()
            if (list.isNotEmpty() && covered(pkg)) options[pkg] = list
        }
        val system = ours.systemOptions[area].orEmpty()
        if (system.isNotEmpty() && covered(Catalog.SYSTEM_SCOPE)) options[Catalog.SYSTEM_SCOPE] = system
        return options
    }
}
