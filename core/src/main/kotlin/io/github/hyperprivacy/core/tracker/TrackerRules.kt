package io.github.hyperprivacy.core.tracker

import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.rule.catalog.PROTECTED_HTTPDNS_ID
import io.github.hyperprivacy.core.rule.catalog.protectedHttpDnsRule

/**
 * The rules enforced inside an app with tracker protection on ([TrackerProtection]): each
 * detected blockable SDK becomes a synthetic [Rule] with id [TrackerProtection.ruleId] (one
 * DOMAIN and/or one URL rule sharing it), so the regular network sinks enforce it. The app-level
 * protections of every protected app ([appRules]) are [Catalog.protectedAppRules], bound to the
 * app: they are defined once there, with their copy, defaults and health.
 */
public object TrackerRules {
    /** Ids of [appRules]. */
    public val appRuleIds: Set<String> get() = Catalog.protectedAppRuleIds

    /**
     * [Catalog.protectedAppRules] bound to the protected [packageName]. In an app that resolves
     * names for the user ([userDns], [OriginHints.userDns]) the resolver rule blocks SDK HTTPDNS
     * only, under the same id and switch.
     */
    public fun appRules(packageName: String, userDns: Boolean = false): List<Rule> =
        Catalog.protectedAppRules.map { rule ->
            val bound = if (userDns && rule.id == PROTECTED_HTTPDNS_ID) protectedHttpDnsRule(sdkOnly = true) else rule
            bound.copy(packages = setOf(packageName))
        }

    /**
     * The per-app switch key of app-level rule [ruleId] in [packageName]. Absent: the global
     * catalog switch (`rule.<id>`), else the rule's default.
     */
    public fun appRuleKey(packageName: String, ruleId: String): String = TrackerProtection.blockKey(packageName, ruleId)

    /** Whether app-level [rule] applies in [packageName] under [raw] (protection itself not considered). */
    public fun isAppRuleOn(raw: Map<String, *>, packageName: String, rule: Rule): Boolean =
        raw[appRuleKey(packageName, rule.id)] as? Boolean
            ?: raw[ConfigCodec.key(rule.id)] as? Boolean
            ?: rule.defaultOn

    /**
     * The in-process rules for a protected [packageName] whose scan found [detectedIds]: one or
     * two synthetic rules per blockable SDK, then [appRules]. Unknown and detection-only ids are
     * skipped. [userDns] as in [appRules].
     */
    public fun rulesFor(packageName: String, detectedIds: Collection<String>, userDns: Boolean = false): List<Rule> {
        val sdkRules = detectedIds.distinct().sorted().mapNotNull(Trackers::byId).filter { it.blockable }
            .flatMap { sdk -> rulesOf(packageName, sdk) }
        return sdkRules + appRules(packageName, userDns)
    }

    /**
     * Effective switch of each rule from [rulesFor] under [raw]: nothing is on unless protection
     * is on for [packageName]; SDK rules follow [TrackerProtection.isBlocked] and the stored scan
     * result, [appRules] follow [isAppRuleOn].
     */
    public fun resolve(raw: Map<String, *>, packageName: String, rules: List<Rule>): Map<String, Boolean> {
        val detected = TrackerProtection.detected(raw, packageName)?.toSet()
        return rules.associate { rule ->
            val sdk = TrackerProtection.sdkIdOf(rule.id)?.let(Trackers::byId)
            val on = when {
                detected == null -> false
                sdk != null -> sdk.id in detected && TrackerProtection.isBlocked(raw, packageName, sdk)
                rule.id in appRuleIds -> isAppRuleOn(raw, packageName, rule)
                else -> false
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

    /** The catalog category a tracker category is reported under. */
    public fun categoryOf(category: TrackerCategory): Category = when (category) {
        TrackerCategory.ADS -> Category.ADS
        TrackerCategory.CRASH -> Category.DIAGNOSTICS
        TrackerCategory.IDENTIFICATION -> Category.DEVICE_ID
        TrackerCategory.LOCATION -> Category.PERSONAL_DATA
        else -> Category.ANALYTICS
    }
}
