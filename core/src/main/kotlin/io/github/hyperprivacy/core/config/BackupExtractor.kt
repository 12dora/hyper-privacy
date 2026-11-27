package io.github.hyperprivacy.core.config

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.system.AppListConfig
import io.github.hyperprivacy.core.tracker.AppDefaults
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.core.tracker.Trackers

/** Picks the whitelisted keys of the live preferences into a [ConfigBackup]. */
internal object BackupExtractor {
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** Android's own limit on a package name's length. */
    private const val MAX_PACKAGE_LENGTH = 255

    fun isPackageName(name: String): Boolean = name.length <= MAX_PACKAGE_LENGTH && PACKAGE_NAME.matches(name)

    fun extract(raw: Map<String, *>, appearance: AppearanceBackup): ConfigBackup {
        val rules = sortedMapOf<String, Boolean>()
        val apps = HashMap<String, AppBackup>()
        raw.forEach { (key, value) ->
            val ruleId = ConfigCodec.ruleIdOf(key)
            if (ruleId != null && ConfigBackup.isRuleId(ruleId) && value is Boolean) rules[ruleId] = value
            appEdit(key, value)?.let { (pkg, change) ->
                if (isPackageName(pkg)) apps[pkg] = change(apps[pkg] ?: AppBackup())
            }
        }
        return ConfigBackup(
            rules = rules,
            apps = apps.filterValues { !it.isEmpty }.toSortedMap(),
            appList = AppListBackup(
                hideSelf = raw[AppListConfig.SELF_HIDE_KEY] as? Boolean,
                allow = AppListConfig.parseAllowlist(raw[AppListConfig.ALLOW_KEY])?.toList(),
            ),
            appearance = appearance,
            defaults = AppDefaults.read(raw),
        )
    }

    /** The package a per-app key is about, and how it changes that app's [AppBackup]; `null` for other keys. */
    private fun appEdit(key: String, value: Any?): Pair<String, (AppBackup) -> AppBackup>? = when {
        key.startsWith(TrackerProtection.PROTECT_PREFIX) && value is String ->
            key.removePrefix(TrackerProtection.PROTECT_PREFIX) to { it.copy(protect = true) }

        key.startsWith(AppListConfig.HIDE_PREFIX) && value == true ->
            key.removePrefix(AppListConfig.HIDE_PREFIX) to { it.copy(hide = true) }

        key.startsWith(AppListConfig.STRICT_PREFIX) && value == true ->
            key.removePrefix(AppListConfig.STRICT_PREFIX) to { it.copy(strict = true) }

        key.startsWith(TrackerProtection.BLOCK_PREFIX) && value is Boolean -> blockEdit(key, value)

        else -> null
    }

    private fun blockEdit(key: String, value: Boolean): Pair<String, (AppBackup) -> AppBackup>? {
        val rest = key.removePrefix(TrackerProtection.BLOCK_PREFIX)
        // App-level rule ids contain dots, SDK ids do not: match the known rule ids first.
        Catalog.protectedAppRuleIds.firstOrNull { rest.endsWith(".$it") }?.let { id ->
            return rest.removeSuffix(".$id") to { it.copy(rules = it.rules + (id to value)) }
        }
        val id = rest.substringAfterLast('.', "")
        if (Trackers.byId(id) == null) return null
        return rest.removeSuffix(".$id") to { it.copy(sdk = it.sdk + (id to value)) }
    }
}
