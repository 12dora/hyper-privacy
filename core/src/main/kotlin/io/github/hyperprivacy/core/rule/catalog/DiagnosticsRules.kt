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
                "android.rqd.qq.com",
            ),
        ),
        category = Category.DIAGNOSTICS,
        evidence = "traffic cap4: shop -> android.bugly.qq.com POST /rqd/async, youpin -> android.bugly.qq.com; dex " +
            "com/tencent/bugly in sogou, gamecenter.sdk.service, shop, smarthome, youpin; libbugly.so; evidence.md " +
            "L814 sogou android.rqd.qq.com/analytics/async",
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
                UrlPrefix("activity.s.migames.com", "/gamesdk/notice/crash"),
            ),
        ),
        category = Category.DIAGNOSTICS,
        evidence = "jadx securitycenter com.xiaomi.gamecenter.sdk.onlinelog.f: sdk.s.migames.com/sdkservice/api/log/" +
            "reportsdk{,online}log; dex: hysdkservice.g.mi.com/sdkservice/api/log/reportmonitordata (hybrid, " +
            "minigame, xmsf), reportPaymentSwitchData; data.game.xiaomi.com/{log/,appmix.do,gamecenter.do}; " +
            "evidence.md L921 gamecenter activity.s.migames.com/gamesdk/notice/crash. Login/payment paths untouched",
    ),
    Rule(
        id = "thirdparty.sdk-logs",
        packages = THIRDPARTY_SDK_LOGS_APPS,
        title = Text("第三方组件日志", "Third-party SDK logs"),
        summary = Text("阻止云服务与内核组件日志", "Block cloud and WebView SDK logs"),
        payload = RulePayload.Domains(
            listOf(
                "nls-log-gather.aliyuncs.com",
                "log.tbs.qq.com",
                "ap-guangzhou.log.zhiyan.tencent-cloud.net",
                "uploadlog.xlmc.sandai.net",
            ),
        ),
        category = Category.DIAGNOSTICS,
        evidence = "dex: log.tbs.qq.com (X5 WebView) in hybrid, player, minigame; nls-log-gather.aliyuncs.com " +
            "(mi.car.mobile, evidence.md L361); zhiyan collectruntime (securityadd); uploadlog.xlmc.sandai.net " +
            "(downloads.ui, L221/L224)",
    ),
    Rule(
        id = "payment-sdk.logs",
        packages = PAYMENT_SDK_LOGS_APPS,
        title = Text("支付组件日志", "Payment SDK logs"),
        summary = Text("阻止支付宝与微信支付日志", "Block Alipay and WeChat Pay logs"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("loggw-exsdk.alipay.com", "/loggw/logUpload.do"),
                UrlPrefix("cn-hangzhou-mas-log.cloud.alipay.com", "/loggw/logUpload.do"),
                UrlPrefix("log.wechatpay.cn", "/mmpayendpointreport/"),
            ),
        ),
        category = Category.DIAGNOSTICS,
        evidence = "dex: loggw-exsdk.alipay.com/loggw/logUpload.do in 10 apps (health, hybrid, player, tsmclient, " +
            "gamecenter…); cn-hangzhou-mas-log.cloud.alipay.com/loggw/logUpload.do (smarthome); log.wechatpay.cn/" +
            "mmpayendpointreport/opensdkreport (health, tsmclient). Not exercised in a live checkout",
        defaultOn = false,
        impact = Text("可能影响第三方支付流程", "May affect third-party payment flows"),
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
    Rule(
        id = "feedback.upload",
        packages = setOf("com.miui.bugreport", "com.miui.misightservice", "com.xiaomi.smarthome"),
        title = Text("问题反馈上传", "Feedback uploads"),
        summary = Text("阻止反馈与日志附件上传", "Block feedback and log attachments"),
        payload = RulePayload.Domains(listOf("feedback.miui.com", "feedback.intl.miui.com", "bugreport.xiaomi.net")),
        category = Category.DIAGNOSTICS,
        evidence = "traffic cap2/cap4: uid 1000 group -> feedback.miui.com x2 without user action (MiSight); dex " +
            "bugreport feedback.miui.com, bugreport.xiaomi.net; misightservice feedback.miui.com",
        defaultOn = false,
        impact = Text("无法提交问题反馈", "Feedback cannot be submitted"),
    ),
    Rule(
        id = "carrier-auth.sdk-logs",
        packages = setOf("com.baidu.input_mi", "com.sohu.inputmethod.sogou.xiaomi", "com.unionpay.tsmservice.mi"),
        title = Text("运营商登录日志", "Carrier login SDK logs"),
        summary = Text("阻止一键登录组件日志上报", "Block one-tap login SDK logs"),
        payload = RulePayload.Domains(listOf("collect.ux.21cn.com", "log1.cmpassport.com", "log2.cmpassport.com")),
        category = Category.DIAGNOSTICS,
        evidence = "evidence.md L811/L814 sogou collect.ux.21cn.com/collect/custom/accountMsg, log1.cmpassport.com; " +
            "L322 baidu log2.cmpassport.com; L819/L820 unionpay tsmservice. Login hosts (*.cmpassport.com auth, " +
            "open.e.189.cn) untouched",
    ),
    Rule(
        id = "globalbrowser.diagnostics",
        packages = setOf("com.mi.globalbrowser"),
        title = Text("国际版浏览器诊断", "Global browser diagnostics"),
        summary = Text("阻止性能监控与可靠性上报", "Block APM and reliability uploads"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("ams-howl.api.intl.miui.com", "/howl-api/apm/v3/"),
                UrlPrefix("clients2.google.com", "/domainreliability/upload"),
            ),
        ),
        category = Category.DIAGNOSTICS,
        evidence = "evidence.md L371 globalbrowser ams-howl.api.intl.miui.com/howl-api/apm/v3/, clients2.google.com/" +
            "domainreliability/upload (Chromium domain reliability)",
    ),
    Rule(
        id = "smarthome.extra-logs",
        packages = setOf("com.xiaomi.smarthome"),
        title = Text("米家附加日志", "Mi Home extra logs"),
        summary = Text("阻止崩溃、路由与验证码日志", "Block crash, router and captcha logs"),
        payload = RulePayload.Domains(
            listOf(
                "sg.stat.appmifile.com",
                "log.miwifi.com",
                "tw.log.miwifi.com",
                "monitor.geetest.com",
            ),
        ),
        category = Category.DIAGNOSTICS,
        evidence = "evidence.md L1067 smarthome sg.stat.appmifile.com, log.miwifi.com, tw.log.miwifi.com, " +
            "monitor.geetest.com; L1069 http://sg.stat.appmifile.com/micra/crash. Captcha (api/static.geetest.com) " +
            "untouched",
    ),
    Rule(
        id = "music.login-logs",
        packages = setOf("com.miui.player"),
        title = Text("QQ 登录日志", "QQ login logs"),
        summary = Text("阻止 QQ 登录组件日志", "Block QQ login SDK logs"),
        payload = RulePayload.Domains(listOf("log.wtlogin.qq.com", "log1.wtlogin.qq.com")),
        category = Category.DIAGNOSTICS,
        evidence = "evidence.md L646/L648 player log.wtlogin.qq.com, log1.wtlogin.qq.com (wtlogin SDK log)",
    ),
    Rule(
        id = "bdsms.log-upload",
        packages = setOf("com.xiaomi.bdsms"),
        title = Text("北斗短信日志", "BeiDou SMS logs"),
        summary = Text("阻止北斗短信组件日志上传", "Block BeiDou SMS SDK log uploads"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("sms.chinaspacetime.com.cn", "/scgateway/xwauth/bdmsg/sdk/v1/uploadBDLog"),
            ),
        ),
        category = Category.DIAGNOSTICS,
        evidence = "evidence.md L883 bdsms sms.chinaspacetime.com.cn/scgateway/xwauth/bdmsg/sdk/v1/uploadBDLog; " +
            "account status path on the same host untouched",
    ),
    Rule(
        id = "voicetrigger.log-upload",
        packages = setOf("com.miui.voicetrigger"),
        title = Text("唤醒日志上传", "Wake-word log uploads"),
        summary = Text("阻止语音唤醒日志上传", "Block voice-wake log uploads"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("pusherapi-iotdcm.xiaomixiaoai.com", "/iotdcm/pusher/tv/uploadlog"),
                UrlPrefix("preview-pusherapi-iotdcm.xiaomixiaoai.com", "/iotdcm/pusher/tv/uploadlog"),
            ),
        ),
        category = Category.DIAGNOSTICS,
        evidence = "evidence.md L776 voicetrigger {preview-,}pusherapi-iotdcm.xiaomixiaoai.com/iotdcm/pusher/tv/" +
            "uploadlog",
    ),
    Rule(
        id = "faceid.sdk-reports",
        packages = setOf("com.xiaomi.xmsf", "com.xiaomi.youpin"),
        title = Text("人脸核验日志", "Face-verify SDK logs"),
        summary = Text("阻止人脸核验组件日志", "Block face-verification SDK logs"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("verify.baidubce.com", "/verify/1.0/sdk/report"),
                UrlPrefix("sdk.faceid.qq.com", "/api/common/error-report"),
            ),
        ),
        category = Category.DIAGNOSTICS,
        evidence = "evidence.md L1111 youpin verify.baidubce.com/verify/1.0/sdk/report; L1102 xmsf sdk.faceid.qq.com/" +
            "api/common/error-report. Verification requests themselves untouched",
    ),
)
