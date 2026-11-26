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
            title = Text("系统分析服务", "System analytics"),
            summary = Text("禁止联网上报使用数据", "Blocks all usage data uploads"),
            payload = RulePayload.Firewall,
            category = Category.ANALYTICS,
            evidence = "traffic: com.miui.analytics (own uid) → tracking.miui.com",
        ),
        Rule(
            id = "market.tracking-domains",
            packages = setOf(MARKET),
            title = Text("商店统计上报", "Store analytics"),
            summary = Text("阻止连接统计服务器", "Blocks analytics servers"),
            payload = RulePayload.Domains(listOf("tracking.miui.com", "data.mistat.xiaomi.com")),
            category = Category.ANALYTICS,
            evidence = "traffic: com.xiaomi.market → tracking.miui.com, data.mistat.xiaomi.com",
        ),
        Rule(
            id = "themes.tracking-domains",
            packages = setOf(THEMES),
            title = Text("主题统计上报", "Theme store analytics"),
            summary = Text("阻止连接统计服务器", "Blocks analytics servers"),
            payload = RulePayload.Domains(listOf("tracking.miui.com", "data.mistat.xiaomi.com")),
            category = Category.ANALYTICS,
            evidence = "traffic: com.android.thememanager → tracking.miui.com, data.mistat.xiaomi.com",
        ),
        Rule(
            id = "onetrack.track",
            packages = setOf(MARKET, THEMES),
            title = Text("OneTrack 统计", "OneTrack tracking"),
            summary = Text("阻止事件与应用列表上报", "Blocks event and app list uploads"),
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
