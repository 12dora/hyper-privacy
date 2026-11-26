package io.github.hyperprivacy.core.rule

/**
 * The built-in rule set. Adding a rule is a data-only change: append an entry to [rules].
 *
 * Scope policy: only privacy reporting channels. A rule must never affect a vendor service
 * (app store, security scan, push, cloud sync, find device, weather, updates …).
 *
 * Invariants (enforced by tests): ids are unique, every rule cites evidence, DOMAIN rules name
 * specific telemetry hosts rather than vendor apexes, and FIREWALL targets own a dedicated uid.
 *
 * The entries below are placeholders until the device survey supplies the real catalog.
 */
public object Catalog {
    /** LSPosed's virtual scope name for system_server. */
    public const val SYSTEM_SCOPE: String = "system"

    private const val MARKET = "com.xiaomi.market"
    private const val THEMES = "com.android.thememanager"

    public val rules: List<Rule> = listOf(
        Rule(
            id = "analytics.firewall",
            packages = setOf("com.miui.analytics"),
            title = Text("系统分析服务断网", "Cut off system analytics"),
            summary = Text("禁止 MIUI 分析服务联网上报使用数据", "Stop MIUI analytics from uploading usage data"),
            payload = RulePayload.Firewall,
            category = Category.ANALYTICS,
            evidence = "traffic: com.miui.analytics (own uid) → tracking.miui.com",
        ),
        Rule(
            id = "market.tracking-domains",
            packages = setOf(MARKET),
            title = Text("拦截统计域名", "Block analytics domains"),
            summary = Text("阻止应用商店连接统计上报服务器", "Block the app store's analytics servers"),
            payload = RulePayload.Domains(listOf("tracking.miui.com", "data.mistat.xiaomi.com")),
            category = Category.ANALYTICS,
            evidence = "traffic: com.xiaomi.market → tracking.miui.com, data.mistat.xiaomi.com",
        ),
        Rule(
            id = "themes.tracking-domains",
            packages = setOf(THEMES),
            title = Text("拦截统计域名", "Block analytics domains"),
            summary = Text("阻止主题商店连接统计上报服务器", "Block the theme store's analytics servers"),
            payload = RulePayload.Domains(listOf("tracking.miui.com", "data.mistat.xiaomi.com")),
            category = Category.ANALYTICS,
            evidence = "traffic: com.android.thememanager → tracking.miui.com, data.mistat.xiaomi.com",
        ),
        Rule(
            id = "onetrack.track",
            packages = setOf(MARKET, THEMES),
            title = Text("停用 OneTrack 事件上报", "Disable OneTrack event reporting"),
            summary = Text("将 OneTrack SDK 的 track 系列方法置空", "No-op the OneTrack SDK track methods"),
            payload = RulePayload.Method(
                className = "com.xiaomi.onetrack.OneTrack",
                method = MethodPattern.parse("track*"),
            ),
            category = Category.ANALYTICS,
            evidence = "dex: Lcom/xiaomi/onetrack/OneTrack;",
        ),
    )

    private val byId: Map<String, Rule> = rules.associateBy { it.id }

    /** Packages whose own processes need the module injected (DOMAIN and METHOD rules). */
    public val hookedPackages: Set<String> = rules
        .filter { it.kind != RuleKind.FIREWALL }
        .flatMapTo(sortedSetOf()) { it.packages }

    /** Every package referenced by any rule. */
    public val allPackages: Set<String> = rules.flatMapTo(sortedSetOf()) { it.packages }

    /**
     * The LSPosed scope: hooked packages plus system_server, which enforces FIREWALL rules.
     * FIREWALL targets are deliberately absent; injecting into them would add risk for nothing.
     */
    public val scope: Set<String> = sortedSetOf(SYSTEM_SCOPE).apply { addAll(hookedPackages) }

    public fun byId(id: String): Rule? = byId[id]

    /** Rules applying to [packageName], in catalog order. */
    public fun rulesFor(packageName: String): List<Rule> = rules.filter { packageName in it.packages }
}
