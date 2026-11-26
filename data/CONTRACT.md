# Tracker SDK database — data contract (v1, + optional `defaultBlock`)

Purpose: detect third-party tracking/ad SDKs inside ANY installed APK (offline dex scan) and block their
network endpoints inside apps the user chooses to protect, without breaking app features.

## File: `trackers.json` (UTF-8, pretty-printed, sorted by `id`)

```json
{
  "schema": 1,
  "version": "2026.11.27c",
  "trackers": [
    {
      "id": "umeng-analytics",                 // lowercase kebab, unique, stable
      "name": "友盟统计",                        // display name (zh), concise
      "nameEn": "Umeng Analytics",
      "vendor": "友盟+ (Alibaba)",
      "categories": ["ANALYTICS"],             // ADS | ANALYTICS | CRASH | IDENTIFICATION | PROFILING | LOCATION | PUSH | ATTRIBUTION
      "codePrefixes": ["com.umeng.analytics.", "com.umeng.commonsdk."],  // Java package/class prefixes, dot form, end with "." for packages
      "blockHosts": ["alog.umeng.com", "ulogs.umeng.com"],  // hosts safe to block: pure telemetry/ad delivery/ad reporting. exact or dot-suffix match
      "blockUrls": [{"host": "x.example.com", "pathPrefix": "/track/"}],   // optional: reporting paths on hosts that also serve features
      "keepHosts": ["..."],                    // optional: hosts the SDK uses for user-visible features that must NEVER be blocked (push delivery, login, payment, maps)
      "defaultBlock": true,                    // optional boolean, default true. false = the entry is detected and listed, but its blockHosts/blockUrls are NOT applied by generic protection; the user must opt in per app
      "evidence": [
        "doc: https://developer.umeng.com/... (integration package names)",
        "apk: com.example.app classes contain Lcom/umeng/analytics/ (device survey)"
      ],
      "notes": "optional, short"
    }
  ]
}
```

Rules:
- `codePrefixes` must be specific enough not to match unrelated code (no bare `com.tencent.`, `com.baidu.`, `com.google.`, `com.alibaba.`, `com.huawei.`).
- `blockHosts` must never contain apex domains shared with services (`qq.com`, `baidu.com`, `alibaba.com`, `aliyun.com`, `google.com`, `googleapis.com`, `huawei.com`, `xiaomi.com`, `bytedance.com`, `douyin.com`, `kuaishou.com`, `tencent.com`, `163.com`...). Use the most specific telemetry/ad hosts.
- PUSH trackers: detection only — `blockHosts` empty, delivery hosts in `keepHosts`.
- IDENTIFICATION (device fingerprint / anti-fraud risk SDKs like Shumei, Tongdun): block only collection/report endpoints; note login/risk-control side effects in `notes`.
- `defaultBlock` (optional boolean, default `true`): set `false` on risk-control / anti-fraud / device-fingerprint SDKs and on SDKs whose endpoints are shared with an update/hot-fix channel, where blocking can cause login rejections or extra verification, payment declines, captcha failures or lost app updates (e.g. Shumei, Tongdun, Kount, Fingerprint, Incognia, Sift, Baidu Sofire, Yidun anti-cheat, Tencent QIMEI/Turing, Geetest client_report, ByteDance SecSDK/MetaSec, Kuaishou Weapon, Tencent Bugly). Keep their `blockHosts`/`blockUrls` so users can opt in per app, and add a `notes` line such as "Blocking may cause login to require extra verification or fail (拦截可能导致登录需要额外验证或失败)". Entries without block rules (detection-only) must omit the flag.
- `keepHosts` exempt a host from host-level blocking. A `blockUrls` rule may still target a specific reporting path on a host that is also in the same entry's `keepHosts` (path-level override, e.g. baidu-location `/statloc`); a `blockHosts` entry must never cover a `keepHosts` host of any entry.
- Shared PaaS / log-ingestion / multi-purpose suffixes (`log.aliyuncs.com`, `cls.tencentcs.com`, `crashlytics.com`, `hyprmx.com`, `networkbench.com`, `pubnative.net`, `vrvm.com`, `tapjoy.com`, `pangle.io`, …) must not appear in `blockHosts`: list verified subdomains, use `blockUrls` paths, or make the entry detection-only.
- Prefix matching is longest-prefix: if one entry's prefix is a prefix of another entry's (e.g. `com.mob.` vs `com.mob.pushsdk.`), a class belongs to the entry with the longest matching prefix; an app can still match several entries. Do not put prefixes shared with other SDKs or build-system namespaces (obfuscated names like `com.byazt.`, unbranded protobuf packages, shared transports such as `com.taobao.accs.`) in an entry.
- Identical hosts may appear in `blockHosts` of sibling entries (e.g. smaato/verve-hybid, tencent-gdt/tencent-ams-ads, sigmob/sigmob-windmill) when each entry must stay self-contained so that detecting either SDK blocks them; this is intentional and harmless. Within one entry, a host already covered by a parent suffix in the same `blockHosts` must not be repeated. Shared telemetry hosts such as `firebaselogging-pa.googleapis.com` are blocked host-wide in every entry that uses them (path-scope only when the host also carries feature traffic).
- Every tracker needs ≥1 evidence entry; prefer vendor docs + observed dex/traffic. Uncertain hosts go to `notes`, not `blockHosts`.
- Do NOT copy text, signatures or regexes from third-party tracker databases whose licence is unclear; all signatures must be independently sourced.
- No personal data, no local paths.

## Engine expectations (for reference)
- Scanner reads dex string table type descriptors `Lcom/umeng/analytics/X;` → dot form → prefix trie match on `codePrefixes`.
- Generic protection for a user-chosen app blocks `blockHosts`/`blockUrls` of categories ADS, ANALYTICS, CRASH, PROFILING, IDENTIFICATION, ATTRIBUTION, LOCATION (per-category toggles in UI); PUSH never blocked. Entries with `"defaultBlock": false` are skipped by generic protection unless the user opts in for that app.
