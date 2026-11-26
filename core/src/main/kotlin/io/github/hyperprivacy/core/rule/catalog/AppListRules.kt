package io.github.hyperprivacy.core.rule.catalog

import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.rule.UrlPrefix

/** Installed-app list, app activity and usage-time uploads. */
internal val appListRules: List<Rule> = listOf(
    Rule(
        id = "market.app-active",
        packages = setOf("com.xiaomi.market"),
        title = Text("应用激活上报", "App activation reports"),
        summary = Text("阻止上报应用安装与启动", "Block app install and launch reports"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("app.market.xiaomi.com", "/apm/statistics/"),
                UrlPrefix("api-external.g.mi.com", "/contentapi/external/tencentpredownload/datareport"),
            ),
        ),
        category = Category.APP_LIST,
        evidence = "jadx market: AppActiveStatService -> MARKET_URL_BASE+statistics/appactive (packageName, " +
            "versionCode, installer, channel, activeTime, firstInstallTime, apkHash, ref); " +
            "/apm/statistics/checkdatauser; tencentpredownload/datareport. Store browsing, download and update APIs " +
            "untouched",
    ),
    Rule(
        id = "xmsf.app-active",
        packages = setOf("com.xiaomi.xmsf"),
        title = Text("服务框架激活统计", "Framework activation stats"),
        summary = Text("阻止上报应用激活记录", "Block app activation records"),
        payload = RulePayload.Urls(listOf(UrlPrefix("api.xmpush.xiaomi.com", "/xmsf/stats/"))),
        category = Category.APP_LIST,
        evidence = "jadx xmsf de.f (ActivationUploadHelper): api.xmpush.xiaomi.com/xmsf/stats/app/active with " +
            "active_infos (package, traceId, activeId, caller, pageName). Push registration/delivery untouched",
    ),
    Rule(
        id = "assistant.app-sync",
        packages = setOf("com.miui.personalassistant"),
        title = Text("应用列表同步", "Installed-app sync"),
        summary = Text("阻止上传已安装应用列表", "Block installed-app list uploads"),
        payload = RulePayload.Urls(listOf(UrlPrefix("assistant.miui.com", "/component/store/syncAppInfo"))),
        category = Category.APP_LIST,
        evidence = "jadx personalassistant: PickerRequestService @POST component/store/syncAppInfo, body = " +
            "compressed list of every installed package + versionCode + firstInstallTime (EVENT_FULL/INSTALL/" +
            "UNINSTALL/UPGRADE); traffic cap2: store.assistant.miui.com x7. Widget store browsing untouched",
    ),
)
