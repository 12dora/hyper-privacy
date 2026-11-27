package io.github.hyperprivacy.core.tracker

import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.system.AppListConfig

/**
 * The options a newly protected app starts with, set once on the Apps page ("default settings")
 * and stored in the remote preferences group next to the per-app keys:
 *
 * - [SDK_PREFIX]`<CATEGORY>` → Boolean: whether detected SDKs of that [TrackerCategory] (their
 *   primary one, as grouped on the app page) are blocked. Absent: `true`. Even when `true`, SDKs
 *   whose blocking can affect sign-in, payment or updates ([TrackerSdk.defaultBlock] `false`) stay
 *   off until the user turns them on in that app.
 * - [RULE_PREFIX]`<ruleId>` → Boolean, for [Catalog.protectedAppRules]. Absent: the rule's default.
 * - [HIDE_KEY], [STRICT_KEY] → Boolean: app-list hiding and its strict mode. Absent: `false`.
 *
 * Nothing in a hooked process reads these keys: [applyTo] turns them into the regular per-app
 * keys once per app, the first time its protection is turned on (the app keeps track of which apps
 * got them; turning protection off and on again re-applies nothing), so the user can still change
 * each app afterwards and a later edit of the defaults never changes apps already protected once.
 * SDKs a later scan finds in a protected app start as the defaults say ([sdkKeys]). With nothing
 * stored, [applyTo] writes nothing and protection behaves exactly as before the defaults existed.
 */
