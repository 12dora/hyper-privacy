package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.tracker.Trackers
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Copy gate for the Apps page strings (app module resources), with the same width measure as
 * [CatalogCopyTest]: titles and one-line summaries must fit a phone row without truncation, and
 * both languages must define the same strings. Section notes (`app_note_*`) are one short line
 * too, and so is the page intro (`apps_intro`), which must fit one line of a 360dp-wide phone.
 */
class AppsCopyTest {
    private val res: File = requireNotNull(
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/res") }
            .firstOrNull { it.isDirectory },
    ) { "app/src/main/res not found" }

    private val zh = strings(File(res, "values-zh/strings.xml"))
    private val en = strings(File(res, "values/strings.xml"))

    @Test
    fun `both languages define the apps strings`() {
        assertTrue(zh.keys.any { it.startsWith("apps_") })
        assertEquals(zh.keys.filter(::isAppsKey).toSet(), en.keys.filter(::isAppsKey).toSet())
    }

    @Test
    fun `titles and summaries fit one line`() {
        val tooLong = zh.filterKeys(::isAppsKey).mapNotNull { (key, text) ->
            val (zhMax, enMax) = if (isTitle(key)) ZH_TITLE_MAX to EN_TITLE_MAX else ZH_LINE_MAX to EN_LINE_MAX
            val english = en[key]
            assertNotNull(english, key)
            when {
                CopyWidth.zh(text) > zhMax -> "$key zh: $text"
                english.length > enMax -> "$key en: $english"
                else -> null
            }
        }
        assertTrue(tooLong.isEmpty(), "Copy too long:\n" + tooLong.joinToString("\n"))
    }

    @Test
    fun `intro fits one line of a narrow phone`() {
        assertEquals("识别应用内置的追踪 SDK 并拦截其上报", zh["apps_intro"])
        assertTrue(CopyWidth.zh(zh.getValue("apps_intro")) <= ZH_INTRO_MAX)
        assertTrue(en.getValue("apps_intro").length <= EN_INTRO_MAX)
    }

    @Test
    fun `detection-only label is short and states what the SDK is`() {
        assertEquals("仅识别", zh["sdk_detect_only"])
        assertEquals("Detect only", en["sdk_detect_only"])
    }

    @Test
    fun `suspected components carry one note about themselves`() {
        assertEquals("不属于已知 SDK，默认不拦截", zh["sdk_suspect_summary"])
        assertTrue(en.getValue("sdk_suspect_summary").length <= EN_LINE_MAX)
    }

    @Test
    fun `apps search has a hint and an empty text in both languages`() {
        listOf("apps_search_hint", "apps_search_empty").forEach { key ->
            assertTrue(zh[key].orEmpty().isNotBlank(), "zh $key")
            assertTrue(en[key].orEmpty().isNotBlank(), "en $key")
        }
    }

    @Test
    fun `copy describes functions, not fraud`() {
        val offenders = (zh + en.mapKeys { "en:${it.key}" }).filter { (_, text) -> banned(text) }
        assertTrue(offenders.isEmpty(), "Describe by function: ${offenders.keys}")
    }

    @Test
    fun `tracker names and vendors describe functions, not fraud`() {
        val offenders = Trackers.all.flatMap { sdk ->
            listOf(sdk.id, sdk.name.zh, sdk.name.en, sdk.displayName.zh, sdk.displayName.en, sdk.vendor)
                .filter(::banned)
                .map { "${sdk.id}: $it" }
        }
        assertTrue(offenders.isEmpty(), "Describe by function:\n" + offenders.joinToString("\n"))
    }

    private fun banned(text: String) = BANNED_WORDS.any { text.contains(it, ignoreCase = true) }

    private fun isAppsKey(key: String) = PREFIXES.any { key.startsWith(it) }

    private fun isTitle(key: String) = key in TITLES || key.startsWith("tracker_cat_")

    /** `<string>` and the `other` item of each `<plurals>`, by name; placeholders count as one character. */
    private fun strings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.documentElement.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.mapNotNull { node ->
            val text = when (node.tagName) {
                "string" -> node.textContent

                "plurals" -> node.getElementsByTagName("item").let { items ->
                    (0 until items.length).map { items.item(it) as Element }
                        .firstOrNull { it.getAttribute("quantity") == "other" }?.textContent
                }

                else -> null
            }
            text?.let { node.getAttribute("name") to it.replace(PLACEHOLDER, "0").replace("\\'", "'") }
        }.toMap()
    }

    private companion object {
        val PREFIXES = listOf(
            "apps_", "app_protection", "app_scope_", "app_section_", "app_hide", "app_strict",
            "app_note_", "sdk_", "tracker_cat_",
        )
        val TITLES = setOf(
            "apps_title",
            "apps_filter_trackers",
            "app_protection",
            "app_scope_retry",
            "app_section_privacy",
            "app_section_sdk",
            "app_section_hardening",
            "app_section_rules",
            "app_hide_apps",
            "app_strict",
            "sdk_suspect_title",
        )
        val PLACEHOLDER = Regex("%[0-9]+\\$[sd]")
        const val ZH_TITLE_MAX = 10.0
        const val EN_TITLE_MAX = 28
        const val ZH_LINE_MAX = 20.0
        const val EN_LINE_MAX = 48
        const val ZH_INTRO_MAX = 22.0
        const val EN_INTRO_MAX = 46

        /** Public copy names functions and data channels, never anti-fraud / anti-cheat roles. */
        val BANNED_WORDS = listOf("反诈", "涉诈", "诈骗", "欺诈", "作弊", "fraud", "cheat")
    }
}
