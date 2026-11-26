package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Copy gate: rule texts must fit a single line in the rule list on a phone, where the by-type view
 * also prefixes the summary with the app name. Limits are display widths, not character counts:
 * a CJK character counts 1, any other character 0.5 (Chinese) — English is counted per character.
 */
class CatalogCopyTest {
    @Test
    fun `chinese copy fits one line`() {
        val tooLong = (Catalog.rules + Catalog.protectedAppRules).flatMap { rule ->
            listOfNotNull(
                "${rule.id} title: ${rule.title.zh}".takeIf { width(rule.title.zh) > ZH_TITLE_MAX },
                "${rule.id} summary: ${rule.summary.zh}".takeIf { width(rule.summary.zh) > ZH_SUMMARY_MAX },
                rule.impact?.zh?.let { "${rule.id} impact: $it".takeIf { _ -> width(it) > ZH_SUMMARY_MAX } },
            )
        }
        assertTrue(tooLong.isEmpty(), "Chinese copy too long:\n" + tooLong.joinToString("\n"))
    }

    @Test
    fun `english copy fits one line`() {
        val tooLong = (Catalog.rules + Catalog.protectedAppRules).flatMap { rule ->
            listOfNotNull(
                "${rule.id} title: ${rule.title.en}".takeIf { rule.title.en.length > EN_TITLE_MAX },
                "${rule.id} summary: ${rule.summary.en}".takeIf { rule.summary.en.length > EN_SUMMARY_MAX },
                rule.impact?.en?.let { "${rule.id} impact: $it".takeIf { _ -> it.length > EN_SUMMARY_MAX } },
            )
        }
        assertTrue(tooLong.isEmpty(), "English copy too long:\n" + tooLong.joinToString("\n"))
    }

    @Test
    fun `titles are distinguishable within a category`() {
        val duplicates = Category.entries.flatMap { category ->
            Catalog.rules.filter { it.category == category }
                .groupBy { it.title.zh }
                .filterValues { it.size > 1 }
                .map { (title, rules) -> "$category: \"$title\" used by ${rules.map { it.id }}" }
        }
        assertTrue(duplicates.isEmpty(), "Duplicate titles:\n" + duplicates.joinToString("\n"))
    }

    private fun width(text: String): Double = text.sumOf { if (it.isCjk()) 1.0 else HALF_WIDTH }

    private fun Char.isCjk(): Boolean = Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN ||
        this in FULL_WIDTH_PUNCTUATION

    private companion object {
        const val ZH_TITLE_MAX = 10.0
        const val ZH_SUMMARY_MAX = 14.0
        const val EN_TITLE_MAX = 28
        const val EN_SUMMARY_MAX = 40
        const val HALF_WIDTH = 0.5
        const val FULL_WIDTH_PUNCTUATION = "，、。：；！？（）《》“”‘’"
    }
}
