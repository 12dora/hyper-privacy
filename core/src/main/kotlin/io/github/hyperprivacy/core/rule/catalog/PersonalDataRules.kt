package io.github.hyperprivacy.core.rule.catalog

import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.rule.UrlPrefix

/** Location, Wi-Fi / cell environment and other personal data uploads. */
internal val personalDataRules: List<Rule> = listOf(
    Rule(
        id = "location.collection",
        packages = LOCATION_COLLECTION_APPS,
        title = Text("定位数据采集", "Location data collection"),
        summary = Text("阻止轨迹与 Wi-Fi 基站采集", "Block trajectory and Wi-Fi/cell scans"),
        payload = RulePayload.Domains(
            listOf(
                "cgicol.amap.com",
                "logs.amap.com",
                "dualstack-logs.amap.com",
                "rttgpsreport.map.qq.com",
                "analytics.map.qq.com",
            ),
        ),
        category = Category.PERSONAL_DATA,
        evidence = "dex: cgicol.amap.com/collection/collectData (Wi-Fi/cell crowdsourcing) in 10+ apps; metoknlp " +
            "rttgpsreport.map.qq.com/report, analytics.map.qq.com; traffic cap4: mi.car.mobile -> " +
            "dualstack-logs.amap.com x4. Location lookup (apilocate, lbs.map.qq.com, loc.map.baidu.com) untouched",
    ),
    Rule(
        id = "location.sdk-stats",
        packages = LOCATION_SDK_STATS_APPS,
        title = Text("定位统计接口", "Location stats API"),
        summary = Text("阻止定位 SDK 统计上报", "Block location SDK statistics"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("loc.map.baidu.com", "/statloc"),
                UrlPrefix("api.map.baidu.com", "/sdkproxy/lbs_androidsdk/statistics/"),
                UrlPrefix("restsdk.amap.com", "/rest/lbs/pos/log/upload"),
                UrlPrefix("m5.amap.com", "/ws/shield/location/collection/"),
                UrlPrefix("m5.amap.com", "/ws/shield/rdr/log/upload"),
            ),
        ),
        category = Category.PERSONAL_DATA,
        evidence = "dex: metoknlp loc.map.baidu.com/statloc; hybrid/finddevice api.map.baidu.com/sdkproxy/" +
            "lbs_androidsdk/statistics; core restsdk.amap.com/rest/lbs/pos/log/upload; car m5.amap.com " +
            "location/collection/autodata, rdr/log/upload; traffic cap2: systemhelper -> loc.map.baidu.com x11",
    ),
    Rule(
        id = "securitycenter.traffic-sms",
        packages = setOf("com.miui.securitycenter"),
        title = Text("流量纠错短信", "Data-usage SMS reports"),
        summary = Text("阻止上传运营商短信原文", "Block uploads of carrier SMS text"),
        payload = RulePayload.Urls(listOf(UrlPrefix("api.sec.miui.com", "/v1/traffic/report"))),
        category = Category.PERSONAL_DATA,
        evidence = "jadx securitycenter: WebApiAccessHelper.reportTrafficCorrectionSms -> " +
            "api.sec.miui.com/v1/traffic/report (android id, province, city, carrier, SMS body); called from " +
            "TcSmsReportService and TrafficSimManager retry queue",
        defaultOn = false,
        impact = Text("无法提交流量纠错", "Data usage correction unavailable"),
    ),
    Rule(
        id = "securitycenter.quake-sensor",
        packages = setOf("com.miui.securitycenter"),
        title = Text("地震志愿者数据", "Quake volunteer data"),
        summary = Text("阻止上传传感器数据", "Block sensor data uploads"),
        payload = RulePayload.Urls(listOf(UrlPrefix("api.sec.miui.com", "/earthquake/volunteer/sensor/upload"))),
        category = Category.PERSONAL_DATA,
        evidence = "jadx securitycenter: api.sec.miui.com/earthquake/volunteer/sensor/upload (opt-in volunteer " +
            "program); warnings (srv.sec.miui.com/earthquake/warning/*) untouched",
        defaultOn = false,
        impact = Text("不再贡献地震监测数据", "Stops quake sensor contributions"),
    ),
    Rule(
        id = "sogou-ime.contacts",
        packages = setOf("com.sohu.inputmethod.sogou.xiaomi"),
        title = Text("通讯录上传", "Contacts upload"),
        summary = Text("阻止上传通讯录生成词库", "Block contact uploads for lexicons"),
        payload = RulePayload.Urls(listOf(UrlPrefix("srv.android.shouji.sogou.com", "/v1/contact/upload"))),
        category = Category.PERSONAL_DATA,
        evidence = "dex strings: srv.android.shouji.sogou.com/v1/contact/upload",
        defaultOn = false,
        impact = Text("无法生成云端通讯录词库", "Cloud contact lexicon cannot be built"),
    ),
    Rule(
        id = "location.uplink",
        packages = LOCATION_UPLINK_APPS,
        title = Text("定位长连上报", "Location uplink reports"),
        summary = Text("阻止轨迹长连与热点网格上报", "Block GPS uplink and hot-grid reports"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("itsdata.map.baidu.com", "/long-conn-gps/"),
                UrlPrefix("aloc-gnss-eph.amap.com", "/collect-hot-grid"),
                UrlPrefix("aloc-gnss-eph.aimap.com", "/collect-hot-grid"),
                UrlPrefix("api.map.baidu.com", "/lbs_sdkcc/report"),
            ),
        ),
        category = Category.PERSONAL_DATA,
        evidence = "evidence.md L803/L913 itsdata.map.baidu.com/long-conn-gps/sdk.php (systemhelper, finddevice); " +
            "L971 metoknlp aloc-gnss-eph.{amap,aimap}.com/collect-hot-grid (A-GPS host); L546/L913/L1053 " +
            "api.map.baidu.com/lbs_sdkcc/report. Not live-verified against Find Device / network location",
        defaultOn = false,
        impact = Text("可能影响查找设备与网络定位", "May affect Find Device and location"),
    ),
)
