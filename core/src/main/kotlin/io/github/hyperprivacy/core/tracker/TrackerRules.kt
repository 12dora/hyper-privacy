package io.github.hyperprivacy.core.tracker

import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.IdentifierSource
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.rule.catalog.HttpDnsResolvers

/**
 * The rules enforced inside an app with tracker protection on ([TrackerProtection]): each
 * detected blockable SDK becomes a synthetic [Rule] with id [TrackerProtection.ruleId] (one
 * DOMAIN and/or one URL rule sharing it), so the regular network sinks enforce it. Two app-level
 * protections ([appRules]) come with every protected app: a per-app `MediaDrm` device id and
 * blocking third-party HTTPDNS resolvers. They are defined here rather than taken from the catalog,
 * so the catalog can scope its own versions independently.
 */
public object TrackerRules {
    /** Rule id of the per-app `MediaDrm` device id in a protected app. */
    public const val DRM_ID_RULE: String = "tracker.app.drm-id"

    /** Rule id of the HTTPDNS resolver block in a protected app. */
    public const val HTTPDNS_RULE: String = "tracker.app.httpdns"

    /** Ids of [appRules]; the switch of each is `TrackerProtection.blockKey(pkg, <id without prefix>)`. */
    public val appRuleIds: Set<String> = setOf(DRM_ID_RULE, HTTPDNS_RULE)

    /** The app-level protections of a protected [packageName]; on with protection unless overridden. */
    public fun appRules(packageName: String): List<Rule> = listOf(
        Rule(
            id = DRM_ID_RULE,
            packages = setOf(packageName),
            title = Text("隔离 DRM 设备号", "Isolate DRM device ID"),
            summary = Text("每个应用拿到不同的设备号", "Each app sees a different DRM ID"),
            payload = RulePayload.Identifiers(IdentifierSource.MEDIA_DRM_ID),
            category = Category.DEVICE_ID,
            evidence = "Widevine deviceUniqueId is one value for every app and survives reinstall; replaced per " +
                "app with HMAC-SHA256(secret, real || package) at the Java API, the DRM HAL keeps the real id",
            preventive = true,
        ),
        Rule(
            id = HTTPDNS_RULE,
            packages = setOf(packageName),
            title = Text("强制系统 DNS", "Force system DNS"),
            summary = Text("阻止应用绕过系统 DNS 解析", "Stop apps bypassing system DNS"),
            payload = RulePayload.Domains(HttpDnsResolvers.hosts + HttpDnsResolvers.addresses),
            category = Category.PERSONAL_DATA,
            evidence = "HTTPDNS / DoH resolver endpoints (docs/collection-techniques.md 7.2); SDKs fall back to " +
                "system DNS, where the DNS sink sees the real host names",
            preventive = true,
        ),
    )

    /**
     * The in-process rules for a protected [packageName] whose scan found [detectedIds]: one or
     * two synthetic rules per blockable SDK, then [appRules]. Unknown and detection-only ids are
     * skipped.
     */
    public fun rulesFor(packageName: String, detectedIds: Collection<String>): List<Rule> {
        val sdkRules = detectedIds.distinct().sorted().mapNotNull(Trackers::byId).filter { it.blockable }
            .flatMap { sdk -> rulesOf(packageName, sdk) }
        return sdkRules + appRules(packageName)
    }

    /**
     * Effective switch of each rule from [rulesFor] under [raw]: nothing is on unless protection
     * is on for [packageName]; SDK rules follow [TrackerProtection.isBlocked] and the stored scan
     * result, [appRules] are on unless their block key is set to `false`.
     */
    public fun resolve(raw: Map<String, *>, packageName: String, rules: List<Rule>): Map<String, Boolean> {
        val detected = TrackerProtection.detected(raw, packageName)?.toSet()
        return rules.associate { rule ->
            val sdk = TrackerProtection.sdkIdOf(rule.id)?.let(Trackers::byId)
            val on = when {
                detected == null -> false
                sdk != null -> sdk.id in detected && TrackerProtection.isBlocked(raw, packageName, sdk)
                else -> raw[TrackerProtection.blockKey(packageName, rule.id.removePrefix(PREFIX))] as? Boolean ?: true
            }
            rule.id to on
        }
    }

    private fun rulesOf(packageName: String, sdk: TrackerSdk): List<Rule> {
        val payloads = listOfNotNull(
            sdk.blockHosts.takeIf { it.isNotEmpty() }?.let { RulePayload.Domains(it) },
            sdk.blockUrls.takeIf { it.isNotEmpty() }?.let { RulePayload.Urls(it) },
        )
        return payloads.map { payload ->
            Rule(
                id = TrackerProtection.ruleId(sdk.id),
                packages = setOf(packageName),
                title = sdk.name,
                summary = Text(sdk.vendor, sdk.vendor),
                payload = payload,
                category = categoryOf(sdk.category),
                evidence = "tracker database ${Trackers.version}: ${sdk.id}",
                defaultOn = sdk.defaultBlock,
            )
        }
    }

    private const val PREFIX = TrackerProtection.RULE_PREFIX

    /** The catalog category a tracker category is reported under. */
    public fun categoryOf(category: TrackerCategory): Category = when (category) {
        TrackerCategory.ADS -> Category.ADS
        TrackerCategory.CRASH -> Category.DIAGNOSTICS
        TrackerCategory.IDENTIFICATION -> Category.DEVICE_ID
        TrackerCategory.LOCATION -> Category.PERSONAL_DATA
        else -> Category.ANALYTICS
    }
}
