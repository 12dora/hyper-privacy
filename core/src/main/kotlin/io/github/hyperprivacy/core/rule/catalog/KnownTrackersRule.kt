package io.github.hyperprivacy.core.rule.catalog

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text

/**
 * Blocks the endpoints of every known tracker SDK in a protected third-party app, whether or not
 * the offline scan found the SDK there ([RulePayload.KnownTrackers]); for apps whose code the scan
 * cannot read (packed apps), off until the user enables it for an app. Uses the default-blocked
 * SDKs' hosts and paths, and every SDK's keep hosts stay allowed.
 */
internal fun knownTrackersRule(): Rule = Rule(
    id = KNOWN_TRACKERS_RULE_ID,
    packages = setOf(Catalog.PROTECTED_APPS),
    title = Text("拦截全部已知追踪", "Block all known trackers"),
    summary = Text("适合识别不到 SDK 的加固应用", "For packed apps whose SDKs go undetected"),
    payload = RulePayload.KnownTrackers,
    category = Category.ANALYTICS,
    evidence = "data/trackers.json: blockHosts / blockUrls of the default-blocked SDKs, for apps whose " +
        "bundled SDKs cannot be inspected offline (such as packed apps); every listed endpoint is ad, " +
        "analytics, crash or profiling reporting only, and feature hosts are kept",
    defaultOn = false,
    preventive = true,
)

/** Id of [knownTrackersRule]; persisted in preferences. */
internal const val KNOWN_TRACKERS_RULE_ID: String = "known-trackers.all"
