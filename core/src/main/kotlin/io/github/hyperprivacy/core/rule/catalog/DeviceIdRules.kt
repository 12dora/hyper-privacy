package io.github.hyperprivacy.core.rule.catalog

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.IdentifierSource
import io.github.hyperprivacy.core.rule.MethodPattern
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
        id = "settings.identifiers",
        packages = setOf(Catalog.SYSTEM_SCOPE),
        title = Text("隐藏设置中的标识", "Hide IDs in settings"),
        summary = Text("向第三方隐藏账号与设备标识", "Hides account and device IDs from apps"),
        payload = RulePayload.Identifiers(IdentifierSource.SETTINGS_KEYS),
        category = Category.DEVICE_ID,
        evidence = "decompiled SettingsProvider: checkReadableAnnotation only gates AOSP-declared keys, and " +
            "getAll{Global,Secure,System}Settings let any app enumerate vendor keys; on-device Settings hold " +
            "ad_aaid, key_mqs_uuid, pref_mipub_random_device_id, last_synced_account, virtualsim_account_rule_<id>, " +
            "miui_home_hide_app_list (docs/collection-techniques.md 2.3, 10.2)",
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
            "fingerprint, sofire.ac.provider); Baidu Passport login uses Sofire risk tokens",
        impact = Text("输入法内百度账号登录可能失败", "Baidu sign-in in the IME may fail"),
        highRisk = true,
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
                "cdn-sdk-opt.getui.com",
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
        summary = Text("阻止设备渠道信息上报", "Block device channel reports"),
        payload = RulePayload.Urls(listOf(UrlPrefix("zxid-m.mobileservice.cn", "/sdk/channel/report"))),
        category = Category.DEVICE_ID,
        evidence = "path scan (applist_urls) smarthome zxid-m.mobileservice.cn/sdk/channel/report; " +
            "/sdk/uaid/reportAuthToken left allowed (one-tap login token); traffic cap2 (third-party app) " +
            "zxid-m.mobileservice.cn",
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
    Rule(
        id = "simactivate.firewall",
        packages = setOf("com.xiaomi.simactivate.service"),
        title = Text("SIM 激活上报", "SIM activation reports"),
        summary = Text("禁止上报 SIM 卡与号码信息", "Cut off SIM and number reporting"),
        payload = RulePayload.Firewall,
        category = Category.DEVICE_ID,
        evidence = "uid 10205 (not shared). traffic cap4: -> cnac.account.xiaomi.com; dex %s/pass/activation/report",
        defaultOn = false,
        impact = Text("SIM 快捷登录可能失效", "SIM quick sign-in may fail"),
    ),
    Rule(
        id = "misight.trial-check",
        packages = setOf("com.miui.misightservice"),
        title = Text("内测设备核验", "Beta device check"),
        summary = Text("阻止以 OAID 查询内测资格", "Block OAID-based beta-device checks"),
        payload = RulePayload.Urls(listOf(UrlPrefix("data.pt.xiaomi.com", "/dataplatform/api/v1/trial-records/"))),
        category = Category.DEVICE_ID,
        evidence = "evidence.md L581 misightservice data.pt.xiaomi.com/dataplatform/api/v1/trial-records/oaid/check " +
            "(sends OAID)",
    ),
    Rule(
        id = "systemhelper.firewall",
        packages = setOf("com.mobiletools.systemhelper"),
        title = Text("联通终端管理", "Unicom device reporting"),
        summary = Text("禁止上报设备标识与位置", "Blocks device ID and location uploads"),
        payload = RulePayload.Firewall,
        category = Category.DEVICE_ID,
        evidence = "dex: g1/d.java https://dm.wo.com.cn:18081/register (+http :18080) — CU-DMS uploads dual " +
            "IMEI/IMSI/ICCID, cell TAC/CID, Wi-Fi BSSID, paired BT MACs, IPv6, app usage (RegType A) and lat/lon " +
            "signal logs (RegType R) over Wi-Fi or Unicom data; embeds Baidu LBS (loc/ofloc/daup/itsdata.map." +
            "baidu.com); traffic: loc.map.baidu.com x11 idle; appops: FINE_LOCATION, WIFI_SCAN, READ_CELL_INFO " +
            "while idle; own uid (sharedUserId with one package)",
        impact = Text("联通终端登记将失败", "Unicom device registration fails"),
        highRisk = true,
    ),
    Rule(
        id = "ct-autoreg.http",
        packages = setOf("com.xiaomi.registration"),
        title = Text("电信自注册", "Telecom auto-registration"),
        summary = Text("阻止上报设备与 SIM 卡标识", "Blocks device and SIM ID uploads"),
        payload = RulePayload.Domains(listOf("zzhc.vnet.cn", "typdm.tydevice.com")),
        category = Category.DEVICE_ID,
        evidence = "dex: i1/j.java POST https://zzhc.vnet.cn (Base64 JSON: IMEI/MEID/IMSI/ICCID, Wi-Fi MAC, cell " +
            "id, TAC, RAM/ROM) on boot/SIM change/15-day period; GET http://typdm.tydevice.com?jsonstr= (CTEI, " +
            "MAC, BSSID, IPv4) on Wi-Fi-only SKUs; China Telecom terminal auto-registration; shared uid " +
            "android.uid.phone so no firewall",
        defaultOn = false,
        impact = Text("电信入网登记将失败", "Telecom registration will fail"),
    ),
    Rule(
        id = "ct-autoreg.sms",
        packages = setOf("com.xiaomi.registration"),
        title = Text("电信自注册短信", "Telecom registration SMS"),
        summary = Text("阻止发送设备登记短信", "Blocks device registration SMS"),
        payload = RulePayload.Method(
            className = "android.telephony.SmsManager",
            method = MethodPattern.parse("send*"),
        ),
        category = Category.DEVICE_ID,
        evidence = "dex: i1/m.java SmsManager.sendDataMessage to 10659401 (port 0) with model, both IMEIs, IMSI, " +
            "SW version; appops: SEND_SMS by com.xiaomi.registration; runs in its own process (no android:process), " +
            "so hooking SmsManager there does not affect the user's SMS",
        defaultOn = false,
        impact = Text("电信入网登记将失败", "Telecom registration will fail"),
    ),
    Rule(
        id = "simactivate.activation-receipt",
        packages = setOf("com.xiaomi.simactivate.service"),
        title = Text("SIM 激活回执", "SIM activation receipt"),
        summary = Text("阻止上报手机号与卡信息", "Blocks phone number and SIM uploads"),
        payload = RulePayload.Urls(listOf(UrlPrefix("ac.account.xiaomi.com", "/pass/activation/report"))),
        category = Category.DEVICE_ID,
        evidence = "dex: POST https://ac.account.xiaomi.com/pass/activation/report after activation with hashed " +
            "SIM/device ids, phone number, userId and encrypted SMS; server use unproven; other /pass/activation/* " +
            "paths (activate, activationReport) are SIM binding and stay open",
        defaultOn = false,
        impact = Text("可能影响 SIM 卡激活", "SIM activation may fail"),
    ),
)
