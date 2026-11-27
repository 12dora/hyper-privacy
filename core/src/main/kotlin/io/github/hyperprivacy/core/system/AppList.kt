package io.github.hyperprivacy.core.system

/**
 * The user's app-list hiding choices, as stored in the remote preferences group
 * `ConfigCodec.PREFS_GROUP` (so system_server gets live updates):
 *
 * - [HIDE_PREFIX]`<pkg>` → Boolean: `true` makes `<pkg>` a *restricted* app, which no longer sees
 *   other third-party apps (enumeration, `getPackageInfo` probing, intent queries, `pm list`).
 *   Absent or `false`: unrestricted. Default: nothing is restricted.
 * - [STRICT_PREFIX]`<pkg>` → Boolean: a restricted app also loses sight of the [ALLOW_KEY] partner
 *   apps. Ignored unless `<pkg>` is restricted.
 * - [ALLOW_KEY] → String (package names separated by commas or whitespace) or String set:
 *   partner apps that SDKs legitimately probe for share / login / pay, left visible to non-strict
 *   restricted apps. Absent: [DEFAULT_ALLOWLIST]; present (even empty): replaces the default.
 * - [SELF_HIDE_KEY] → Boolean, default `true`: hide this module's own package (and so its status
 *   provider) from every third-party app, except the default home app, LSPosed managers and the
 *   catalog's hooked targets (see [AppListPolicy.resolve]); protected third-party apps are not
 *   exempt. SDKs otherwise detect the module by package name.
 */
public data class AppListConfig(
    val restricted: Set<String>,
    val strict: Set<String>,
    val allowlist: Set<String>,
    val hideSelf: Boolean = true,
) {
    public companion object {
        public const val HIDE_PREFIX: String = "applist.hide."
        public const val STRICT_PREFIX: String = "applist.strict."
        public const val ALLOW_KEY: String = "applist.allow"
        public const val SELF_HIDE_KEY: String = "applist.self.hide"

        /**
         * Partner apps whose presence SDKs check before offering share, login, payment or "open in
         * maps": mainland social, payment and shopping apps, map apps, then global login, share and
         * payment apps.
         */
        public val DEFAULT_ALLOWLIST: Set<String> = linkedSetOf(
            "com.tencent.mm", // WeChat
            "com.tencent.mobileqq", // QQ
            "com.eg.android.AlipayGphone", // Alipay
            "com.taobao.taobao", // Taobao
            "com.unionpay", // UnionPay
            "com.sina.weibo", // Weibo
            "com.ss.android.ugc.aweme", // Douyin
            "com.xingin.xhs", // Xiaohongshu
            "com.jingdong.app.mall", // JD
            "com.sankuai.meituan", // Meituan
            "com.autonavi.minimap", // Amap
            "com.baidu.BaiduMap", // Baidu Maps
            "com.tencent.map", // Tencent Maps
            "com.google.android.apps.maps", // Google Maps
            "com.facebook.katana", // Facebook
            "com.whatsapp", // WhatsApp
            "org.telegram.messenger", // Telegram
            "com.twitter.android", // X
            "com.paypal.android.p2pmobile", // PayPal
        )

        /** LSPosed manager packages: they must keep seeing the module to list and configure it. */
        public val MODULE_MANAGERS: Set<String> = setOf("org.lsposed.manager", "io.github.lsposed.manager")

        public val EMPTY: AppListConfig = AppListConfig(emptySet(), emptySet(), DEFAULT_ALLOWLIST)

        private val SEPARATORS = Regex("[\\s,]+")

        public fun hideKey(packageName: String): String = HIDE_PREFIX + packageName

        public fun strictKey(packageName: String): String = STRICT_PREFIX + packageName

        /** Whether a change of preference [key] can affect the app-list policy (`null`: everything changed). */
        public fun affects(key: String?): Boolean = key == null || key == ALLOW_KEY || key == SELF_HIDE_KEY ||
            key.startsWith(HIDE_PREFIX) || key.startsWith(STRICT_PREFIX)

        /** Resolves the config from [raw] preferences; malformed values are ignored. */
        public fun decode(raw: Map<String, *>): AppListConfig {
            val restricted = enabledSuffixes(raw, HIDE_PREFIX)
            return AppListConfig(
                restricted = restricted,
                strict = enabledSuffixes(raw, STRICT_PREFIX).filterTo(HashSet()) { it in restricted },
                allowlist = parseAllowlist(raw[ALLOW_KEY]) ?: DEFAULT_ALLOWLIST,
                hideSelf = raw[SELF_HIDE_KEY] as? Boolean ?: true,
            )
        }

        /** Package names from a [ALLOW_KEY] value; `null` when the value is absent or of the wrong type. */
        public fun parseAllowlist(value: Any?): Set<String>? = when (value) {
            is String -> value.split(SEPARATORS).filterTo(LinkedHashSet()) { it.isNotEmpty() }
            is Set<*> -> value.mapNotNullTo(LinkedHashSet()) { (it as? String)?.trim()?.ifEmpty { null } }
            else -> null
        }

        /** Encodes [allowlist] for [ALLOW_KEY]. */
        public fun encodeAllowlist(allowlist: Collection<String>): String = allowlist.joinToString(",")

        private fun enabledSuffixes(raw: Map<String, *>, prefix: String): Set<String> = raw.entries
            .filter { (key, value) -> value == true && key.startsWith(prefix) && key.length > prefix.length }
            .mapTo(HashSet()) { (key, _) -> key.removePrefix(prefix) }
    }
}

