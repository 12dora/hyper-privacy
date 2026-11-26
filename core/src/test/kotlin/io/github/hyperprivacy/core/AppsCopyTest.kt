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
 * both languages must define the same strings. Paragraph notes (`app_note_*`, `apps_intro`) wrap
 * and are exempt from width limits.
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
        val tooLong = zh.filterKeys(::isAppsKey).filterKeys { !isNote(it) }.mapNotNull { (key, text) ->
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

    private fun isNote(key: String) = key.startsWith("app_note_") || key == "apps_intro"

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
            "apps_", "app_protection", "app_scope_", "app_section_privacy", "app_hide", "app_strict",
            "app_note_", "sdk_", "tracker_cat_", "action_apps",
        )
        val TITLES = setOf(
            "apps_title",
            "action_apps",
            "apps_filter_trackers",
            "app_protection",
            "app_scope_retry",
            "app_section_privacy",
            "app_hide_apps",
            "app_strict",
        )
        val PLACEHOLDER = Regex("%[0-9]+\\$[sd]")
        const val ZH_TITLE_MAX = 10.0
        const val EN_TITLE_MAX = 28
        const val ZH_LINE_MAX = 20.0
        const val EN_LINE_MAX = 48

        /** Public copy names functions and data channels, never anti-fraud / anti-cheat roles. */
        val BANNED_WORDS = listOf("反诈", "涉诈", "诈骗", "欺诈", "作弊", "fraud", "cheat")
    }
}
