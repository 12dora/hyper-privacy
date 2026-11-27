package io.github.hyperprivacy.ui.detail

import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.tracker.TrackerRules

private val IDENTIFIER_NAMES = listOf("OAID", "VAID", "AAID", "UDID")

/** What a rule blocks, one entry per line: hosts, URL prefixes, SDK methods or identifier names. */
internal fun Rule.blockedTargets(): List<String> = when (val data = payload) {
    RulePayload.Firewall -> emptyList()

    is RulePayload.Domains -> data.suffixes

    is RulePayload.Method -> listOf("${data.className}#${data.method}")

    is RulePayload.Urls -> data.prefixes.map { it.host + it.pathPrefix + it.query?.let { q -> "?$q" }.orEmpty() }

    is RulePayload.Identifiers -> IDENTIFIER_NAMES

    RulePayload.KnownTrackers -> TrackerRules.knownTrackerPayloads().flatMap { copy(payload = it).blockedTargets() }

    // Adds no hosts of its own: it applies the app's other domain blocks to native code.
    RulePayload.NativeResolver -> emptyList()
}