public data class AppDefaults(
    /** Per category; a category missing here is blocked ([isCategoryBlocked]). */
    val sdk: Map<TrackerCategory, Boolean> = emptyMap(),
    /** Per protected-app rule id; a rule missing here follows its own default ([isRuleOn]). */
    val rules: Map<String, Boolean> = emptyMap(),
    val hide: Boolean = false,
    val strict: Boolean = false,
) {
    public fun isCategoryBlocked(category: TrackerCategory): Boolean = sdk[category] ?: true

    public fun isRuleOn(ruleId: String): Boolean =
        rules[ruleId] ?: Catalog.protectedAppRules.firstOrNull { it.id == ruleId }?.defaultOn ?: false

    /**
     * The per-app keys to write when protection is turned on for [packageName], whose scan found
     * [detectedIds], given the current preferences [raw]. A key the user already set for this app
     * is kept, and a key whose value would equal what the hooks assume without it is not written,
     * so the built-in defaults leave the preferences untouched.
     */
    public fun applyTo(
        raw: Map<String, *>,
        packageName: String,
        detectedIds: Collection<String>,
    ): Map<String, Boolean> {
        val puts = LinkedHashMap(sdkKeys(raw, packageName, detectedIds))
        fun put(key: String, value: Boolean, implied: Boolean) {
            if (value != implied && raw[key] !is Boolean) puts[key] = value
        }
        // Only a rule the user set in the defaults: an untouched one keeps whatever the hooks assume today.
        Catalog.protectedAppRules.forEach { rule ->
            val on = rules[rule.id] ?: return@forEach
            val key = TrackerRules.appRuleKey(packageName, rule.id)
            put(key, on, implied = TrackerRules.isAppRuleOn(raw - key, packageName, rule))
        }
        put(AppListConfig.hideKey(packageName), hide, implied = false)
        put(AppListConfig.strictKey(packageName), hide && strict, implied = false)
        return puts
    }

    /**
     * The SDK part of [applyTo] alone: also used when a protected app is updated and its new scan
     * finds SDKs it did not have ([detectedIds] are then only the new ones).
     */
    public fun sdkKeys(
        raw: Map<String, *>,
        packageName: String,
        detectedIds: Collection<String>,
    ): Map<String, Boolean> = detectedIds
        .mapNotNull(Trackers::byId)
        // Off for the category turns every SDK of it off; on keeps each SDK's own default.
        .filter { it.switchable && it.defaultBlock && !isCategoryBlocked(it.category) }
        .map { TrackerProtection.blockKey(packageName, it.id) }
        .filter { raw[it] !is Boolean }
        .associateWith { false }

    /** The preference entries of these defaults; values equal to the built-in ones are left out. */
    public fun entries(): Map<String, Boolean> = buildMap {
        sdk.forEach { (category, on) -> if (!on) put(sdkKey(category), false) }
        rules.forEach { (id, on) ->
            val rule = Catalog.protectedAppRules.firstOrNull { it.id == id }
            if (rule != null && on != rule.defaultOn) put(ruleKey(id), on)
        }
        if (hide) put(HIDE_KEY, true)
        // Strict mode only refines hiding: on its own it means nothing, so it is not stored.
        if (hide && strict) put(STRICT_KEY, true)
    }

    public companion object {
        public const val PREFIX: String = "defaults."
        public const val SDK_PREFIX: String = "${PREFIX}sdk."
        public const val RULE_PREFIX: String = "${PREFIX}rule."
        public const val HIDE_KEY: String = "${PREFIX}hide"
        public const val STRICT_KEY: String = "${PREFIX}strict"

        /** Today's behaviour: every switchable SDK at its own default, rules at theirs, no app-list hiding. */
        public val BUILT_IN: AppDefaults = AppDefaults()

        /** Categories with at least one SDK that has a switch, in enum order: the rows of the defaults page. */
        public val categories: List<TrackerCategory> by lazy {
            val switchable = Trackers.all.filter { it.switchable }.mapTo(HashSet()) { it.category }
            TrackerCategory.entries.filter { it in switchable }
        }

        /** Every key the defaults can be stored under, whether stored or not (restore defaults removes them all). */
        public val allKeys: Set<String> by lazy {
            buildSet {
                TrackerCategory.entries.forEach { add(sdkKey(it)) }
                Catalog.protectedAppRuleIds.forEach { add(ruleKey(it)) }
                add(HIDE_KEY)
                add(STRICT_KEY)
            }
        }

        public fun sdkKey(category: TrackerCategory): String = SDK_PREFIX + category.name

        public fun ruleKey(ruleId: String): String = RULE_PREFIX + ruleId

        /** Whether [key] is one of the default-settings keys this version understands. */
        public fun isKey(key: String): Boolean =
            key == HIDE_KEY || key == STRICT_KEY || categoryOf(key) != null || ruleIdOf(key) != null

        private fun categoryOf(key: String): TrackerCategory? = key.takeIf { it.startsWith(SDK_PREFIX) }
            ?.removePrefix(SDK_PREFIX)
            ?.let { name -> TrackerCategory.entries.firstOrNull { it.name == name } }

        private fun ruleIdOf(key: String): String? = key.takeIf { it.startsWith(RULE_PREFIX) }
            ?.removePrefix(RULE_PREFIX)
            ?.takeIf { it in Catalog.protectedAppRuleIds }

        /**
         * The value the defaults page shows for protected-app rule [ruleId] under [raw]: the stored
         * default, else a legacy global `rule.<id>` switch (older versions wrote one; [applyTo] then
         * writes nothing and the hooks follow it), else the rule's own default.
         */
        public fun shownRule(raw: Map<String, *>, ruleId: String): Boolean = raw[ruleKey(ruleId)] as? Boolean
            ?: raw[ConfigCodec.key(ruleId)] as? Boolean
            ?: Catalog.protectedAppRules.firstOrNull { it.id == ruleId }?.defaultOn
            ?: false

        /** The defaults stored in [raw]; anything missing or of the wrong type is the built-in value. */
        public fun read(raw: Map<String, *>): AppDefaults {
            val sdk = LinkedHashMap<TrackerCategory, Boolean>()
            val rules = LinkedHashMap<String, Boolean>()
            raw.forEach { (key, value) ->
                if (value !is Boolean || !key.startsWith(PREFIX)) return@forEach
                // A category without switchable SDKs has no row and no effect, so it is not a setting.
                categoryOf(key)?.takeIf { it in categories }?.let { sdk[it] = value }
                ruleIdOf(key)?.let { rules[it] = value }
            }
            return AppDefaults(
                sdk = sdk,
                rules = rules,
                hide = raw[HIDE_KEY] as? Boolean ?: false,
                strict = raw[STRICT_KEY] as? Boolean ?: false,
            )
        }
    }
}
