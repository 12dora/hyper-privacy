package io.github.hyperprivacy.core.rule.catalog

import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.rule.UrlPrefix

/** Log, crash and performance upload. */
internal val diagnosticsRules: List<Rule> = listOf(
    Rule(
        id = "mqs.domains",
        packages = setOf("com.miui.powerkeeper"),
        title = Text("MQS 诊断日志", "MQS diagnostics"),
        summary = Text("阻止应用活动与硬件日志上报", "Block app-activity and hardware logs"),
        payload = RulePayload.Domains(listOf("mqs-log.miui.com", "mqs-api.pt.miui.com")),
        category = Category.DIAGNOSTICS,
        evidence = "dex powerkeeper (com/miui/daemon/mqsas, 635 classes): mqs-log.miui.com/data/{appLife,killProcess," +
            "screenOn,sensor,hardwareInfo,hwInfo,bluetooth,bootEvent,storage,emmcLife,wcns,fido,identifyRes," +
            "matrix}Upload, sysopt/coldStartUpload, /v2/data/mqsUpload, /mqsas/fileUpload*; " +
            "mqs-api.pt.miui.com/biz/log/query/*",
    ),
    Rule(
        id = "mqs.port-upload",
        packages = setOf("com.miui.misightservice", "com.miui.powerkeeper"),
        title = Text("MQS 文件上传", "MQS file uploads"),
        summary = Text("阻止诊断文件与事件上传", "Block diagnostic file and event uploads"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("port.sec.miui.com", "/mqsas/fileUpload"),
                UrlPrefix("port.sec.miui.com", "/mqsas/kdxFileUpload"),
                UrlPrefix("port.sec.miui.com", "/v2/data/mqsUpload"),
                UrlPrefix("port.sec.intl.miui.com", "/v2/data/mqsUpload"),
                UrlPrefix("port.sec.intl.miui.com", "/mqsas/fileUpload"),
            ),
        ),
        category = Category.DIAGNOSTICS,
        evidence = "dex: powerkeeper port.sec.miui.com/mqsas/fileUploadWithUrlId?bizName={aftersale,themal,test}, " +
            "preview.port.sec{,.intl}.miui.com/v2/data/mqsUpload; misightservice /mqsas/kdxFileUpload. Firmware " +
            "check /mqsas/ffu/checkUpgrade left allowed",
    ),
    Rule(
        id = "xmsf.remote-log",
        packages = setOf("com.xiaomi.xmsf"),
        title = Text("远程日志提取", "Remote log retrieval"),
        summary = Text("阻止服务端拉取设备日志", "Block server-triggered log uploads"),
        payload = RulePayload.Domains(listOf("upload-data-api.xmpush.xiaomi.net")),
        category = Category.DIAGNOSTICS,
        evidence = "jadx xmsf ic.h: upload-data-api.xmpush.xiaomi.net/upload/{text,file}, driven by FetchHelper " +
            "(server push command -> collect + compress log files -> upload). Push delivery hosts unaffected",
    ),
    Rule(
        id = "sentry.domains",
        packages = SENTRY_APPS,
        title = Text("Sentry 崩溃", "Sentry crash reports"),
        summary = Text("阻止崩溃与异常上报", "Block crash and error reports"),
        payload = RulePayload.Domains(listOf("sentry.d.mi.com", "sentry.d.xiaomi.net", "car-app-sentry.d.xiaomi.net")),
        category = Category.DIAGNOSTICS,
        evidence = "traffic cap3/cap4: shop -> sentry.d.mi.com x6, youpin x9, smarthome x3, quicksearchbox -> " +
            "sentry.d.xiaomi.net, mi.car.mobile -> car-app-sentry.d.xiaomi.net x4; jadx shop SentryUtil DSN " +
            "…@sentry.d.mi.com/30",
    ),
    Rule(
        id = "bugly.domains",
        packages = BUGLY_APPS,
        title = Text("Bugly 崩溃", "Bugly crash reports"),
        summary = Text("阻止腾讯崩溃日志上报", "Block Tencent crash logs"),
        payload = RulePayload.Domains(
            listOf(
                "bugly.qq.com",
                "bugly.tencent.com",
                "astat.bugly.qcloud.com",
                "astat.bugly.cros.wr.pvp.net",
                "rqd.uu.qq.com",
            ),
        ),
        category = Category.DIAGNOSTICS,
        evidence = "traffic cap4: shop -> android.bugly.qq.com POST /rqd/async, youpin -> android.bugly.qq.com; dex " +
            "com/tencent/bugly in sogou, gamecenter.sdk.service, shop, smarthome, youpin; libbugly.so",
    ),
    Rule(
        id = "rmonitor.domains",
        packages = setOf("com.sohu.inputmethod.sogou.xiaomi"),
        title = Text("腾讯性能监控", "Tencent RMonitor"),
        summary = Text("阻止性能与卡顿数据上报", "Block performance and jank data"),
        payload = RulePayload.Domains(listOf("rmonitor.qq.com")),
        category = Category.DIAGNOSTICS,
        evidence = "traffic cap3 typing: sogou -> rmonitor.qq.com x2; dex com/tencent/rmonitor, " +
            "{pre,t}.rmonitor.qq.com",
    ),
    Rule(
        id = "bytedance.apm",
        packages = setOf("com.android.browser", "com.mi.globalbrowser", "com.xiaomi.smarthome"),
        title = Text("字节 APM", "ByteDance APM+"),
        summary = Text("阻止性能与崩溃数据上报", "Block performance and crash data"),
        payload = RulePayload.Domains(
            listOf(
                "apmplus.volces.com",
                "apm.volccdn.com",
                "apmplus.hybrid.cn",
                "dispatch-api-online.vemarsdev.com",
                "api.howlapm.com",
            ),
        ),
        category = Category.DIAGNOSTICS,
        evidence = "traffic cap4: smarthome -> apmplus.volces.com x2; dex apmplus.volces.com/monitor/collect/c/" +
            "{crash,exception,native_bin_crash,core_dump_collect,logcollect,session} in browser, smarthome; " +
            "libapminsight*.so",
    ),
    Rule(
        id = "firebase.crashlytics",
        packages = setOf("com.android.fileexplorer", "com.mi.globalbrowser", "com.miui.extraphoto"),
        title = Text("Firebase 崩溃", "Firebase Crashlytics"),
        summary = Text("阻止谷歌崩溃日志上报", "Block Google crash logs"),
        payload = RulePayload.Domains(listOf("crashlytics.com", "crashlyticsreports-pa.googleapis.com")),
        category = Category.DIAGNOSTICS,
        evidence = "dex: com/google/firebase/crashlytics in fileexplorer, globalbrowser, extraphoto; " +
            "reports.crashlytics.com/spi/v1/platforms/android/apps/%s/reports, firebase-settings.crashlytics.com; " +
            "libcrashlytics*.so",
    ),
    Rule(
        id = "xiaomi.rum",
        packages = setOf("com.miui.greenguard", "com.miui.virtualsim", "com.xiaomi.market"),
        title = Text("小米 RUM 监控", "Xiaomi RUM monitoring"),
        summary = Text("阻止页面性能数据上报", "Block page performance data"),
        payload = RulePayload.Domains(listOf("apm-rum.inf.miui.com")),
        category = Category.DIAGNOSTICS,
        evidence = "traffic cap2: market -> apm-rum.inf.miui.com x2; dex in greenguard, virtualsim",
    ),
    Rule(
        id = "kingsoft.stats",
        packages = setOf("com.xiaomi.market", "com.xiaomi.shop"),
        title = Text("金山云统计", "Kingsoft Cloud stats"),
        summary = Text("阻止播放与网络质量统计", "Block playback/network quality stats"),
        payload = RulePayload.Domains(
            listOf(
                "kingdata.ksyun.com",
                "cdnlog-kingdata.ksyun.com",
                "store.log.ksyun.com",
                "trace-ldns.ksyun.com",
            ),
        ),
        category = Category.DIAGNOSTICS,
        evidence = "dex: market kingdata.ksyun.com, cdnlog-kingdata.ksyun.com, store.log.ksyun.com (KSY player " +
            "stats, trace/v3 with deviceid); shop trace-ldns.ksyun.com, libksyapm.so",
    ),
    Rule(
        id = "game-sdk.logs",
        packages = GAME_SDK_LOGS_APPS,
        title = Text("游戏 SDK 日志", "Game SDK logs"),
        summary = Text("阻止游戏服务日志上报", "Block game service logs"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("sdk.s.migames.com", "/sdkservice/api/log/"),
                UrlPrefix("hysdkservice.g.mi.com", "/sdkservice/api/log/"),
                UrlPrefix("data.game.xiaomi.com", "/log/"),
                UrlPrefix("data.game.xiaomi.com", "/appmix.do"),
                UrlPrefix("data.game.xiaomi.com", "/gamecenter.do"),
            ),
        ),
        category = Category.DIAGNOSTICS,
        evidence = "jadx securitycenter com.xiaomi.gamecenter.sdk.onlinelog.f: sdk.s.migames.com/sdkservice/api/log/" +
            "reportsdk{,online}log; dex: hysdkservice.g.mi.com/sdkservice/api/log/reportmonitordata (hybrid, " +
            "minigame, xmsf), reportPaymentSwitchData; data.game.xiaomi.com/{log/,appmix.do,gamecenter.do}. " +
            "Login/payment paths untouched",
    ),
    Rule(
        id = "thirdparty.sdk-logs",
        packages = THIRDPARTY_SDK_LOGS_APPS,
        title = Text("第三方组件日志", "Third-party SDK logs"),
        summary = Text("阻止支付与云服务组件日志", "Block payment and cloud SDK logs"),
        payload = RulePayload.Domains(
            listOf(
                "loggw-exsdk.alipay.com",
                "cn-hangzhou-mas-log.cloud.alipay.com",
                "log.wechatpay.cn",
                "nls-log-gather.aliyuncs.com",
                "log.tbs.qq.com",
                "ap-guangzhou.log.zhiyan.tencent-cloud.net",
            ),
        ),
        category = Category.DIAGNOSTICS,
        evidence = "dex: loggw-exsdk.alipay.com/loggw/logUpload.do in 10 apps (health, hybrid, player, tsmclient, " +
            "gamecenter…); cn-hangzhou-mas-log.cloud.alipay.com (smarthome); log.wechatpay.cn/mmpayendpointreport " +
            "(health, tsmclient); log.tbs.qq.com (X5); nls-log-gather.aliyuncs.com (mi.car.mobile); zhiyan " +
            "collectruntime (securityadd). Payment gateways untouched",
    ),
    Rule(
        id = "browser.engine-telemetry",
        packages = setOf("com.android.browser", "com.mi.globalbrowser"),
        title = Text("浏览器内核遥测", "Browser engine telemetry"),
        summary = Text("阻止内核网络可靠性上报", "Block engine network-reliability beacons"),
        payload = RulePayload.Domains(
            listOf(
                "beacons.gvt2.com",
                "beacons2.gvt2.com",
                "beacons3.gvt2.com",
                "beacons4.gvt2.com",
                "beacons5.gvt2.com",
                "beacons.gcp.gvt2.com",
                "beacons5.gvt3.com",
            ),
        ),
        category = Category.DIAGNOSTICS,
        evidence = "dex: beacons*.gvt2.com/domainreliability/upload in browser and globalbrowser (Chromium domain " +
            "reliability)",
    ),
    Rule(
        id = "aicr.runtime-log",
        packages = setOf("com.xiaomi.aicr"),
        title = Text("AI 组件日志", "AI SDK logs"),
        summary = Text("阻止运行时下发组件日志", "Block runtime-loaded SDK logs"),
        payload = RulePayload.Domains(listOf("log-sdk.clickwifi.net")),
        category = Category.DIAGNOSTICS,
        evidence = "traffic cap2: aicr -> log-sdk.clickwifi.net, api-sdk.clickwifi.net, cache-files.isjike.com; host " +
            "absent from all dex, native libs and bundled configs (runtime-delivered code). Only the log host is " +
            "blocked",
    ),
    Rule(
        id = "powerinsight.log-upload",
        packages = setOf("com.miui.powerinsight"),
        title = Text("功耗日志上传", "Power log uploads"),
        summary = Text("阻止功耗分析日志上传", "Block power-analysis log uploads"),
        payload = RulePayload.Urls(listOf(UrlPrefix("power.pt.xiaomi.com", "/file/upload/"))),
        category = Category.DIAGNOSTICS,
        evidence = "dex: powerinsight power.pt.xiaomi.com/file/upload/zipFile2; OneTrack /track/appUpload also present",
    ),
)
