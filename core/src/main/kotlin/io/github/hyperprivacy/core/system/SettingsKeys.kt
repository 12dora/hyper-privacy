package io.github.hyperprivacy.core.system

/**
 * Matches Settings key names against exact names and prefix patterns (a pattern ending in `*`).
 *
 * Built for system_server's `SettingsState#getSettingLocked`, which runs for every Settings read
 * on the device: [matches] is one hash lookup plus a scan over the few prefixes and allocates
 * nothing. Matching is case-sensitive, like Settings keys themselves.
 */
public class SettingsKeyMatcher(patterns: Collection<String>) {
    private val exact: Set<String>
    private val prefixes: Array<String>

    init {
        require(patterns.all { it.isNotEmpty() && it != WILDCARD }) { "Empty or match-all pattern" }
        require(patterns.none { it.dropLast(1).contains(WILDCARD) }) { "Wildcard only allowed at the end" }
        val (wild, plain) = patterns.partition { it.endsWith(WILDCARD) }
        exact = plain.toHashSet()
        prefixes = wild.map { it.dropLast(1) }.distinct().toTypedArray()
    }

    public fun matches(key: String?): Boolean {
        if (key.isNullOrEmpty()) return false
        if (key in exact) return true
        for (prefix in prefixes) {
            if (key.startsWith(prefix)) return true
        }
        return false
    }

    private companion object {
        const val WILDCARD = "*"
    }
}

/**
 * Vendor Settings keys hidden from third-party apps by the `settings.identifiers` rule. Found on a
 * HyperOS 4 device (see `docs/collection-techniques.md` 2.3): account and device identifiers, plus
 * keys whose value is a package list (they bypass package visibility).
 *
 * Never add `android_id` (already per-signer and handled specially by SettingsProvider) or an
 * AOSP-public key: apps read those for legitimate functionality.
 */
public object SettingsDenyList {
    public val PATTERNS: List<String> = listOf(
        // Device identifiers.
        "ad_aaid",
        "mi_health_id",
        "key_mqs_uuid",
        "pref_mipub_random_device_id",
        "com.miui.yellowpage_preferences.pref_uuid",
        "green_kid_device_id",
        // Xiaomi account id and account-derived state; virtualsim keys embed the account id in the name.
        "last_synced_account",
        "virtualsim_account_rule_*",
        "antispam_sync_token",
        "account_msg_*",
        "cloud_msg_account_*",
        // Vendor-private, signature/token-like Secure keys readable by any app (red-team probe on
        // the device); owner not yet identified in the decompiled sources.
        "agent_sign_*",
        // Package lists.
        "com.xiaomi.market_need_update_app_packages_list",
        "miui_home_hide_app_list",
        "notificationbar_launch_list",
    )

    public val matcher: SettingsKeyMatcher = SettingsKeyMatcher(PATTERNS)
}

/** The Settings tables, numbered as in `SettingsState.getTypeFromKey`. */
public enum class SettingsTable(public val type: Int, public val label: String) {
    GLOBAL(0, "global"),
    SYSTEM(1, "system"),
    SECURE(2, "secure"),
    ;

    public companion object {
        private const val TYPE_SHIFT = 28
        private const val LOW_WORD = 0xFFFF_FFFFL

        /** The table of a `SettingsState.mKey` (type in bits 28..31 of its low word); `null` for SSAID or config. */
        public fun ofStateKey(stateKey: Long): SettingsTable? = ofType(
            ((stateKey and LOW_WORD).toInt()) ushr TYPE_SHIFT,
        )

        public fun ofType(type: Int): SettingsTable? = when (type) {
            GLOBAL.type -> GLOBAL
            SYSTEM.type -> SYSTEM
            SECURE.type -> SECURE
            else -> null
        }
    }
}

/**
 * How Settings reads are carried in the diagnostics (observed endpoints) channel: a pseudo host
 * `settings.<table>` and the key as a one-segment path, under the *reading* app's package. Only
 * the key name is recorded, never the value.
 */
public object SettingsObservation {
    public const val HOST_PREFIX: String = "settings."
    private const val MAX_KEY_LENGTH = 120
    private const val REPLACEMENT = '_'
    private const val UNSAFE = "/?#,"

    public fun host(table: SettingsTable): String = HOST_PREFIX + table.label

    /** Whether an observed endpoint is a Settings read rather than network traffic. */
    public fun isSettingsHost(host: String): Boolean =
        host.startsWith(HOST_PREFIX) && SettingsTable.entries.any { host == host(it) }

    /**
     * `/` + [key] with characters that would split or end a path prefix (`/`, `?`, `#`, control
     * characters, separators of the status wire format) replaced, cut to a bounded length, so it
     * survives `ObservedEndpoint.pathPrefixOf` unchanged.
     */
    public fun path(key: String): String {
        val length = minOf(key.length, MAX_KEY_LENGTH)
        if (length == 0) return "/$REPLACEMENT"
        return buildString(length + 1) {
            append('/')
            for (i in 0 until length) {
                val char = key[i]
                append(if (char.isISOControl() || char in UNSAFE) REPLACEMENT else char)
            }
        }
    }
}
