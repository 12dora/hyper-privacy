package io.github.hyperprivacy.core.config

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.tracker.AppDefaults
import io.github.hyperprivacy.core.tracker.TrackerCategory
import io.github.hyperprivacy.core.tracker.Trackers

/** The JSON form of a [ConfigBackup]: writing it, and reading a file the user picked. */
internal object BackupJson {
    /**
     * Most entries read from the whole file (rule switches, per-app choices, allowlist entries),
     * and most apps; a real configuration is far smaller. Everything an import writes ends up in
     * the preferences every hooked process and system_server load, so a file cannot grow them.
     */
    private const val MAX_ENTRIES = 4_000
    private const val MAX_APPS = 500
    private const val MAX_ALLOWLIST = 512
    private const val MAX_VERSION = 32
    private val APPEARANCE_NAME = Regex("[A-Z][A-Z0-9_]{0,31}")

    fun encode(backup: ConfigBackup, moduleVersion: String): String {
        val doc = LinkedHashMap<String, Any?>()
        doc["format"] = ConfigBackup.FORMAT
        doc["schema"] = ConfigBackup.SCHEMA
        doc["module"] = moduleVersion.filter { it.isLetterOrDigit() || it in "._+-" }.take(MAX_VERSION)
        doc["rules"] = backup.rules
        doc["apps"] = backup.apps.mapValues { (_, app) -> appJson(app) }
        doc["appList"] = LinkedHashMap<String, Any?>().apply {
            backup.appList.hideSelf?.let { put("hideSelf", it) }
            backup.appList.allow?.let { put("allow", it) }
        }
        backup.defaults?.let { doc["defaults"] = defaultsJson(it) }
        doc["appearance"] = LinkedHashMap<String, Any?>().apply {
            backup.appearance.mode?.let { put("mode", it) }
            backup.appearance.palette?.let { put("palette", it) }
            backup.appearance.pureBlack?.let { put("pureBlack", it) }
        }
        return MiniJson.write(doc)
    }

    private fun appJson(app: AppBackup): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        if (app.protect) put("protect", true)
        if (app.hide) put("hide", true)
        if (app.strict) put("strict", true)
        if (app.sdk.isNotEmpty()) put("sdk", app.sdk.toSortedMap())
        if (app.rules.isNotEmpty()) put("rules", app.rules.toSortedMap())
    }

    /** The values that differ from the built-in ones; an empty section still resets them on import. */
    private fun defaultsJson(defaults: AppDefaults): Map<String, Any?> {
        val stored = AppDefaults.read(defaults.entries())
        return linkedMapOf(
            "sdk" to stored.sdk.mapKeys { it.key.name }.toSortedMap(),
            "rules" to stored.rules.toSortedMap(),
            "hide" to stored.hide,
            "strict" to stored.strict,
        )
    }

    fun parse(text: String): ParsedBackup {
        val root = MiniJson.parse(text) as? Map<*, *> ?: return ParsedBackup.Rejected(BackupProblem.NOT_A_BACKUP)
        if (root["format"] != ConfigBackup.FORMAT) return ParsedBackup.Rejected(BackupProblem.NOT_A_BACKUP)
        val schema = root["schema"] as? Long
        if (schema == null || schema < 1) return ParsedBackup.Rejected(BackupProblem.NOT_A_BACKUP)
        if (schema > ConfigBackup.SCHEMA) return ParsedBackup.Rejected(BackupProblem.NEWER_VERSION)
        return ParsedBackup.Ok(Reader().read(root))
    }

    /** Reads one document, counting what it skips. */
    private class Reader {
        private var ignored = 0
        private var budget = MAX_ENTRIES

        /** Takes one entry from the file-wide [budget]; `false` once it is spent. */
        private fun take(): Boolean = (budget > 0).also { if (it) budget-- }

        /** Whether one more app fits, besides [apps] read so far; takes from the budget when it does. */
        private fun hasRoom(apps: Int): Boolean = apps < MAX_APPS && take()

        fun read(root: Map<*, *>): ConfigBackup {
            val rules = booleans(root["rules"], ConfigBackup::isRuleId)
            val apps = LinkedHashMap<String, AppBackup>()
            (root["apps"] as? Map<*, *>)?.entries?.forEach { (key, value) ->
                val pkg = (key as? String)?.takeIf(BackupExtractor::isPackageName)
                val app = value as? Map<*, *>
                if (pkg != null && app != null && hasRoom(apps.size)) apps[pkg] = readApp(app) else ignored++
            }
            return ConfigBackup(
                rules = rules,
                apps = apps,
                appList = readAppList(root["appList"] as? Map<*, *>),
                appearance = readAppearance(root["appearance"] as? Map<*, *>),
                defaults = (root["defaults"] as? Map<*, *>)?.let(::readDefaults),
                ignored = ignored,
            )
        }

        private fun readApp(app: Map<*, *>) = AppBackup(
            protect = app["protect"] == true,
            sdk = booleans(app["sdk"]) { Trackers.byId(it) != null },
            rules = booleans(app["rules"]) { it in Catalog.protectedAppRuleIds },
            hide = app["hide"] == true,
            strict = app["strict"] == true,
        )

        private fun readDefaults(section: Map<*, *>): AppDefaults {
            val sdk = booleans(section["sdk"]) { name -> TrackerCategory.entries.any { it.name == name } }
            return AppDefaults(
                sdk = sdk.mapKeys { (name, _) -> TrackerCategory.valueOf(name) },
                rules = booleans(section["rules"]) { it in Catalog.protectedAppRuleIds },
                hide = section["hide"] == true,
                strict = section["strict"] == true,
            )
        }

        private fun readAppList(section: Map<*, *>?): AppListBackup {
            val allow = (section?.get("allow") as? List<*>)?.let { list ->
                list.take(MAX_ALLOWLIST)
                    .mapNotNull { (it as? String)?.takeIf { name -> BackupExtractor.isPackageName(name) && take() } }
                    .also { ignored += list.size - it.size }
            }
            return AppListBackup(hideSelf = section?.get("hideSelf") as? Boolean, allow = allow)
        }

        private fun readAppearance(section: Map<*, *>?) = AppearanceBackup(
            mode = name(section?.get("mode")),
            palette = name(section?.get("palette")),
            pureBlack = section?.get("pureBlack") as? Boolean,
        )

        private fun name(value: Any?): String? = (value as? String)?.takeIf(APPEARANCE_NAME::matches)

        /** Boolean entries of [section] whose key passes [valid]; the others count as ignored. */
        private fun booleans(section: Any?, valid: (String) -> Boolean): Map<String, Boolean> {
            val entries = (section as? Map<*, *>)?.entries ?: return emptyMap()
            val result = LinkedHashMap<String, Boolean>()
            entries.forEach { (key, value) ->
                val name = (key as? String)?.takeIf(valid)
                if (name != null && value is Boolean && take()) result[name] = value else ignored++
            }
            return result
        }
    }
}
