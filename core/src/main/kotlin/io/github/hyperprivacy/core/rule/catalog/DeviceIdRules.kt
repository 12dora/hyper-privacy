package io.github.hyperprivacy.core.rule.catalog

import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.IdentifierSource
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.rule.UrlPrefix

/** Device identifier collection. */
internal val deviceIdRules: List<Rule> = listOf(
    Rule(
        id = "idprovider.identifiers",
        packages = setOf("com.miui.securitycenter"),
        title = Text("匿名设备标识", "Anonymous device IDs"),
        summary = Text("向第三方应用返回空标识", "Return blank OAID/VAID to apps"),
        payload = RulePayload.Identifiers(IdentifierSource.MIUI_ID_PROVIDER),
        category = Category.DEVICE_ID,
        evidence = "dex: com/miui/idprovider in securitycenter serves content://com.miui.idprovider; consumers via " +
            "com/android/id/IdentifierManager (7 apps) and com/bun/miitmdid (baidu IME, sogou, smarthome, youpin)",
    ),
    Rule(
        id = "qimei.domains",
        packages = TENCENT_RDELIVERY_APPS,
        title = Text("腾讯 QIMEI", "Tencent QIMEI"),
        summary = Text("阻止腾讯设备指纹采集", "Block Tencent device fingerprinting"),
        payload = RulePayload.Domains(listOf("snowflake.qq.com")),
        category = Category.DEVICE_ID,
        evidence = "traffic cap3 typing: sogou -> snowflake.qq.com x3 (/ola/v2); dex com/tencent/qimei, libqimei.so " +
            "in sogou, player",
    ),
    Rule(
        id = "sofire.domains",
        packages = setOf("com.baidu.input_mi"),
        title = Text("百度设备指纹", "Baidu device fingerprint"),
        summary = Text("阻止百度安全设备指纹", "Block Baidu Sofire fingerprinting"),
        payload = RulePayload.Domains(listOf("sofire.baidu.com")),
        category = Category.DEVICE_ID,
        evidence = "traffic cap2: baidu.input_mi -> sofire.baidu.com; dex com.baidu.sofire.* (device risk " +
            "fingerprint, sofire.ac.provider)",
    ),
    Rule(
        id = "getui.collect",
        packages = setOf("com.xiaomi.smarthome"),
        title = Text("个推数据采集", "Getui data collection"),
        summary = Text("阻止设备与场景数据采集", "Block device and scene data collection"),
        payload = RulePayload.Domains(
            listOf(
                "ido.gepush.com",
                "c-ido.gepush.com",
                "ido.getui.net",
                "c-ido.getui.net",
                "gtc.gepush.com",
                "b-gtc.gepush.com",
                "c-gtc.gepush.com",
                "gtc.getui.net",
                "b-gtc.getui.net",
                "c-gtc.getui.net",
                "oneid.gepush.com",
                "oneid.getui.net",
                "b-qj.gepush.com",
            ),
        ),
        category = Category.DEVICE_ID,
        evidence = "traffic cap4: smarthome -> ido.gepush.com x3, c-ido.gepush.com, c-gtc.getui.net, " +
            "b-gtc.getui.net, cdn-sdk-opt.getui.com/tdata_*; dex com.getui.{gtc,oneid,gs}, b-qj.gepush.com/" +
            "doslogcenter/json/hwscene. One-tap login sdk-open-phone.getui.com untouched",
    ),
    Rule(
        id = "uaid.report",
        packages = setOf("com.xiaomi.smarthome"),
        title = Text("运营商统一标识", "Carrier unified ID"),
        summary = Text("阻止设备标识与渠道上报", "Block device ID and channel reports"),
        payload = RulePayload.Domains(listOf("zxid-m.mobileservice.cn")),
        category = Category.DEVICE_ID,
        evidence = "dex: smarthome zxid-m.mobileservice.cn/sdk/uaid/reportAuthToken, /sdk/channel/report",
    ),
    Rule(
        id = "telecom-auth.report",
        packages = setOf("com.mi.car.mobile"),
        title = Text("电信认证上报", "Telecom auth reports"),
        summary = Text("阻止一键登录设备信息上报", "Block one-tap login device reports"),
        payload = RulePayload.Urls(
            listOf(
                UrlPrefix("auni.telecome.cn", "/a/daa/report"),
                UrlPrefix("auni.telecome.cn", "/a/dai/report"),
            ),
        ),
        category = Category.DEVICE_ID,
        evidence = "traffic cap4: mi.car.mobile -> auni.telecome.cn POST /a/daa/report, /a/dai/report (device/app " +
            "info); the login check /a/audd/valid stays allowed",
    ),
)
