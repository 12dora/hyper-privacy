package io.github.hyperprivacy.core.rule.catalog

import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.MethodPattern
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.rule.UrlPrefix

/** Event and behaviour tracking. */
internal val analyticsRules: List<Rule> = listOf(
    Rule(
        id = "analytics.firewall",
        packages = setOf("com.miui.analytics"),
        title = Text("系统分析服务", "System analytics"),
        summary = Text("禁止联网上报使用数据", "Cut off usage-data uploads"),
        payload = RulePayload.Firewall,
        category = Category.ANALYTICS,
        evidence = "uid 10155 (not shared). traffic cap2/cap3 idle: tracking.miui.com, sdkconfig.xiaomi.com, " +
            "open.e.kuaishou.com, unet.quark.cn; dex: tracking.miui.com/track/v1, pn-tracking-bak.onetrack.mi.com; " +
            "appops: FINE_LOCATION + WIFI_SCAN within the last hour; holds usage-stats, app-list and phone-state " +
            "permissions",
    ),
    Rule(
        id = "onetrack.domains",
        packages = ONETRACK_APPS,
        title = Text("OneTrack 统计", "OneTrack analytics"),
        summary = Text("阻止事件与应用列表上报", "Block event and app-list uploads"),
        payload = RulePayload.Domains(
            listOf(
                "tracking.miui.com",
                "tracking.intl.miui.com",
                "tracking.india.miui.com",
                "tracking.rus.miui.com",
                "tracking.eu.miui.com",
                "sdkconfig.xiaomi.com",
                "sdkconfig.intl.xiaomi.com",
                "sdkconfig.india.xiaomi.com",
                "sdkconfig.rus.xiaomi.com",
                "sdkconfig.eu.xiaomi.com",
                "pn-tracking-bak.onetrack.mi.com",
                "pn-sdkconfig-bak.onetrack.mi.com",
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "dex: Lcom/xiaomi/onetrack in 98 apps; hidden byte-array hosts " +
            "tracking.{,intl,india,rus,eu}.miui.com in 102 apps, sdkconfig.*.xiaomi.com in 68; path /track/appUpload " +
            "(installed-app list) in 20+ apps; traffic: tracking.miui.com (also unattributed in cap3); market dex " +
            "MIUI_LOG_URL=https://tracking.miui.com/tracks",
    ),
    Rule(
        id = "onetrack.method",
        packages = ONETRACK_SDK_APPS,
        title = Text("OneTrack 埋点", "OneTrack in-app events"),
        summary = Text("停止在应用内记录事件", "Stop in-app event logging"),
        payload = RulePayload.Method(
            className = "com.xiaomi.onetrack.OneTrack",
            method = MethodPattern.parse("track*"),
        ),
        category = Category.ANALYTICS,
        evidence = "dex: com.xiaomi.onetrack.OneTrack#track(String,Map), track(String,List,Map), trackPluginEvent, " +
            "trackServiceQualityEvent (all void); covers events relayed over IPC or direct-IP/HTTPDNS uploads",
    ),
    Rule(
        id = "xiaomi-analytics.method",
        packages = XIAOMI_ANALYTICS_APPS,
        title = Text("小米分析 SDK", "Xiaomi Analytics SDK"),
        summary = Text("停止记录广告与事件日志", "Stop ad and event logging"),
        payload = RulePayload.Method(
            className = "com.xiaomi.analytics.Analytics",
            method = MethodPattern.parse("track*"),
        ),
        category = Category.ANALYTICS,
        evidence = "dex: com.xiaomi.analytics.Analytics#trackAdAction/trackEventAction/trackCustomAction/trackSystem " +
            "(void); core updater https://sdkconfig.ad.xiaomi.com/api/checkupdate/lastusefulversion2 (market, " +
            "securitycenter)",
    ),
    Rule(
        id = "mistat.domains",
        packages = MISTAT_APPS,
        title = Text("小米统计", "MiStat"),
        summary = Text("阻止旧版统计数据上报", "Block legacy MiStat uploads"),
        payload = RulePayload.Domains(
            listOf(
                "data.mistat.xiaomi.com",
                "data.mistat.intl.xiaomi.com",
                "data.mistat.india.xiaomi.com",
                "data.mistat.rus.xiaomi.com",
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "dex: market MI_STAT_UPLOAD_URL data.mistat.xiaomi.com/mistats{,/v2,/v3}, /micrash; xmsf " +
            "HttpService region-url list data.mistat.{,intl,india,rus}; player test-idservice.data.mistat " +
            "(deviceid_get); sdk com/xiaomi/stat, com/xiaomi/mistatistic",
    ),
    Rule(
        id = "xiaoai.tracker",
        packages = XIAOAI_TRACKER_APPS,
        title = Text("小爱埋点", "XiaoAI tracker"),
        summary = Text("阻止 AI 功能使用埋点上报", "Block AI feature usage tracking"),
        payload = RulePayload.Domains(
            listOf(
                "tracker.xiaomixiaoai.com",
                "tracker-preview.xiaomixiaoai.com",
                "tracker.ai.xiaomi.com",
                "tracker-preview.ai.xiaomi.com",
                "log.xiaomixiaoai.com",
                "log-preview.xiaomixiaoai.com",
                "cnbj1-micollector-gateway.api.xiaomi.net",
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "traffic cap4: voiceassist -> tracker.xiaomixiaoai.com; dex: /track/v3, /track/perf/v2, " +
            "/track/uploadLog in camera, health, shop, notes, soundrecorder, personalassistant, accessibility, " +
            "carlink, voicetrigger, aicr; voiceassist log.xiaomixiaoai.com, micollector gateway",
    ),
    Rule(
        id = "xiaoai.track-api",
        packages = setOf("com.miui.voiceassist", "com.xiaomi.shop"),
        title = Text("小爱埋点接口", "XiaoAI tracking API"),
        summary = Text("阻止小爱接口中的埋点路径", "Block tracking paths on AI APIs"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("api.ai.xiaomi.com", "/track/"),
                UrlPrefix("api-preview.ai.xiaomi.com", "/track/"),
                UrlPrefix("api-staging.ai.xiaomi.com", "/track/"),
                UrlPrefix("api.xiaomixiaoai.com", "/track/"),
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "dex: voiceassist api.ai.xiaomi.com/track/h5, api.xiaomixiaoai.com/track/v2; shop " +
            "api-staging.ai.xiaomi.com/track/perf, hosts also serve AI features, so path-scoped",
    ),
    Rule(
        id = "sensorsdata.domains",
        packages = SENSORSDATA_APPS,
        title = Text("神策统计", "Sensors Analytics"),
        summary = Text("阻止神策数据埋点上报", "Block Sensors Data event uploads"),
        payload = RulePayload.Domains(
            listOf(
                "sensorsdata.10046.xiaomimobile.com",
                "analytics.95516.com",
                "one-track-app.10046.xiaomimobile.com",
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "dex: com/sensorsdata in contacts, virtualsim, tsmservice.mi, gamecenter.sdk.service, xmsf; " +
            "sensorsdata.10046.xiaomimobile.com/sa?project=production; traffic cap4: virtualsim -> " +
            "sensorsdata.10046, unionpay tsmservice -> analytics.95516.com",
    ),
    Rule(
        id = "tencent.beacon",
        packages = TENCENT_BEACON_APPS,
        title = Text("腾讯灯塔统计", "Tencent Beacon"),
        summary = Text("阻止腾讯灯塔事件上报", "Block Tencent Beacon event uploads"),
        payload = RulePayload.Domains(
            listOf(
                "otheve.beacon.qq.com",
                "othstr.beacon.qq.com",
                "oth.eve.mdt.qq.com",
                "oth.str.mdt.qq.com",
                "h.trace.qq.com",
                "pingma.qq.com",
                "htrace.wetvinfo.com",
                "log.qchannel03.cn",
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "dex: com/tencent/beacon (player, sogou); otheve/othstr.beacon.qq.com/analytics/v2_upload in " +
            "player, voiceassist, sogou, smarthome; h.trace.qq.com/kv (QQ open SDK) in 8 apps; pingma.qq.com (MTA) " +
            "in thememanager, smarthome; traffic cap3: h.trace.qq.com unattributed; evidence.md L811 sogou " +
            "htrace.wetvinfo.com, log.qchannel03.cn",
    ),
    Rule(
        id = "qq-opensdk.stats",
        packages = QQ_OPENSDK_STATS_APPS,
        title = Text("QQ 互联统计", "QQ Connect stats"),
        summary = Text("阻止 QQ 分享登录统计", "Block QQ share/login statistics"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("appsupport.qq.com", "/cgi-bin/appstage/mstats_batch_report"),
                UrlPrefix("openmobile.qq.com", "/user/user_login_statis"),
                UrlPrefix("cgi.qplus.com", "/report/"),
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "dex: appsupport.qq.com/cgi-bin/appstage/mstats_batch_report, openmobile.qq.com/user/" +
            "user_login_statis, cgi.qplus.com/report/report (QQ open SDK embedded in IMEs, player, voiceassist, " +
            "vipaccount, hybrid, gamecenter); share/login endpoints on the same hosts are left untouched",
    ),
    Rule(
        id = "tencent.rdelivery-report",
        packages = TENCENT_RDELIVERY_APPS,
        title = Text("腾讯配置回执", "RDelivery reports"),
        summary = Text("阻止配置下发回执上报", "Block config-delivery reports"),
        payload = RulePayload.Urls(listOf(UrlPrefix("rdelivery.qq.com", "/v1/sdk/report"))),
        category = Category.ANALYTICS,
        evidence = "dex: {,p.,t.}rdelivery.qq.com/v1/sdk/report in sogou, player; traffic cap3: sogou -> " +
            "rdelivery.qq.com, content.rconfig.qq.com; config pull (/v1/config/pull, /v3/config/pull) stays allowed",
    ),
    Rule(
        id = "bytedance.applog",
        packages = BYTEDANCE_APPLOG_APPS,
        title = Text("字节 AppLog", "ByteDance AppLog"),
        summary = Text("阻止字节跳动行为统计", "Block ByteDance behavior analytics"),
        payload = RulePayload.Domains(
            listOf(
                "applog.snssdk.com",
                "rtapplog.snssdk.com",
                "rtlog.snssdk.com",
                "log.snssdk.com",
                "toblog-alink.ctobsnssdk.com",
                "applog.zijieapi.com",
                "log.zijieapi.com",
                "log.byteoversea.com",
                "analytics.tiktok.com",
                "analytics.us.tiktok.com",
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "dex: com/bytedance/applog (browser, smarthome); applog/rtapplog/rtlog/log.snssdk.com/monitor/" +
            "collect/c/session, log_settings in browser; applog/log.zijieapi.com in health, gamecenter.sdk.service; " +
            "globalbrowser log.byteoversea.com, analytics.tiktok.com. ichannel.snssdk.com (device registration, " +
            "Douyin Pay) left allowed",
    ),
    Rule(
        id = "baidu.mobstat",
        packages = setOf("com.baidu.input_mi", "com.xiaomi.youpin"),
        title = Text("百度移动统计", "Baidu Mobile Stats"),
        summary = Text("阻止百度移动统计上报", "Block Baidu Mobile Stats uploads"),
        payload = RulePayload.Domains(listOf("hmma.baidu.com", "sdkss.shitu.baidu.com")),
        category = Category.ANALYTICS,
        evidence = "dex: baidu.input_mi com.baidu.mobstat -> https://hmma.baidu.com/app.gif (evidence.md L322 " +
            "hmma.baidu.com); youpin sdkss.shitu.baidu.com/cgi-bin/sdkstat.py",
    ),
    Rule(
        id = "baidu-ime.stats",
        packages = setOf("com.baidu.input_mi"),
        title = Text("百度输入法统计", "Baidu IME statistics"),
        summary = Text("阻止输入行为统计上报", "Block typing-behavior statistics"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("mime.baidu.com", "/v5/fb/st"),
                UrlPrefix("mime.baidu.com", "/v5/fb/rt"),
                UrlPrefix("mime.baidu.com", "/v5/fb/vt"),
                UrlPrefix("mime.baidu.com", "/v5/fb/an"),
                UrlPrefix("mime.baidu.com", "/v5/fb/custom"),
                UrlPrefix("mime.baidu.com", "/v5/performance/info"),
                UrlPrefix("mime.baidu.com", "/v5/skinV2/statistics"),
                UrlPrefix("mime.baidu.com", "/sapiv2/hf/pub/pvreport"),
                UrlPrefix("mime.baidu.com", "/sapiv2/hf/pub/pdreport"),
                UrlPrefix("mime-sh.baidu.com", "/v5/fb/st"),
                UrlPrefix("mime-sh.baidu.com", "/v5/fb/rt"),
                UrlPrefix("mime-sh.baidu.com", "/v5/fb/vt"),
                UrlPrefix("mime-sh.baidu.com", "/v5/fb/an"),
                UrlPrefix("mime-sh.baidu.com", "/v5/fb/custom"),
                UrlPrefix("mime-sh.baidu.com", "/v5/performance/info"),
                UrlPrefix("mime-sh.baidu.com", "/v5/skinV2/statistics"),
                UrlPrefix("mime-sh.baidu.com", "/sapiv2/hf/pub/pvreport"),
                UrlPrefix("mime-sh.baidu.com", "/sapiv2/hf/pub/pdreport"),
                UrlPrefix("pmime.baidu.com", "/v5/fb/st"),
                UrlPrefix("pmime.baidu.com", "/v5/fb/rt"),
                UrlPrefix("pmime.baidu.com", "/v5/fb/vt"),
                UrlPrefix("pmime.baidu.com", "/v5/fb/an"),
                UrlPrefix("pmime.baidu.com", "/v5/fb/custom"),
                UrlPrefix("pmime.baidu.com", "/v5/performance/info"),
                UrlPrefix("pmime.baidu.com", "/v5/skinV2/statistics"),
                UrlPrefix("pmime.baidu.com", "/sapiv2/hf/pub/pvreport"),
                UrlPrefix("pmime.baidu.com", "/sapiv2/hf/pub/pdreport"),
                UrlPrefix("upl.baidu.com", "/slp/upload_monitor"),
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "dex: Retrofit service yy0.j @POST /v5/fb/{st,rt,strt,rtq,vt} (multipart cfile log, log_type=…), " +
            "/sapiv2/hf/pub/{pv,pd}report; lp.a /v5/performance/info; am0.e /v5/skinV2/statistics; speech " +
            "AnalysisUpload upl.baidu.com/slp/upload_monitor; traffic cap3 typing: mime.baidu.com x6. Cloud input, " +
            "skins and voice endpoints on mime.baidu.com stay allowed",
    ),
    Rule(
        id = "sogou-ime.pingback",
        packages = setOf("com.sohu.inputmethod.sogou.xiaomi"),
        title = Text("搜狗输入法统计", "Sogou IME pingback"),
        summary = Text("阻止输入与活跃统计上报", "Block typing and activity pingbacks"),
        payload = RulePayload.Domains(
            listOf(
                "ping.android.shouji.sogou.com",
                "ping-android.shouji.sogou.com",
                "ping-andrdata.shouji.sogou.com",
                "ping-monitor.shouji.sogou.com",
                "ping-msganr.android.shouji.sogou.com",
                "ping-yuyin.android.shouji.sogou.com",
                "ping-biz.shouji.sogou.com",
                "data.ping.shouji.sogou.com",
                "kpi2.android.shouji.sogou.com",
                "breaklog.android.shouji.sogou.com",
                "newalivelog.android.shouji.sogou.com",
                "requality.android.shouji.sogou.com",
                "foreignpb.android.shouji.sogou.com",
                "androidsdk.ping.mse.sogou.com",
                "androidsdkmini.ping.mse.sogou.com",
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "dex strings: ping-android.shouji.sogou.com/pingback/index.php, kpi2.gif, breaklog.gif, " +
            "newversionalive.gif, bigdata.gif, ping-biz deviceinfo.gif, apd.ping appinfo.gif (app info), " +
            "ping-andrdata mpkg.gif, requality api_monitor.gif, foreignpb finput_trace.gif. Cloud input " +
            "(ws-keyboard.shouji.sogou.com) untouched",
    ),
    Rule(
        id = "sogou-ime.alive",
        packages = setOf("com.sohu.inputmethod.sogou.xiaomi"),
        title = Text("搜狗激活上报", "Sogou activation reports"),
        summary = Text("阻止安装激活与日志收集", "Block install/activation and log reports"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("api.shouji.sogou.com", "/v1/alive/"),
                UrlPrefix("api-android.shouji.sogou.com", "/v1/alive/"),
                UrlPrefix("srv.android.shouji.sogou.com", "/v1/dex/collectionLog"),
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "dex strings: api.shouji.sogou.com/v1/alive/{install,login,trackInstall}, " +
            "api-android.shouji.sogou.com/v1/alive/special_alive, srv.android.shouji.sogou.com/v1/dex/collectionLog",
    ),
    Rule(
        id = "iflytek-ime.stats",
        packages = setOf("com.iflytek.inputmethod.miui"),
        title = Text("讯飞输入法统计", "iFlytek IME statistics"),
        summary = Text("阻止讯飞统计与日志上报", "Block iFlytek stats and log uploads"),
        payload = RulePayload.Domains(
            listOf(
                "inputd.xfinfr.com",
                "inputl1.xfinfr.com",
                "inputl2.xfinfr.com",
                "log.voicecloud.cn",
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "dex: com.iflytek.statssdk.c.b endpoint table: 1017 anonlogin -> inputd.xfinfr.com/do, 1018 " +
            "active / 1002 uplog -> inputl1.xfinfr.com/do, default -> inputl2.xfinfr.com/do, 9003 -> " +
            "log.voicecloud.cn/do (evidence.md L349); traffic cap3 typing: inputd.xfinfr.com x5. Cloud input uses " +
            "authsh.xfinfr.com tokens and imeclient.openspeech.cn; the 1017 anonlogin lives inside " +
            "com.iflytek.statssdk only",
    ),
    Rule(
        id = "browser.search-log",
        packages = setOf("com.android.browser", "com.android.quicksearchbox"),
        title = Text("搜索日志", "Search logs"),
        summary = Text("阻止搜索与浏览日志上报", "Block search and browsing logs"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("global.search.xiaomi.net", "/log/"),
                UrlPrefix("r.browser.miui.com", "/log/"),
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "dex: browser and quicksearchbox global.search.xiaomi.net/log/v1/collect; browser " +
            "r.browser.miui.com/log/, /log/et",
    ),
    Rule(
        id = "securitycenter.stats",
        packages = SECURITY_CENTER_APPS,
        title = Text("安全中心统计", "Security app stats"),
        summary = Text("阻止功能统计与充电数据", "Block feature and charging stats"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("data.sec.miui.com", "/data/upload"),
                UrlPrefix("data.sec.miui.com", "/data/chargeV2"),
                UrlPrefix("data.sec.miui.com", "/stat/report"),
                UrlPrefix("api.sec.miui.com", "/redPacket/reportStatus"),
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "jadx securitycenter: monthreport TaskManager -> data.sec.miui.com/data/upload (module events); " +
            "ChargeReporter -> /data/chargeV2 (biz_id charge_stat); luckymoney UploadConfigAsyncTask -> " +
            "api.sec.miui.com/redPacket/reportStatus (IMEI md5 + switch state); cleanmaster dex /stat/report. " +
            "/privacy/agree|get|revoke consent paths untouched",
    ),
    Rule(
        id = "sms.richmsg-track",
        packages = setOf("com.android.mms"),
        title = Text("智能短信曝光", "Smart SMS impressions"),
        summary = Text("阻止短信卡片曝光上报", "Block SMS card impression reports"),
        payload = RulePayload.Urls(listOf(UrlPrefix("client.sms.miui.com", "/api/client/richMsg/aimMsgShowRpt"))),
        category = Category.ANALYTICS,
        evidence = "jadx mms: RichSmsRequestHelper TRACK_URL https://client.sms.miui.com/api/client/richMsg/" +
            "aimMsgShowRpt; traffic cap4: mms -> client.sms.miui.com x7. Smart-SMS templates/config on the same host " +
            "stay allowed",
    ),
    Rule(
        id = "mms.5g-stats",
        packages = setOf("com.android.mms"),
        title = Text("5G 消息统计", "5G messaging stats"),
        summary = Text("阻止消息组件埋点上报", "Block messaging SDK tracking"),
        payload = RulePayload.Urls(listOf(UrlPrefix("logsdata.cxkfwn.com", "/statistics/uploadlogInfo"))),
        category = Category.ANALYTICS,
        evidence = "jadx mms f.a: \"bpoint\" tracking uploader -> https://logsdata.cxkfwn.com:11443/statistics/" +
            "uploadlogInfo (evidence.md L154); /statistics/auth/getsynconfinfo on the same host stays allowed",
    ),
    Rule(
        id = "music.stats",
        packages = setOf("com.miui.player"),
        title = Text("音乐播放统计", "Music playback stats"),
        summary = Text("阻止播放与性能数据上报", "Block playback and performance stats"),
        payload = RulePayload.Domains(
            listOf(
                "report.tencentmusic.com",
                "stat.y.qq.com",
                "stat.3g.music.qq.com",
                "stats.music.xiaomi.com",
                "wnslogupload.twns.qq.com",
                "logger.qingting.fm",
                "apd-cable-monitor.teg.tencent-cloud.net",
                "apd-pcdnvodstat.teg.tencent-cloud.net",
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "traffic cap2/cap4: player -> report.tencentmusic.com x3, stats.music.xiaomi.com; dex: " +
            "report.tencentmusic.com/api/v1/perf, stat.3g.music.qq.com/fcgi-bin, wnslogupload.twns.qq.com/" +
            "wns_log_upload, logger.qingting.fm/logger.php, apd-*.teg.tencent-cloud.net data_report",
    ),
    Rule(
        id = "music.stats-api",
        packages = setOf("com.miui.player"),
        title = Text("音乐统计接口", "Music stats API"),
        summary = Text("阻止播放流水与日志上报", "Block play-flow and log reports"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("p.tencentmusic.com", "/report/"),
                UrlPrefix("wnsop.twns.qq.com", "/api/v1/upload"),
                UrlPrefix("wnsop.twns.qq.com", "/api/v1/logUploadResult"),
                UrlPrefix("musict.proxy.music.qq.com", "/qmtm2/PlayPerformanceReport"),
                UrlPrefix("db.kg.qq.com", "/wns_rtlog_see/"),
                UrlPrefix("o2o.api.xiaomi.com", "/tracker/"),
                UrlPrefix("i.gtimg.cn", "/ams-web/page-performance/"),
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "dex: p.tencentmusic.com/report/api/v{1,2}/{flow,jobReport}; wnsop.twns.qq.com/api/v1/{upload," +
            "uploadQualityData,logUploadResult}; PlayPerformanceReport.fcg; o2o.api.xiaomi.com/tracker/; traffic " +
            "cap2/cap4: p.tencentmusic.com, wnsop.twns.qq.com; cap2/cap4 HTTP GET i.gtimg.cn/ams-web/" +
            "page-performance/page-performance.min.js (perf beacon script). Streaming/API paths on these hosts " +
            "untouched",
    ),
    Rule(
        id = "shop.tracking",
        packages = SHOP_APPS,
        title = Text("商城埋点", "Mi Store tracking"),
        summary = Text("阻止浏览与点击埋点上报", "Block browsing and click tracking"),
        payload = RulePayload.Domains(
            listOf(
                "beats.be.mi.com",
                "tk.d.mi.com",
                "a.hl.mi.com",
                "a-hl.mi.com",
                "t.hl.mifile.cn",
                "stat.youpin.mi.com",
                "log.youpin.mi.com",
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "traffic cap4: shop -> beats.be.mi.com x27, a.hl.mi.com x21 (POST /m), tk.d.mi.com x6, " +
            "t.hl.mifile.cn (POST /c); youpin -> stat.youpin.mi.com x19. jadx shop: SpmStatConfig SPM_URL " +
            "tk.d.mi.com/app/dcv2, setUrl beats.be.mi.com/mistore, mobilestats BPM_URL a-hl.mi.com/m, " +
            "ConnectivityStatManager /c; youpin hawkeye UPLOAD_URL stat.youpin.mi.com/0.gif",
    ),
    Rule(
        id = "shop.visit-stats",
        packages = setOf("com.xiaomi.shop", "com.xiaomi.youpin"),
        title = Text("商城访问统计", "Store visit stats"),
        summary = Text("阻止页面访问统计上报", "Block page-visit statistics"),
        payload = RulePayload.Urls(listOf(UrlPrefix("shopapi.io.mi.com", "/app/stat/"))),
        category = Category.ANALYTICS,
        evidence = "dex: shopapi.io.mi.com/app/stat/visit, /visitv2 in shop and youpin; rest of shopapi.io.mi.com " +
            "(shopping) allowed",
    ),
    Rule(
        id = "payment.checkout-track",
        packages = setOf("com.xiaomi.payment", "com.xiaomi.xmsf"),
        title = Text("收银台埋点", "Checkout tracking"),
        summary = Text("阻止支付页面行为埋点", "Block checkout page tracking"),
        payload = RulePayload.Domains(listOf("track.ucashier.mipay.com")),
        category = Category.ANALYTICS,
        evidence = "dex: https://track.ucashier.mipay.com in xmsf and payment (evidence.md L1031, L1102); not " +
            "exercised in a live checkout",
        defaultOn = false,
        impact = Text("可能影响小米支付收银台", "May affect Mi Pay checkout"),
    ),
    Rule(
        id = "email.analytics",
        packages = setOf("com.android.email"),
        title = Text("邮件统计", "Email analytics"),
        summary = Text("阻止邮箱组件行为统计", "Block mail SDK usage analytics"),
        payload = RulePayload.Domains(
            listOf(
                "dw-online.ksosoft.com",
                "collect.wpsmail.net",
                "dw-collect-debug.ksord.com",
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "traffic cap4: email -> dw-online.ksosoft.com x2; dex: collect.wpsmail.net/reporter, " +
            "dw-collect-debug.ksord.com",
    ),
    Rule(
        id = "milink.cast-stats",
        packages = setOf("com.milink.service"),
        title = Text("投屏统计", "Casting analytics"),
        summary = Text("阻止投屏组件统计上报", "Block casting SDK statistics"),
        payload = RulePayload.Domains(listOf("logu.lebo.cn", "logu.hpplay.cn", "test01-logu.hpplay.cn")),
        category = Category.ANALYTICS,
        evidence = "dex: milink.service logu.lebo.cn, test01-logu.hpplay.cn (Lebo/HPPlay cast SDK log)",
    ),
    Rule(
        id = "xunlei.stats",
        packages = setOf(
            "com.android.browser",
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
        ),
        title = Text("迅雷下载统计", "Xunlei download stats"),
        summary = Text("阻止下载加速统计上报", "Block download-acceleration stats"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("etl-xlmc-ssl.sandai.net", "/api/stat/"),
                UrlPrefix("api.tw06.xlmc.sandai.net", "/api/stat/"),
                UrlPrefix("api.xlmc.sandai.net", "/api/stat/"),
                UrlPrefix("api-shoulei-ssl.xunlei.com", "/xlppc.resourcepush.api/report/"),
                UrlPrefix("sj-m-ssl.xunlei.com", "/h5/report"),
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "traffic cap2/cap4: downloads group and downloads.ui -> etl-xlmc-ssl.sandai.net; dex: " +
            "etl-xlmc-ssl.sandai.net/api/stat/rt, pre.api.tw06.xlmc.sandai.net/api/stat/rt, browser " +
            "api-shoulei-ssl.xunlei.com/xlppc.resourcepush.api/report/task, sj-m-ssl.xunlei.com/h5/report. Download " +
            "acceleration itself untouched",
    ),
    Rule(
        id = "huawei.analytics",
        packages = SHOP_APPS,
        title = Text("华为分析", "Huawei Analytics"),
        summary = Text("阻止华为 HMS 统计上报", "Block Huawei HMS analytics"),
        payload = RulePayload.Domains(
            listOf(
                "metrics.data.hicloud.com",
                "metrics1.data.hicloud.com",
                "metrics2.data.hicloud.com",
                "metrics5.data.hicloud.com",
                "metrics-dra.dt.hicloud.com",
                "metrics1-drcn.dt.dbankcloud.cn",
                "metrics5.dt.dbankcloud.ru",
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "dex: metrics*.data.hicloud.com, metrics-dra.dt.hicloud.com, metrics1-drcn.dt.dbankcloud.cn in " +
            "shop, smarthome, youpin",
    ),
    Rule(
        id = "firebase.analytics",
        packages = FIREBASE_ANALYTICS_APPS,
        title = Text("Firebase 统计", "Firebase Analytics"),
        summary = Text("阻止 Firebase 与 GA 统计", "Block Firebase and GA analytics"),
        payload = RulePayload.Domains(
            listOf(
                "app-measurement.com",
                "google-analytics.com",
                "firebaselogging.googleapis.com",
                "firebaselogging-pa.googleapis.com",
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "dex: com/google/firebase/analytics in 6 vendor apps; google-analytics.com in browser, " +
            "globalbrowser, greenguard. firebaseinstallations/FCM left alone (push)",
    ),
    Rule(
        id = "baidu-ime.ubc",
        packages = setOf("com.baidu.input_mi"),
        title = Text("百度 UBC 日志", "Baidu UBC logs"),
        summary = Text("阻止百度 UBC 行为日志", "Block Baidu UBC behavior logs"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("tcbox.baidu.com", "/ztbox"),
                UrlPrefix("h2tcbox.baidu.com", "/ztbox"),
                UrlPrefix("mbd.baidu.com", "/ztbox"),
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "jadx baidu.input_mi com.baidu.ubc Constants UPLOAD_URL_PATH \"/ztbox?action=zubc\", UBCHelper -> " +
            "{h2,}tcbox.baidu.com/ztbox?action=zubc (evidence.md L325); traffic cap3 typing: mbd.baidu.com x3 " +
            "(L321). /ztbox multiplexes other action= services and the matcher ignores query strings",
        defaultOn = false,
        impact = Text("可能影响百度部分云端功能", "May affect other Baidu ztbox services"),
    ),
    Rule(
        id = "milink.cast-report",
        packages = setOf("com.milink.service"),
        title = Text("跨品牌投屏上报", "Cross-brand cast reports"),
        summary = Text("阻止投屏连接与镜像上报", "Block cast connection/mirror reports"),
        payload = RulePayload.Urls(listOf(UrlPrefix("upnp-api.vivo.com.cn", "/v1/report/"))),
        category = Category.ANALYTICS,
        evidence = "evidence.md L388 milink.service upnp-api.vivo.com.cn/v1/report/{conn,initialization,mirror," +
            "relation}",
    ),
    Rule(
        id = "onetrack.pubsub",
        packages = ONETRACK_PUBSUB_APPS,
        title = Text("OneTrack 云端通道", "OneTrack Pub/Sub uplink"),
        summary = Text("阻止经谷歌云通道上报事件", "Block events sent via Google Pub/Sub"),
        payload = RulePayload.Domains(listOf("pubsub.googleapis.com")),
        category = Category.ANALYTICS,
        evidence = "evidence.md L532 home: pubsub.googleapis.com/api/v1/token + hyper_one_track::pubsub::uploader " +
            "upload_ps_data; L511 gallery: hyper_common/one_track/src/pubsub/secret_key.rs + embedded RSA key (Rust " +
            "OneTrack); hidden byte-array https://pubsub.googleapis.com next to tracking.*/sdkconfig.* in 17 Xiaomi " +
            "apps (L39, L183, L274, …). Scoped to Xiaomi packages only; GMS, Play and Google apps are never targeted",
    ),
    Rule(
        id = "globalbrowser.telemetry",
        packages = setOf("com.mi.globalbrowser"),
        title = Text("国际版浏览器统计", "Global browser analytics"),
        summary = Text("阻止浏览器监控与合作方统计", "Block browser monitor/partner stats"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("api.brs.intl.miui.com", "/browser/monitor/"),
                UrlPrefix("api-partners.kindred.co", "/partners/analytics/track/"),
                UrlPrefix("csi.gstatic.com", "/csi"),
            ),
        ),
        category = Category.ANALYTICS,
        evidence = "evidence.md L371 globalbrowser api.brs.intl.miui.com/browser/monitor/v1|v2, " +
            "api-partners.kindred.co/partners/analytics/track/v2/couponevents, csi.gstatic.com/csi (L368); traffic " +
            "cap4 api.brs.intl.miui.com x17 (other paths are browser services)",
    ),
    Rule(
        id = "smarthome.ximalaya-stats",
        packages = setOf("com.xiaomi.smarthome"),
        title = Text("喜马拉雅播放统计", "Ximalaya play stats"),
        summary = Text("阻止音频播放记录上报", "Block audio play-record reports"),
        payload = RulePayload.Urls(listOf(UrlPrefix("api.ximalaya.com", "/openapi-collector-app/"))),
        category = Category.ANALYTICS,
        evidence = "evidence.md L1069 smarthome api.ximalaya.com/openapi-collector-app/{live,track}_single_record; " +
            "openapi-gateway-app (playback) untouched",
    ),
    Rule(
        id = "thirdparty.analytics",
        packages = setOf("com.mi.car.mobile", "com.mi.health", "com.miui.core"),
        title = Text("第三方统计 SDK", "Third-party analytics"),
        summary = Text("阻止阿里 UT 等统计上报", "Block Alibaba UT and similar stats"),
        payload = RulePayload.Domains(listOf("mmstat.com", "cg.play-analytics.com")),
        category = Category.ANALYTICS,
        evidence = "evidence.md L361 mi.car.mobile mmstat.com, L496 miui.core mmstat.com (Alibaba UT/AMap), L377 " +
            "health cg.play-analytics.com",
    ),
)
