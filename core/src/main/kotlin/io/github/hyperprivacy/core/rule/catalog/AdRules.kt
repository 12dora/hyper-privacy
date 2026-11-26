package io.github.hyperprivacy.core.rule.catalog

import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.rule.UrlPrefix

/** Ad attribution and ad reporting. */
internal val adRules: List<Rule> = listOf(
    Rule(
        id = "msa.firewall",
        packages = setOf("com.miui.systemAdSolution"),
        title = Text("系统广告服务", "System ad service"),
        summary = Text("禁止联网，移除开屏与推荐广告", "Cut off splash and feed ads"),
        payload = RulePayload.Firewall,
        category = Category.ADS,
        evidence = "uid 10180 (not shared). dex: only api.ad.xiaomi.com, preview/test.ad.xiaomi.com, " +
            "monitor-check.ad.xiaomi.com plus push registration; exported services ISystemSplashAdService, " +
            "ILandingPageService, ISystemExtMessageService, IMsaDiskLruCacheService — ad delivery only",
    ),
    Rule(
        id = "xiaomi-ads.domains",
        packages = AD_SDK_APPS,
        title = Text("小米广告 SDK", "Xiaomi Ads SDK"),
        summary = Text("阻止广告请求、曝光与监测", "Block ad requests, impressions, tracking"),
        payload = RulePayload.Domains(
            listOf(
                "ad.xiaomi.com",
                "ad.intl.xiaomi.com",
                "ad.india.xiaomi.com",
                "ad.rus.xiaomi.com",
            ),
        ),
        category = Category.ADS,
        evidence = "dex: api.ad.xiaomi.com in 105 apps (OneTrack ad-monitor URLs get _sn_ appended), hidden " +
            "sdkconfig.ad.{,intl,india,rus}.xiaomi.com in 34–36 apps (ad/analytics core updater), " +
            "monitor-check.ad.xiaomi.com in 11, log.ad.xiaomi.com; sdk com/xiaomi/ad (12), com/miui/zeus (11), " +
            "com/miui/msa (5). The *.ad.xiaomi.com zone serves only ads",
    ),
    Rule(
        id = "gdt.domains",
        packages = setOf("com.baidu.input_mi", "com.miui.player", "com.sohu.inputmethod.sogou.xiaomi"),
        title = Text("广点通", "Tencent GDT ads"),
        summary = Text("阻止腾讯广告请求与回传", "Block Tencent ad requests and reports"),
        payload = RulePayload.Domains(listOf("gdt.qq.com", "e.qq.com", "gdtimg.com", "pgdt.gtimg.cn", "us.l.qq.com")),
        category = Category.ADS,
        evidence = "traffic cap2/cap4: player -> xs.gdt.qq.com x27, pgdt.gtimg.cn x13, sdkreport.e.qq.com x9, " +
            "tangram.e.qq.com, adsmind.gdtimg.com, us.l.qq.com; dex com/qq/e/comm in player, baidu.input_mi, sogou; " +
            "ads.privacy.qq.com (opt-out) is not matched",
    ),
    Rule(
        id = "music.ads",
        packages = setOf("com.miui.player"),
        title = Text("音乐广告", "Music app ads"),
        summary = Text("阻止腾讯音乐广告投放", "Block Tencent Music ads"),
        payload = RulePayload.Domains(listOf("ad.tencentmusic.com", "tmead.y.qq.com", "tmeadcomm.y.qq.com")),
        category = Category.ADS,
        evidence = "traffic cap2/cap4: player -> ad.tencentmusic.com x4, tmead.y.qq.com, tmeadcomm.y.qq.com; dex " +
            "tmead.y.qq.com/mareportsimp/ReportSimpleBatch",
    ),
    Rule(
        id = "pangle.domains",
        packages = setOf("com.android.browser", "com.mi.globalbrowser", "com.miui.voiceassist"),
        title = Text("穿山甲", "Pangle ads"),
        summary = Text("阻止字节广告请求与回传", "Block ByteDance ad requests and reports"),
        payload = RulePayload.Domains(
            listOf(
                "pangolin-sdk-toutiao.com",
                "pangolin-sdk-toutiao-b.com",
                "pglstatp-toutiao.com",
                "pangle.io",
                "tiktokpangle.us",
                "tiktokpangle-cdn-us.com",
                "i18n-pglstatp.com",
            ),
        ),
        category = Category.ADS,
        evidence = "dex: com/bytedance/sdk/openadsdk in browser, globalbrowser, voiceassist; voiceassist " +
            "api-access.pangolin-sdk-toutiao.com/api/ad/union/sdk/settings; traffic cap4: globalbrowser -> " +
            "api16-access-ttp.tiktokpangle.us x5, api16-access-wf-sg.pangle.io, sf16-static.i18n-pglstatp.com",
    ),
    Rule(
        id = "google.ads",
        packages = setOf("com.mi.globalbrowser"),
        title = Text("谷歌广告", "Google ads"),
        summary = Text("阻止 AdMob 与 DoubleClick", "Block AdMob and DoubleClick ads"),
        payload = RulePayload.Domains(
            listOf(
                "googleads.g.doubleclick.net",
                "pubads.g.doubleclick.net",
                "pagead2.googlesyndication.com",
            ),
        ),
        category = Category.ADS,
        evidence = "traffic cap4: globalbrowser -> googleads.g.doubleclick.net x2, pubads.g.doubleclick.net. " +
            "fundingchoicesmessages.google.com (consent) not matched",
    ),
    Rule(
        id = "securitycenter.ads",
        packages = setOf("com.miui.cleanmaster", "com.miui.securitycenter", "com.miui.securitycore"),
        title = Text("安全中心广告", "Security app ads"),
        summary = Text("阻止游戏广告与广告统计", "Block game ads and ad statistics"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("data.sec.miui.com", "/adv/"),
                UrlPrefix("adv.sec.miui.com", "/info/videoAdv"),
                UrlPrefix("adv.sec.miui.com", "/game2/gameAdv"),
                UrlPrefix("adv.sec.miui.com", "/switch/clonedAd"),
            ),
        ),
        category = Category.ADS,
        evidence = "evidence.md L708 securitycore adv.sec.miui.com/switch/clonedAd (ads in cloned apps); jadx " +
            "securitycenter: AdvTailStatUtils -> data.sec.miui.com/adv/tail (biz tail4virusscan, did); gamebooster " +
            "active_track -> /adv/game_ad/report; ki.q -> adv.sec.miui.com/info/videoAdv; ki.p/ki.c -> " +
            "/game2/gameAdv (sends gaid). Game Turbo config (/gameTurbo/*, /info/gameConfig, /game/speedParams) left " +
            "allowed",
    ),
    Rule(
        id = "securitycenter.promo-cards",
        packages = SECURITY_CENTER_APPS,
        title = Text("安全中心运营卡片", "Security promo cards"),
        summary = Text("阻止结果页推荐卡片", "Block result-page promo cards"),
        payload = RulePayload.Urls(listOf(UrlPrefix("adv.sec.miui.com", "/info/layout"))),
        category = Category.ADS,
        evidence = "jadx securitycenter: 9 callers of adv.sec.miui.com/info/layout (scan result, home, phone-manage, " +
            "notification, app manager cards; BatterySaveIdeaLoadTask posts installed pkgNames); traffic: " +
            "adv.sec.miui.com from uid 1000 x5",
        impact = Text("结果页不再显示推荐", "Hides result page suggestions"),
        highRisk = true,
    ),
    Rule(
        id = "ime.promo",
        packages = setOf("com.baidu.input_mi", "com.iflytek.inputmethod.miui", "com.sohu.inputmethod.sogou.xiaomi"),
        title = Text("输入法推广位", "IME promo slots"),
        summary = Text("阻止推广图标与弹窗广告", "Block promo icons and popup ads"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("adv.sec.miui.com", "/info/icon"),
                UrlPrefix("adv.sec.miui.com", "/info/inputAdv/"),
                UrlPrefix("mime.baidu.com", "/v5/solid-window-ads/"),
            ),
        ),
        category = Category.ADS,
        evidence = "traffic cap3 typing: sogou -> adv.sec.miui.com x2; dex: adv.sec.miui.com/info/icon, " +
            "/info/icon_st in all three IMEs, /info/inputAdv/ in iflytek; baidu yn.i base + \"/info/icon\", " +
            "/v5/solid-window-ads/content",
    ),
    Rule(
        id = "weather.ads",
        packages = setOf("com.miui.weather2"),
        title = Text("天气广告", "Weather ads"),
        summary = Text("阻止天气页广告", "Block ads in Weather"),
        payload = RulePayload.Domains(listOf("wtradv.market.xiaomi.com")),
        category = Category.ADS,
        evidence = "traffic cap4: weather2 -> wtradv.market.xiaomi.com x3 (evidence.md L779); the host also serves " +
            "/v1/index/indexDetail and /v1/page (L782), so ads cannot be separated from life-index content",
        defaultOn = false,
        impact = Text("生活指数详情与资讯页无法加载", "Life-index details may not load"),
    ),
    Rule(
        id = "baidu.ads",
        packages = setOf("com.baidu.input_mi"),
        title = Text("百度联盟广告", "Baidu Union ads"),
        summary = Text("阻止百度联盟广告投放", "Block Baidu Union ads"),
        payload = RulePayload.Domains(listOf("union.baidu.com", "cpro.baidustatic.com")),
        category = Category.ADS,
        evidence = "evidence.md L322 baidu.input_mi union.baidu.com, cpro.baidustatic.com, com.baidu.input.ad.info; " +
            "jadx XAdEntryResponse unionUrl https://union.baidu.com/",
    ),
)
