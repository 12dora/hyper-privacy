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
                "itsdata.map.baidu.com",
                "rttgpsreport.map.qq.com",
                "analytics.map.qq.com",
            ),
        ),
        category = Category.PERSONAL_DATA,
        evidence = "dex: cgicol.amap.com/collection/collectData (Wi-Fi/cell crowdsourcing) in 10+ apps; metoknlp " +
            "rttgpsreport.map.qq.com/report, analytics.map.qq.com; systemhelper itsdata.map.baidu.com/long-conn-gps; " +
            "traffic cap4: mi.car.mobile -> dualstack-logs.amap.com x4. Location lookup (apilocate, lbs.map.qq.com, " +
            "loc.map.baidu.com) untouched",
    ),
    Rule(
        id = "location.sdk-stats",
        packages = LOCATION_SDK_STATS_APPS,
        title = Text("定位统计接口", "Location stats API"),
        summary = Text("阻止定位 SDK 统计上报", "Block location SDK statistics"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("loc.map.baidu.com", "/statloc"),
                UrlPrefix("api.map.baidu.com", "/lbs_sdkcc/report"),
                UrlPrefix("api.map.baidu.com", "/sdkproxy/lbs_androidsdk/statistics/"),
                UrlPrefix("aloc-gnss-eph.amap.com", "/collect-hot-grid"),
                UrlPrefix("aloc-gnss-eph.aimap.com", "/collect-hot-grid"),
                UrlPrefix("restsdk.amap.com", "/rest/lbs/pos/log/upload"),
                UrlPrefix("m5.amap.com", "/ws/shield/location/collection/"),
                UrlPrefix("m5.amap.com", "/ws/shield/rdr/log/upload"),
            ),
        ),
        category = Category.PERSONAL_DATA,
        evidence = "dex: metoknlp loc.map.baidu.com/statloc, aloc-gnss-eph collect-hot-grid; hybrid/finddevice " +
            "api.map.baidu.com/lbs_sdkcc/report, sdkproxy statistics; core restsdk.amap.com/rest/lbs/pos/log/upload; " +
            "car m5.amap.com location/collection/autodata, rdr/log/upload; traffic cap2: systemhelper -> " +
            "loc.map.baidu.com x11",
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
)
