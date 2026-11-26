package io.github.hyperprivacy.core.rule.catalog

import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.IdentifierSource
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text

/*
 * Rules that apply to every package the module is injected into anyway ([targets] is the set of
 * packages of all other in-process rules), so they never widen the LSPosed scope. They are
 * `preventive`: a target that never trips them is the normal case.
 */

/** Per-app `MediaDrm` device id; listed with the device-id rules. */
internal fun mediaDrmRule(targets: Set<String>): Rule = Rule(
    id = "mediadrm.device-id",
    packages = targets,
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
