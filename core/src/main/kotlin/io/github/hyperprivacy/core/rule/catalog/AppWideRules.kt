package io.github.hyperprivacy.core.rule.catalog

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.IdentifierSource
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text

/*
 * Rules that are not tied to one SDK or channel, defined once and applied to a computed target set:
 *
 * - [httpDnsRule] targets every package the module is injected into anyway (the packages of all
 *   other in-process catalog rules) except [HTTPDNS_EXCLUDED_APPS], so it never widens the LSPosed
 *   scope;
 * - [mediaDrmRule], [hardwareFingerprintRule] and [protectedHttpDnsRule] target only [Catalog.PROTECTED_APPS]:
 *   user-installed third-party apps the user chose to protect, never a vendor package.
 *
 * Both are `preventive`: a target that never trips them is the normal case.
 */

/**
 * Per-app `MediaDrm` device id, for protected third-party apps only
 * ([Catalog.protectedAppRules]).
 *
 * Never applied to vendor packages: vendor apps are the vendor, so hiding the shared id from them
 * protects nothing, while account, wallet, TSM, FIDO and Find Device backends use it to bind the
 * device and expect every vendor app to report the same value.
 */
internal fun mediaDrmRule(): Rule = Rule(
    id = "mediadrm.device-id",
    packages = setOf(Catalog.PROTECTED_APPS),
    title = Text("隔离 DRM 设备号", "Isolate DRM device ID"),
    summary = Text("每个应用拿到不同的设备号", "Each app sees a different DRM ID"),
    payload = RulePayload.Identifiers(IdentifierSource.MEDIA_DRM_ID),
    category = Category.DEVICE_ID,
    evidence = "docs/collection-techniques.md 2.2 / 10.7: Widevine deviceUniqueId is one value for every app on " +
        "the device and survives reinstall and ANDROID_ID resets, so SDKs (qimei, Bugly, risk SDKs) use it to join " +
        "apps; replaced per app with HMAC-SHA256(secret, real || package) at the Java API; the DRM HAL " +
        "keeps the real id, so licences and playback are unaffected",
    preventive = true,
)

/**
 * Per-app hardware traits, for protected third-party apps only ([Catalog.protectedAppRules]) and
 * off until the user enables it for an app. Changes only values no app needs for its function:
 * `core.fingerprint.HardwareTraits` lists what is perturbed and what is deliberately left alone.
 */
internal fun hardwareFingerprintRule(): Rule = Rule(
    id = "fingerprint.hardware",
    packages = setOf(Catalog.PROTECTED_APPS),
    title = Text("随机化硬件指纹", "Randomize hardware traits"),
    summary = Text("各应用读到的硬件特征互不相同", "Each app sees different hardware traits"),
    payload = RulePayload.Identifiers(IdentifierSource.HARDWARE_TRAITS),
    category = Category.DEVICE_ID,
    evidence = "docs/collection-techniques.md section 1 (device identifiers): SDKs hash permission-free hardware " +
        "readings (Sensor resolution/power/version list, statvfs f_fsid, battery EXTRA_CYCLE_COUNT) into a device " +
        "id that survives resets and links apps; replaced per app with values derived by HMAC-SHA256(secret, " +
        "label || package) at the Java API, sensor events, ranges, rates, names, model, SDK, ABI and paths untouched",
    defaultOn = false,
    preventive = true,
)

/** Third-party HTTPDNS / DoH resolvers in vendor packages; listed with the personal-data rules. */
internal fun httpDnsRule(targets: Set<String>): Rule = httpDnsRule("httpdns.resolvers", targets)

/**
 * The same resolver block for protected third-party apps ([Catalog.protectedAppRules]); a separate
 * id so its switch and health never mix with the vendor rule's.
 */
internal fun protectedHttpDnsRule(): Rule = httpDnsRule("httpdns.protected-apps", setOf(Catalog.PROTECTED_APPS))

