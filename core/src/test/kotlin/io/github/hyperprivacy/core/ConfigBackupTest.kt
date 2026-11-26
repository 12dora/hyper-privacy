package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.config.AppearanceBackup
import io.github.hyperprivacy.core.config.BackupProblem
import io.github.hyperprivacy.core.config.ConfigBackup
import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.config.ModuleSecret
import io.github.hyperprivacy.core.config.ParsedBackup
import io.github.hyperprivacy.core.config.ProtectionPause
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.system.AppListConfig
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.core.tracker.Trackers
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigBackupTest {
    private val ruleId = Catalog.rules.first().id
    private val otherRuleId = Catalog.rules.last().id
    private val sdkId = Trackers.all.first().id
    private val appRuleId = Catalog.protectedAppRuleIds.first()
    private val look = AppearanceBackup("DARK", "SEA", true)
    private val secret = "ab".repeat(ModuleSecret.BYTES)

    private val raw: Map<String, Any> = mapOf(
        ConfigCodec.key(ruleId) to false,
        ConfigCodec.key(otherRuleId) to true,
        ConfigCodec.key("gone.rule") to true,
        ConfigCodec.OBSERVE_KEY to true,
        ModuleSecret.KEY to secret,
        ProtectionPause.KEY to ProtectionPause.UNTIL_MANUAL,
        TrackerProtection.protectKey("com.example.app") to "$sdkId,other.sdk",
        TrackerProtection.blockKey("com.example.app", sdkId) to false,
        TrackerProtection.blockKey("com.example.app", appRuleId) to true,
        TrackerProtection.blockKey("com.example.app", "unknown_sdk") to true,
        AppListConfig.hideKey("com.example.app") to true,
        AppListConfig.strictKey("com.example.app") to true,
        AppListConfig.hideKey("com.example.off") to false,
        AppListConfig.SELF_HIDE_KEY to false,
        AppListConfig.ALLOW_KEY to "com.a.one, com.b.two",
        "status.something" to "x",
    )

    private fun export(): String = ConfigBackup.encode(ConfigBackup.of(raw, look), "0.3.0")

    private fun import(text: String): ConfigBackup = assertIs<ParsedBackup.Ok>(ConfigBackup.parse(text)).backup

    @Test
    fun `export keeps the whitelisted settings`() {
        val backup = import(export())
        assertEquals(mapOf(ruleId to false, otherRuleId to true), backup.rules)
        val app = backup.apps.getValue("com.example.app")
        assertTrue(app.protect && app.hide && app.strict)
        assertEquals(mapOf(sdkId to false), app.sdk)
        assertEquals(mapOf(appRuleId to true), app.rules)
        assertFalse("com.example.off" in backup.apps)
        assertEquals(false, backup.appList.hideSelf)
        assertEquals(listOf("com.a.one", "com.b.two"), backup.appList.allow)
        assertEquals(look, backup.appearance)
    }

    @Test
    fun `export never carries the secret, diagnostics, pause, scan results or status`() {
        val text = export()
        assertFalse(secret in text)
        listOf(ModuleSecret.KEY, ConfigCodec.OBSERVE_KEY, ProtectionPause.KEY, "status", "other.sdk", "gone.rule")
            .forEach { assertFalse(it in text, it) }
    }

    @Test
    fun `an empty configuration still round-trips`() {
        val empty = ConfigBackup.of(emptyMap<String, Any>(), AppearanceBackup(null, null, null))
        val backup = import(ConfigBackup.encode(empty, "1"))
        assertTrue(backup.rules.isEmpty() && backup.apps.isEmpty())
        assertEquals(0, backup.summary.rules)
        assertFalse(backup.summary.hasAppList || backup.summary.hasAppearance)
    }

    @Test
    fun `summary counts what the file holds`() {
        val summary = import(export()).summary
        assertEquals(2, summary.rules)
        assertEquals(1, summary.apps)
        assertEquals(1, summary.protectedApps)
        assertTrue(summary.hasAppList && summary.hasAppearance)
        assertEquals(0, summary.ignored)
    }

    @Test
    fun `unknown keys and invalid entries are ignored and counted`() {
        val text = """
            {"format":"hyperprivacy-config","schema":1,"future":{"x":1},
             "rules":{"$ruleId":true,"gone.rule":true,"$otherRuleId":"yes"},
             "apps":{"com.example.app":{"hide":true,"sdk":{"$sdkId":false,"nope":true},"wat":1},"Not A Package":{}},
             "appList":{"allow":["com.ok.app","bad name"]},
             "appearance":{"mode":"dark","palette":"SEA"}}
        """.trimIndent()
        val backup = import(text)
        assertEquals(mapOf(ruleId to true), backup.rules)
        assertEquals(mapOf(sdkId to false), backup.apps.getValue("com.example.app").sdk)
        assertEquals(listOf("com.ok.app"), backup.appList.allow)
        assertNull(backup.appearance.mode)
        assertEquals("SEA", backup.appearance.palette)
        assertEquals(5, backup.ignored)
    }

    @Test
    fun `files that are not a backup are rejected`() {
        val notBackups = listOf(
            "",
            "not json",
            "[]",
            "{}",
            """{"format":"other","schema":1}""",
            """{"format":"hyperprivacy-config"}""",
        )
        notBackups
            .forEach { text ->
                assertEquals(
                    BackupProblem.NOT_A_BACKUP,
                    assertIs<ParsedBackup.Rejected>(ConfigBackup.parse(text)).problem,
                    text,
                )
            }
        val newer = ConfigBackup.parse("""{"format":"hyperprivacy-config","schema":99}""")
        assertEquals(BackupProblem.NEWER_VERSION, assertIs<ParsedBackup.Rejected>(newer).problem)
    }

    @Test
    fun `hostile input is bounded`() {
        assertIs<ParsedBackup.Rejected>(ConfigBackup.parse("[".repeat(200) + "]".repeat(200)))
        assertIs<ParsedBackup.Rejected>(ConfigBackup.parse(" ".repeat(2_000_000)))
        assertIs<ParsedBackup.Rejected>(
            ConfigBackup.parse("""{"format":"hyperprivacy-config","schema":1,"rules":{"a":tru}}"""),
        )
    }

    @Test
    fun `strings with escapes survive`() {
        val backup = ConfigBackup(appearance = AppearanceBackup("DARK", null, null))
        assertEquals("DARK", import(ConfigBackup.encode(backup, "a\"b\\c\n")).appearance.mode)
    }

    @Test
    fun `plan replaces what the file covers and leaves everything else`() {
        val backup = import(
            """{"format":"hyperprivacy-config","schema":1,"rules":{"$ruleId":true},
               "apps":{"com.example.app":{"hide":true,"protect":true,"sdk":{"$sdkId":true}},
                       "com.missing.app":{"protect":true}}}""",
        )
        val current = raw + (TrackerProtection.blockKey("com.stale.app", sdkId) to false)
        val plan = backup.plan(current) { pkg -> setOf(sdkId).takeIf { pkg == "com.example.app" } }
        assertEquals(true, plan.puts[ConfigCodec.key(ruleId)])
        assertEquals(true, plan.puts[AppListConfig.hideKey("com.example.app")])
        assertEquals(true, plan.puts[TrackerProtection.blockKey("com.example.app", sdkId)])
        assertEquals(1, plan.skippedProtection)
        // Replaced: the other rule switch, stale per-app choices and the app-list settings not in the file.
        assertTrue(ConfigCodec.key(otherRuleId) in plan.removes)
        assertTrue(TrackerProtection.blockKey("com.stale.app", sdkId) in plan.removes)
        assertTrue(AppListConfig.SELF_HIDE_KEY in plan.removes && AppListConfig.ALLOW_KEY in plan.removes)
        // Untouched: secret, diagnostics, pause, unknown rule ids, existing protection.
        val untouched = listOf(
            ModuleSecret.KEY,
            ConfigCodec.OBSERVE_KEY,
            ProtectionPause.KEY,
            ConfigCodec.key("gone.rule"),
            TrackerProtection.protectKey("com.example.app"),
            "status.something",
        )
        untouched.forEach { key -> assertFalse(key in plan.removes || key in plan.puts, key) }
    }

    @Test
    fun `plan restores protection with the scan of this device`() {
        val backup = import("""{"format":"hyperprivacy-config","schema":1,"apps":{"com.new.app":{"protect":true}}}""")
        val plan = backup.plan(emptyMap<String, Any>()) { setOf(sdkId, "other.sdk") }
        val expected = TrackerProtection.encodeDetected(setOf(sdkId, "other.sdk"))
        assertEquals(expected, plan.puts[TrackerProtection.protectKey("com.new.app")])
        assertEquals(0, plan.skippedProtection)
    }
}