/**
 * [AppListConfig] resolved against an [AppIndex] into app-id sets, so `shouldFilterApplication`
 * (a PackageManager hot path) decides with a few [IntSet] probes.
 *
 * Two independent reasons hide a target from a caller (never the caller itself):
 * - **restricted caller**: the caller is restricted, the target is a third-party app, and the
 *   target is not allowlisted (unless the caller is strict);
 * - **module self-hiding**: the target is this module, the caller is a third-party app and not
 *   exempt (the default home app, module managers, catalog targets).
 *
 * "Third-party" is [AppIndex.isThirdParty]: vendor-trusted apps ([VendorTrust]) and
 * non-application uids are never hidden and never hidden from; an app id installed after the
 * snapshot counts as third-party until the next rebuild (fail closed).
 */
public class AppListPolicy private constructor(
    private val restricted: IntSet,
    private val strict: IntSet,
    private val allowlisted: IntSet,
    private val index: AppIndex,
    /** The module's app id, or `-1` when self-hiding is off or the module is not indexed. */
    private val selfAppId: Int,
    private val selfVisibleTo: IntSet,
) {
    /** No restricted caller and no self-hiding: every call can return immediately. */
    public val isInactive: Boolean get() = restricted.isEmpty() && selfAppId < 0

    /** Whether any target can be hidden from [callerAppId]; the cheap pre-check before reading the target. */
    public fun mayHideFrom(callerAppId: Int): Boolean = callerAppId in restricted ||
        (selfAppId >= 0 && callerAppId !in selfVisibleTo && index.isThirdParty(callerAppId))

    public fun isRestricted(callerAppId: Int): Boolean = callerAppId in restricted

    public fun shouldHide(callerAppId: Int, targetAppId: Int): Boolean {
        if (callerAppId == targetAppId) return false
        // Self-hiding alone decides for the module: an exempt caller keeps seeing it even when restricted.
        if (selfAppId >= 0 && targetAppId == selfAppId) {
            return callerAppId !in selfVisibleTo && index.isThirdParty(callerAppId)
        }
        return callerAppId in restricted && index.isThirdParty(targetAppId) &&
            (callerAppId in strict || targetAppId !in allowlisted)
    }

    public companion object {
        public val INACTIVE: AppListPolicy =
            IntSet.EMPTY_SET.let { none -> AppListPolicy(none, none, none, AppIndex.EMPTY, NO_APP, none) }

        private const val NO_APP = -1

        /**
         * @param selfPackage this module's package name.
         * @param selfExempt packages that keep seeing the module besides [AppListConfig.MODULE_MANAGERS]:
         *   the current default home app and the catalog's hooked targets (they deliver status to
         *   its provider). Protected third-party apps are never exempt: they report through
         *   system_server ([io.github.hyperprivacy.core.status.StatusRelay]).
         *
         * Restricted packages that are not installed are skipped; they are resolved again on the next rebuild.
         *
         * Whatever the preferences say, only third-party apps ([AppIndex.isThirdParty]) can be
         * restricted: never a vendor-trusted or system app (launcher, package installer, permission
         * controller …), never this module, a module manager or an exempt package. The import and
         * the UI check the same; this is the last line, so a crafted preference cannot blind them.
         */
        public fun resolve(
            config: AppListConfig,
            index: AppIndex,
            selfPackage: String? = null,
            selfExempt: Set<String> = emptySet(),
        ): AppListPolicy {
            fun ids(packages: Set<String>) = IntSet.of(packages.mapNotNull(index::appIdOf))
            val moduleAppId = selfPackage?.let(index::appIdOf) ?: NO_APP
            val selfAppId = if (config.hideSelf) moduleAppId else NO_APP
            val exemptIds = (selfExempt + AppListConfig.MODULE_MANAGERS).mapNotNullTo(HashSet(), index::appIdOf)
            val restrictedIds = config.restricted.mapNotNull(index::appIdOf).filter { appId ->
                index.isThirdParty(appId) && appId != moduleAppId && appId !in exemptIds
            }
            if (restrictedIds.isEmpty() && selfAppId < 0) return INACTIVE
            val restricted = IntSet.of(restrictedIds)
            return AppListPolicy(
                restricted = restricted,
                strict = IntSet.of(config.strict.mapNotNull(index::appIdOf).filter { it in restricted }),
                allowlisted = ids(config.allowlist),
                index = index,
                selfAppId = selfAppId,
                selfVisibleTo = IntSet.of(exemptIds),
            )
        }
    }
}