private fun httpDnsRule(id: String, targets: Set<String>): Rule = Rule(
    id = id,
    packages = targets,
    title = Text("强制系统 DNS", "Force system DNS"),
    summary = Text("阻止应用绕过系统 DNS 解析", "Stop apps bypassing system DNS"),
    payload = RulePayload.Domains(HttpDnsResolvers.hosts + HttpDnsResolvers.addresses),
    category = Category.PERSONAL_DATA,
    evidence = "docs/collection-techniques.md 7.2 (HTTPDNS endpoint list): dex aliyun httpdns SDK in the app store " +
        "and Xiaomi shop (bootstrap IPs, resolvers-cn.httpdns.aliyuncs.com), iflytek IME HttpDnsConstants " +
        "203.107.1.2/108507/d, Xiaomi httpdns SDK dispatch.mgslb.com + DoH fallbacks dns.alidns.com/dns.google/" +
        "120.53.53.53, Xiaomi shop mishop.httpdns.Config (Wangsu, Kingsoft, dns-hl.mi.com), Baidu IME HttpDnsCache " +
        "httpsdns.baidu.com, Amap httpdns.apilocate.amap.com; traffic 203.119.204.226/v2/d?dn=. Every SDK falls " +
        "back to system DNS (Tencent documents it as mandatory), where the DNS sink sees the real host names",
    preventive = true,
)

/**
 * HTTPDNS and DoH resolver endpoints (docs/collection-techniques.md 7.2). Only hosts and IPs that
 * do nothing but answer DNS queries: no CDN, no vendor apex, nothing that also serves content.
 * Speculative entries (Volcengine, Huawei) are left out until verified.
 */
internal object HttpDnsResolvers {
    /** MiPush's own resolver: blocking it would break push, so no rule may ever match it. */
    const val ALWAYS_ALLOWED: String = "resolver.msg.xiaomi.net"

    val hosts: List<String> = listOf(
        // Aliyun EMAS HTTPDNS dispatch (resolvers-cn / -hk / -sg / -us / -de) and account resolvers.
        "httpdns.aliyuncs.com",
        // Aliyun public DNS over HTTPS.
        "dns.alidns.com",
        // Google public DNS over HTTPS.
        "dns.google",
        // DNSPod public DNS over HTTPS.
        "doh.pub",
        // Baidu HTTPDNS.
        "httpsdns.baidu.com",
        // Amap location SDK HTTPDNS.
        "httpdns.apilocate.amap.com",
        // Wangsu HTTPDNS.
        "httpdns.wangsucloud.com",
        // Kingsoft Cloud HTTPDNS.
        "hdns.ksyun.com",
        // Xiaomi httpdns SDK (app store) and Xiaomi shop resolver.
        "dispatch.mgslb.com",
        "dns-hl.mi.com",
    )

    /** Hard-coded resolver IPs, blocked at the connect sink on every port except 53. */
    val addresses: List<String> = listOf(
        // Aliyun EMAS HTTPDNS bootstrap and account endpoints.
        "203.107.1.1",
        "203.107.1.2",
        "203.107.1.34",
        "203.107.1.35",
        "203.107.1.97",
        "203.107.1.100",
        "203.119.204.226",
        "203.119.238.240",
        "106.11.25.239",
        "59.82.99.47",
        // Aliyun public DNS (DoH / HTTP JSON API).
        "223.5.5.5",
        "223.6.6.6",
        // Tencent Cloud HTTPDNS (CN HTTP / HTTPS, international).
        "119.29.29.98",
        "119.29.29.99",
        "43.132.55.55",
        "43.132.43.43",
        // DNSPod public DoH.
        "120.53.53.53",
        "1.12.12.12",
        // Baidu map SDK HTTPDNS.
        "180.76.76.200",
        // Wangsu HTTPDNS fallback.
        "182.132.33.40",
        // Xiaomi shop resolver (dns-hl.mi.com) fallbacks.
        "58.83.160.209",
        "58.83.160.210",
        // Google public DNS (DoH JSON API).
        "8.8.8.8",
    )
}
