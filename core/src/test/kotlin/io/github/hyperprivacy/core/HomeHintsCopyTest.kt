package io.github.hyperprivacy.core

import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Copy gate for the Home hints (restart, pause, overlapping module) and the Settings backup
 * strings, measured like [AppsCopyTest]: titles and one-line summaries must fit a phone row
 * without truncation, both languages define the same strings, nothing names a third-party product
 * (the overlapping module's own label is shown dynamically), and nothing describes rules as
 * blocking fraud. Dialog bodies wrap and are exempt from width limits.
 */
class HomeHintsCopyTest {
    private val res: File = requireNotNull(
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/res") }
            .firstOrNull { it.isDirectory },
    ) { "app/src/main/res not found" }

    private val zh = strings(File(res, "values-zh/strings.xml"))
    private val en = strings(File(res, "values/strings.xml"))

    @Test
    fun `both languages define the same strings`() {
        assertTrue(zh.keys.any { it.startsWith("pause_") })
        assertEquals(zh.keys.filter(::isHintKey).toSet(), en.keys.filter(::isHintKey).toSet())
    }

    @Test
    fun `titles and summaries fit one line`() {
        val tooLong = zh.filterKeys(::isHintKey).filterKeys { !isBody(it) }.mapNotNull { (key, text) ->
            val (zhMax, enMax) = if (key in TITLES) ZH_TITLE_MAX to EN_TITLE_MAX else ZH_LINE_MAX to EN_LINE_MAX
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
    fun `overlap lines name the area, then every module`() {
        // Placeholders read as "0": the area label, then the module names (or the first few and a count).
        assertEquals("0：0", zh["conflict_line"])
        assertEquals("0: 0", en["conflict_line"])
        // The count is of the modules not named, so the copy says "and N more", never "etc., N".
        // Runs on from the names (the gap after a Latin label is added in code); the count keeps its unit.
        assertEquals("0及另外 0\u00A0个", zh["conflict_names_more"])
        assertEquals("0 and 0 more", en["conflict_names_more"])
        listOf("applist", "ads", "privacy", "device").map { "conflict_area_$it" }.forEach { key ->
            assertTrue(CopyWidth.zh(zh.getValue(key)) <= ZH_AREA_MAX, key)
        }
    }

    @Test
    fun `copy names no third-party product and describes no fraud role`() {
        val offenders = (zh + en.mapKeys { "en:${it.key}" }).filterKeys { it.removePrefix("en:").let(::isHintKey) }
            .filter { (_, text) -> (PRODUCTS + FRAUD).any { text.contains(it, ignoreCase = true) } }
        assertTrue(offenders.isEmpty(), "Neutral copy only: ${offenders.keys}")
    }

    @Test
    fun `every overlap area explains itself and how to resolve it`() {
        listOf("applist", "ads", "privacy", "device").forEach { area ->
            assertTrue(zh["conflict_${area}_body"].orEmpty().startsWith("两边都"), area)
            assertTrue(en["conflict_${area}_body"].orEmpty().startsWith("Both "), area)
        }
        listOf("conflict_fix_applist_body", "conflict_fix_rules_body").forEach { key ->
            assertTrue(zh.getValue(key).startsWith("建议："), key)
        }
    }

    private fun isHintKey(key: String) = PREFIXES.any { key.startsWith(it) } || key in EXTRA

    private fun isBody(key: String) = key.endsWith("_body")

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
        val PREFIXES = listOf("module_reboot_", "pause_", "conflict_", "backup_", "settings_backup")
        val EXTRA = setOf("action_undo")
        val TITLES = setOf(
            "pause_action",
            "pause_resume",
            "pause_active",
            "pause_dialog_title",
            "pause_15m",
            "pause_1h",
            "pause_manual",
            "conflict_title",
            "conflict_disable_applist",
            "conflict_dismiss",
            "conflict_details",
            "conflict_detail_title",
            "conflict_open_rules",
            "action_undo",
            "settings_backup",
            "backup_export",
            "backup_import",
            "backup_import_title",
            "backup_import_confirm",
        )
        val PLACEHOLDER = Regex("%[0-9]+\\$[sd]")
        const val ZH_TITLE_MAX = 10.0
        const val EN_TITLE_MAX = 28
        const val ZH_LINE_MAX = 20.0
        const val EN_LINE_MAX = 48

        /** Area labels lead a line of module names, so they stay short. */
        const val ZH_AREA_MAX = 6.0

        /** Names of overlapping modules, which only ever appear as the detected app's own label. */
        val PRODUCTS = listOf("hide my applist", "hidemyapplist", "xprivacy", "xlua", "faircode", "hyperceiler")
        val FRAUD = listOf("反诈", "涉诈", "诈骗", "欺诈", "作弊", "fraud", "cheat")
    }
}
