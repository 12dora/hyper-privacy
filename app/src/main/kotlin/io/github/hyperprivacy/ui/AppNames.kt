package io.github.hyperprivacy.ui

import io.github.hyperprivacy.core.rule.Text

/** Display names for system components whose own label is unhelpful (untranslated or generic). */
internal object AppNames {
    private val overrides: Map<String, Text> = mapOf(
        "com.miui.analytics" to Text("系统分析服务", "System analytics"),
        "com.miui.systemAdSolution" to Text("系统广告服务", "System ad service"),
    )

    /** The override for [packageName] in [languageTag], or [systemLabel] when there is none. */
    fun display(packageName: String, systemLabel: String, languageTag: String): String =
        overrides[packageName]?.resolve(languageTag) ?: systemLabel
}
