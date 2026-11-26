package io.github.hyperprivacy.core.config

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.system.AppListConfig
import io.github.hyperprivacy.core.tracker.TrackerProtection

/** Appearance as stored by the app (plain names; the app validates them against its own enums). */
public data class AppearanceBackup(val mode: String?, val palette: String?, val pureBlack: Boolean?) {
    public val isEmpty: Boolean get() = mode == null && palette == null && pureBlack == null
}

/** One app's options in a [ConfigBackup]; every field is optional. */
public data class AppBackup(
    /** Tracker protection is on for the app. The scan result is not part of a backup. */
    val protect: Boolean = false,
    /** The user's choice per tracker SDK id. */
    val sdk: Map<String, Boolean> = emptyMap(),
    /** The user's choice per app-level rule id ([Catalog.protectedAppRuleIds]). */
    val rules: Map<String, Boolean> = emptyMap(),
    val hide: Boolean = false,
    val strict: Boolean = false,
) {
    val isEmpty: Boolean get() = !protect && sdk.isEmpty() && rules.isEmpty() && !hide && !strict
}

/** App-list settings besides the per-app ones; `null` means "not stored", i.e. the default. */
public data class AppListBackup(val hideSelf: Boolean?, val allow: List<String>?) {
    public val isEmpty: Boolean get() = hideSelf == null && allow == null
}

/** What the user is shown before an import overwrites anything. */
public data class BackupSummary(
    val rules: Int,
    val apps: Int,
    val protectedApps: Int,
    val hasAppList: Boolean,
    val hasAppearance: Boolean,
    /** Entries in the file this version does not understand; they are skipped. */
    val ignored: Int,
)

/** Preference writes an import performs; [removes] are applied before [puts]. */
public data class PrefsPlan(
    val puts: Map<String, Any>,
    val removes: Set<String>,
    /** Apps whose protection could not be restored (not installed here, or not scanned). */
    val skippedProtection: Int,
)

/** Why a file cannot be imported. */
public enum class BackupProblem { NOT_A_BACKUP, NEWER_VERSION }

/** The outcome of [ConfigBackup.parse]: a backup, or the [BackupProblem] that stops it. */
public sealed interface ParsedBackup {
    public class Ok(public val backup: ConfigBackup) : ParsedBackup

    public class Rejected(public val problem: BackupProblem) : ParsedBackup
}

/**
 * The user's configuration as a portable file: rule switches, per-app protection and SDK
 * choices, app-list hiding settings and appearance. JSON, one `schema` version, unknown keys
 * ignored on import.
 *
 * Deliberately not part of it: the module secret, diagnostics mode, the pause deadline, scan
 * results, observed endpoints and any status data, and device identifiers (none are ever read).
 * Everything is picked from the preferences by an explicit key whitelist, so a key added to the
 * preferences later is never exported by accident.
 *
 * An import replaces what the file covers (rule switches, per-app options, app-list settings) and
 * only ever adds protected apps ([plan]): turning protection off also leaves the LSPosed scope
 * behind, which is the user's call to make on the Apps page.
 */
public class ConfigBackup(
    public val rules: Map<String, Boolean> = emptyMap(),
    public val apps: Map<String, AppBackup> = emptyMap(),
    public val appList: AppListBackup = AppListBackup(null, null),
    public val appearance: AppearanceBackup = AppearanceBackup(null, null, null),
    /** Entries skipped while reading (unknown ids, wrong types, over the limits). */
    public val ignored: Int = 0,
) {
    public val summary: BackupSummary
        get() = BackupSummary(
            rules = rules.size,
            apps = apps.count { !it.value.isEmpty },
            protectedApps = apps.count { it.value.protect },
            hasAppList = !appList.isEmpty,
            hasAppearance = !appearance.isEmpty,
            ignored = ignored,
        )

    /**
     * The preference writes that make [current] match this backup. [detected] gives the SDK ids of
     * a protected app's scan (`null`: not installed or not scanned, so protection is skipped and
     * counted).
     */
    public fun plan(current: Map<String, *>, detected: (String) -> Set<String>?): PrefsPlan {
        val puts = LinkedHashMap<String, Any>()
        rules.forEach { (id, on) -> puts[ConfigCodec.key(id)] = on }
        appList.hideSelf?.let { puts[AppListConfig.SELF_HIDE_KEY] = it }
        appList.allow?.let { puts[AppListConfig.ALLOW_KEY] = AppListConfig.encodeAllowlist(it) }
        var skipped = 0
        apps.forEach { (pkg, app) ->
            if (app.hide) puts[AppListConfig.hideKey(pkg)] = true
            if (app.strict) puts[AppListConfig.strictKey(pkg)] = true
            (app.sdk + app.rules).forEach { (id, on) -> puts[TrackerProtection.blockKey(pkg, id)] = on }
            val key = TrackerProtection.protectKey(pkg)
            if (!app.protect || key in current) return@forEach
            val ids = detected(pkg)
            if (ids == null) skipped++ else puts[key] = TrackerProtection.encodeDetected(ids)
        }
        val removes = current.keys.filterTo(LinkedHashSet(), ::isManagedKey)
        removes.removeAll(puts.keys)
        return PrefsPlan(puts, removes, skipped)
    }

    public companion object {
        public const val FORMAT: String = "hyperprivacy-config"
        public const val SCHEMA: Int = 1

        /** Rule ids a backup may carry: the catalog's and the protected-app rules'. */
        internal fun isRuleId(id: String): Boolean = Catalog.byId(id) != null || id in Catalog.protectedAppRuleIds

        /** Preference keys an import replaces wholesale; protect keys are only ever added. */
        private fun isManagedKey(key: String): Boolean {
            val ruleId = ConfigCodec.ruleIdOf(key)
            return (ruleId != null && isRuleId(ruleId)) || key == AppListConfig.ALLOW_KEY ||
                key == AppListConfig.SELF_HIDE_KEY || listOf(
                    TrackerProtection.BLOCK_PREFIX,
                    AppListConfig.HIDE_PREFIX,
                    AppListConfig.STRICT_PREFIX,
                ).any(key::startsWith)
        }

        /** The backup of the preferences [raw] (the whitelisted keys only), with [appearance] as the app stores it. */
        public fun of(raw: Map<String, *>, appearance: AppearanceBackup): ConfigBackup =
            BackupExtractor.extract(raw, appearance)

        /** The JSON file text of [backup], stamped with the [moduleVersion] that wrote it. */
        public fun encode(backup: ConfigBackup, moduleVersion: String): String =
            BackupJson.encode(backup, moduleVersion)

        /** Reads [text]; unknown keys and invalid entries are skipped and counted, never fatal. */
        public fun parse(text: String): ParsedBackup = BackupJson.parse(text)
    }
}
