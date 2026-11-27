package io.github.hyperprivacy.core.conflict

import io.github.hyperprivacy.core.rule.Category

/**
 * What an installed Xposed module may also be doing that this module does. [category] is where the
 * matching built-in rules of this module live (the System tab category the hint links to).
 */
public enum class OverlapArea(public val category: Category) {
    APP_LIST(Category.APP_LIST),
    ADS(Category.ADS),
    PRIVACY(Category.ANALYTICS),
    DEVICE_ID(Category.DEVICE_ID),
}

/** An installed module that overlaps, as found on this device. [label] is the app's own label. */
public data class OverlapHit(val packageName: String, val label: String, val areas: Set<OverlapArea>)

/**
 * Decides whether another Xposed module overlaps with this one, from the module's package name
 * ([KNOWN]) and, for the rest, keywords in its own label and description. Two modules doing the
 * same work can interfere (a call blocked twice, an app list hidden twice); this only informs the
 * user and never touches the other module.
 *
 * The decision is deliberately loose: a false positive costs one dismissible hint.
 */
public object ModuleOverlap {
    /** Well-known modules by package name, with what they do. */
    public val KNOWN: Map<String, Set<OverlapArea>> = mapOf(
        "com.tsng.hidemyapplist" to setOf(OverlapArea.APP_LIST),
        "eu.faircode.xlua" to setOf(OverlapArea.PRIVACY, OverlapArea.DEVICE_ID),
        "biz.bokhorst.xprivacy" to setOf(OverlapArea.PRIVACY, OverlapArea.APP_LIST, OverlapArea.DEVICE_ID),
        "com.sevtinge.hyperceiler" to setOf(OverlapArea.ADS),
    )

    /** Intent category of the settings screen an Xposed module declares. */
    public const val SETTINGS_CATEGORY: String = "de.robv.android.xposed.category.MODULE_SETTINGS"

    private val APP_LIST_WORDS = Regex(
        "hide[ -]?(my )?(installed )?apps?|app ?list|applist|应用列表|隐藏应用|应用隐藏|隐藏已安装|隐藏.{0,4}应用",
        RegexOption.IGNORE_CASE,
    )
    private val ADS_WORDS = Regex(
        "\\bad[ -]?block|\\bads?\\b|block(ing)? ads|广告",
        RegexOption.IGNORE_CASE,
    )
    private val PRIVACY_WORDS = Regex(
        "privacy|tracker|tracking|telemetry|隐私|上报|追踪|数据收集",
        RegexOption.IGNORE_CASE,
    )

    private val DEVICE_WORDS = Regex(
        "\\bdevice ?ids?\\b|\\bandroid ?ids?\\b|\\bimei\\b|\\boaid\\b|设备标识|设备号|设备 ?id|机型伪装|伪装机型",
        RegexOption.IGNORE_CASE,
    )

    /** Longest text matched; descriptions are one or two sentences. */
    private const val MAX_TEXT = 400

    /** The overlapping areas of a module with this [packageName], [label] and [description]. */
    public fun areasOf(packageName: String, label: String?, description: String?): Set<OverlapArea> {
        val text = listOfNotNull(label, description).joinToString(" ").take(MAX_TEXT)
        val found = linkedSetOf<OverlapArea>()
        KNOWN[packageName]?.let(found::addAll)
        if (APP_LIST_WORDS.containsMatchIn(text)) found += OverlapArea.APP_LIST
        if (ADS_WORDS.containsMatchIn(text)) found += OverlapArea.ADS
        if (PRIVACY_WORDS.containsMatchIn(text)) found += OverlapArea.PRIVACY
        if (DEVICE_WORDS.containsMatchIn(text)) found += OverlapArea.DEVICE_ID
        return found
    }
}
