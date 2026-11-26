package io.github.hyperprivacy.core

/**
 * Display width used by the copy gates: a CJK character or full-width punctuation mark counts 1,
 * any other character 0.5.
 */
internal object CopyWidth {
    private const val HALF_WIDTH = 0.5
    private const val FULL_WIDTH_PUNCTUATION = "，、。：；！？（）《》“”‘’"

    fun zh(text: String): Double = text.sumOf { if (it.isCjk()) 1.0 else HALF_WIDTH }

    private fun Char.isCjk(): Boolean = Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN ||
        this in FULL_WIDTH_PUNCTUATION
}
