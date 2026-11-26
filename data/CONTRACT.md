# Tracker SDK database — contributor notes

`trackers.json` lists third-party tracking and ad SDKs: how to recognise them in an installed app and
which of their reporting endpoints may be blocked in apps the user chooses to protect.

## Entry fields

| Field | Meaning |
|---|---|
| `id` | Lowercase kebab-case, unique and stable. |
| `name` / `nameEn` | Display name (zh / en), concise. |
| `displayName` / `displayNameEn` | Optional short list-row name when `name` is long or lists alternatives (zh ≤ 12 by the copy-width measure, en ≤ 28 characters). |
| `vendor` | SDK publisher. |
| `categories` | One or more of `ADS`, `ANALYTICS`, `CRASH`, `IDENTIFICATION`, `PROFILING`, `LOCATION`, `PUSH`, `ATTRIBUTION`. |
| `codePrefixes` | Java package / class prefixes that identify the SDK (dot form; packages end with `.`). |
| `excludePrefixes` | Optional, ADS entries only: the SDK's consent packages, kept working. |
| `anchors` | Optional: component class names from the SDK's own published manifest. |
| `blockHosts` | Hosts used only for telemetry, ad delivery or ad reporting. |
| `blockUrls` | Optional `{host, pathPrefix}` reporting paths on hosts that also serve features. |
| `keepHosts` | Optional: hosts needed for user-visible features (push delivery, login, payment, maps); never blocked. |
| `defaultBlock` | Optional, default `true`. `false` = listed but opt-in per app, for SDKs whose blocking may affect login, verification, payment or updates. |
| `originBlock` | Optional, default `true`, ADS entries only. `false` for ad SDKs that also serve user-visible features such as rewards. |
| `evidence` | At least one source. |
| `notes` | Optional, short: user-visible impact of blocking. |

## Requirements

- **Evidence**: every entry cites at least one independent source — vendor documentation, a published SDK artifact, or an analysed app package. Hosts that could not be verified are left out.
- **Specific signatures**: prefixes and hosts must be specific to the SDK; never list a shared vendor apex or a package shared with unrelated code.
- **Keep features working**: when a host also serves features, block only its reporting paths; record side effects in `notes` and set `defaultBlock: false` when blocking may affect login, payment or updates.
- **Neutral naming**: describe every entry by function and data channel. Words in `AppsCopyTest.BANNED_WORDS` are not allowed in any text field or id (core tests check this); use neutral terms such as risk control or device verification (风控, 设备验证).
- **Independent sources**: do not copy text, signatures or patterns from third-party databases whose licence is unclear.
- **No personal data**, local paths or device details.

## Regenerating

1. Edit `trackers.json` (keep entries sorted by `id`) and bump `version`; the version is part of scan cache keys.
2. Run `python3 -I scripts/gen-trackers.py` to regenerate `TrackerData.kt`.
3. Run `./gradlew :core:test`; `TrackerDataTest` fails if the JSON and the generated table are out of sync.
