package io.github.hyperprivacy.core

import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Copy gate for the navigation and page structure (app module resources), measured like
 * [AppsCopyTest]: bottom-bar labels fit a navigation item, page and section titles fit one line,
 * row summaries fit one line, and both languages define the same strings. Like every public copy,
 * nothing names a third-party product or describes a fraud role. Dialog bodies wrap and are exempt
 * from width limits.
 */
class NavigationCopyTest {
    private val res: File = requireNotNull(
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/res") }
            .firstOrNull { it.isDirectory },
    ) { "app/src/main/res not found" }

    private val zh = strings(File(res, "values-zh/strings.xml"))
    private val en = strings(File(res, "values/strings.xml"))

    @Test
    fun `both languages define the navigation strings`() {
        assertTrue(zh.keys.any { it.startsWith("tab_") })
        assertEquals(zh.keys.filter(::isNavKey).toSet(), en.keys.filter(::isNavKey).toSet())
    }

    @Test
    fun `tab labels fit a navigation item`() {
        val tooLong = zh.filterKeys { it.startsWith("tab_") }.mapNotNull { (key, text) ->
            val english = en[key]
            assertNotNull(english, key)
            when {
                CopyWidth.zh(text) > ZH_TAB_MAX -> "$key zh: $text"
                english.length > EN_TAB_MAX -> "$key en: $english"
                else -> null
            }
        }
        assertTrue(tooLong.isEmpty(), "Tab label too long:\n" + tooLong.joinToString("\n"))
    }

    @Test
    fun `titles and summaries fit one line`() {
        val tooLong = zh.filterKeys(::isNavKey).filterKeys { !it.endsWith("_body") }.mapNotNull { (key, text) ->
            val line = LINE_SUFFIXES.any { key.endsWith(it) } || key in LINES
            val (zhMax, enMax) = if (line) ZH_LINE_MAX to EN_LINE_MAX else ZH_TITLE_MAX to EN_TITLE_MAX
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
    fun `system apps and installed apps are named apart`() {
        assertEquals("系统应用", zh["system_title"])
        assertEquals("已安装应用", zh["apps_title"])
        val titles = TITLED.map { zh.getValue(it) }
        assertEquals(titles.size, titles.toSet().size, "Two pages or sections share a title: $titles")
    }

    @Test
    fun `copy names no third-party product and describes no fraud role`() {
        val offenders = (zh + en.mapKeys { "en:${it.key}" })
            .filterKeys { it.removePrefix("en:").let(::isNavKey) }
            .filter { (_, text) -> (PRODUCTS + FRAUD).any { text.contains(it, ignoreCase = true) } }
        assertTrue(offenders.isEmpty(), "Neutral copy only: ${offenders.keys}")
    }

    private fun isNavKey(key: String) = PREFIXES.any { key.startsWith(it) }

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
        val PREFIXES = listOf("tab_", "system_", "overview_", "settings_", "search_", "app_section_")

        /** Row summaries and one-line notes; everything else under [PREFIXES] is a title or label. */
        val LINE_SUFFIXES = listOf("_summary", "_off", "_empty", "_locked", "_none", "_count", "_hint")
        val LINES = setOf("overview_apps_protected", "settings_reset_title")

        /** Page and section titles that must never be confused with each other. */
        val TITLED = listOf(
            "system_title", "apps_title", "overview_coverage", "settings_title", "settings_appearance",
            "settings_privacy", "settings_backup", "settings_diagnostics", "settings_about",
            "app_section_sdk", "app_section_hardening", "app_section_privacy",
        )
        val PLACEHOLDER = Regex("%[0-9]+\\$[sd]")
        const val ZH_TAB_MAX = 4.0
        const val EN_TAB_MAX = 12
        const val ZH_TITLE_MAX = 10.0
        const val EN_TITLE_MAX = 28
        const val ZH_LINE_MAX = 20.0
        const val EN_LINE_MAX = 48

        val PRODUCTS = listOf("hide my applist", "hidemyapplist", "xprivacy", "xlua", "faircode", "hyperceiler")
        val FRAUD = listOf("反诈", "涉诈", "诈骗", "欺诈", "作弊", "fraud", "cheat")
    }
}
