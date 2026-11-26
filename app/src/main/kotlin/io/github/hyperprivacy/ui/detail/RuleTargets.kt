package io.github.hyperprivacy.ui.detail

import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload

private val IDENTIFIER_NAMES = listOf("OAID", "VAID", "AAID", "UDID")

/** What a rule blocks, one entry per line: hosts, URL prefixes, SDK methods or identifier names. */
internal fun Rule.blockedTargets(): List<String> = when (val data = payload) {
    RulePayload.Firewall -> emptyList()
    is RulePayload.Domains -> data.suffixes
    is RulePayload.Method -> listOf("${data.className}#${data.method}")
    is RulePayload.Urls -> data.prefixes.map { it.host + it.pathPrefix }
    is RulePayload.Identifiers -> IDENTIFIER_NAMES
}
