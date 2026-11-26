package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.health.TelemetryHeuristics
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.IdentifierSource
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.scope.DeviceCoverage
import io.github.hyperprivacy.core.scope.PackageRules
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.system.SettingsDenyList
import io.github.hyperprivacy.core.system.SettingsKeyMatcher
import io.github.hyperprivacy.core.system.SettingsObservation
import io.github.hyperprivacy.core.system.SettingsTable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsKeysTest {
    private val deny = SettingsDenyList.matcher

    @Test
    fun `matcher handles exact names and trailing-star prefixes`() {
        val matcher = SettingsKeyMatcher(listOf("a_id", "rule_*"))
        assertTrue(matcher.matches("a_id"))
        assertTrue(matcher.matches("rule_"))
        assertTrue(matcher.matches("rule_123"))
        assertFalse(matcher.matches("a_id_2"))
        assertFalse(matcher.matches("x_a_id"))
        assertFalse(matcher.matches("rule"))
        assertFalse(matcher.matches("A_ID"))
        assertFalse(matcher.matches(""))
        assertFalse(matcher.matches(null))
    }

    @Test
    fun `matcher rejects match-all and inner wildcards`() {
        assertThrows<IllegalArgumentException> { SettingsKeyMatcher(listOf("*")) }
        assertThrows<IllegalArgumentException> { SettingsKeyMatcher(listOf("")) }
        assertThrows<IllegalArgumentException> { SettingsKeyMatcher(listOf("a*b")) }
    }

    @Test
    fun `deny list covers the device findings`() {
        listOf(
            "ad_aaid", "mi_health_id", "key_mqs_uuid", "pref_mipub_random_device_id",
            "com.miui.yellowpage_preferences.pref_uuid", "green_kid_device_id", "last_synced_account",
            "virtualsim_account_rule_1234567890", "antispam_sync_token", "account_msg_unread",
            "cloud_msg_account_1234", "com.xiaomi.market_need_update_app_packages_list", "miui_home_hide_app_list",
            "notificationbar_launch_list", "agent_sign_x",
        ).forEach { assertTrue(deny.matches(it), it) }
    }

    @Test
    fun `deny list never hides android_id or AOSP keys apps rely on`() {
        listOf(
            "android_id", "adb_enabled", "development_settings_enabled", "airplane_mode_on", "auto_time",
            "screen_brightness", "font_scale", "accessibility_enabled", "enabled_accessibility_services",
            "default_input_method", "bluetooth_name", "device_name", "time_12_24", "location_mode",
            "install_non_market_apps", "transition_animation_scale", "force_fsg_nav_bar", "ui_night_mode",
            "account", "virtualsim_account_rule",
        ).forEach { assertFalse(deny.matches(it), it) }
    }

    @Test
    fun `settings table is decoded from the state key`() {
        fun makeKey(type: Int, user: Int, device: Int): Long =
            user.toLong() or (device.toLong() shl 32) or (type.toLong() shl 28)
        assertEquals(SettingsTable.GLOBAL, SettingsTable.ofStateKey(makeKey(0, 0, 0)))
        assertEquals(SettingsTable.SYSTEM, SettingsTable.ofStateKey(makeKey(1, 10, 0)))
        assertEquals(SettingsTable.SECURE, SettingsTable.ofStateKey(makeKey(2, 0, 3)))
        assertNull(SettingsTable.ofStateKey(makeKey(3, 0, 0)), "SSAID")
        assertNull(SettingsTable.ofStateKey(makeKey(4, 0, 0)), "config")
    }

    @Test
    fun `observations fit the observed endpoint format unchanged`() {
        SettingsTable.entries.forEach { table ->
            val host = SettingsObservation.host(table)
            assertTrue(ObservedEndpoint.isValidHost(host), host)
            assertTrue(SettingsObservation.isSettingsHost(host))
        }
        listOf("key_mqs_uuid", "a/b/c", "x?y#z", "tab\there", "comma,key", "", "k".repeat(500)).forEach { key ->
            val path = SettingsObservation.path(key)
            assertEquals(path, ObservedEndpoint.pathPrefixOf(path), key)
            assertTrue(path.length <= ObservedEndpoint.MAX_PATH_LENGTH)
        }
        assertEquals("/key_mqs_uuid", SettingsObservation.path("key_mqs_uuid"))
        assertFalse(SettingsObservation.isSettingsHost("settings.example.com"))
        assertFalse(SettingsObservation.isSettingsHost("tracking.miui.com"))
    }

    @Test
    fun `settings reads are never suspected reporting endpoints`() {
        val read = ObservedEndpoint("com.example", "settings.secure", "/upload_log_stat", 1, 1, 1, blocked = false)
        val traffic = read.copy(host = "log.example.com")
        assertEquals(listOf(traffic), TelemetryHeuristics.suspected(listOf(read, traffic)))
    }

    @Test
    fun `settings rule is a default-on system_server identifier rule`() {
        val rule = assertNotNull(Catalog.byId("settings.identifiers"))
        assertEquals(setOf(Catalog.SYSTEM_SCOPE), rule.packages)
        assertEquals(RulePayload.Identifiers(IdentifierSource.SETTINGS_KEYS), rule.payload)
        assertEquals(Category.DEVICE_ID, rule.category)
        assertTrue(rule.defaultOn)
        assertEquals(listOf(rule), PackageRules.catalog.inProcess(Catalog.SYSTEM_SCOPE))
    }

    @Test
    fun `system scope rules always apply and use the framework version`() {
        val rule = assertNotNull(Catalog.byId("settings.identifiers"))
        assertEquals(listOf(rule), DeviceCoverage.applicableRules(listOf(rule), emptySet()))
        assertEquals(listOf(Catalog.SYSTEM_SCOPE), DeviceCoverage.installedTargets(rule, emptySet()))
        assertEquals(DeviceCoverage.FRAMEWORK_PACKAGE, DeviceCoverage.versionPackage(Catalog.SYSTEM_SCOPE))
        assertEquals("com.miui.home", DeviceCoverage.versionPackage("com.miui.home"))
        assertFalse(Catalog.SYSTEM_SCOPE in DeviceCoverage.missingScope(emptySet(), listOf(Catalog.SYSTEM_SCOPE)))
    }
}
