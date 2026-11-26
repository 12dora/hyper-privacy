package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.conflict.LabelGap
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LabelGapTest {
    @Test
    fun `no space after a CJK label`() {
        assertEquals("示例模块", LabelGap.beforeCjk("示例模块"))
        assertEquals("隐藏应用）", LabelGap.beforeCjk("隐藏应用）"))
    }

    @Test
    fun `a space after a Latin letter or digit`() {
        assertEquals("Example Module ", LabelGap.beforeCjk("Example Module"))
        assertEquals("Module2 ", LabelGap.beforeCjk("Module2"))
        assertEquals("模块 X ", LabelGap.beforeCjk("模块 X"))
    }

    @Test
    fun `empty label stays empty`() {
        assertEquals("", LabelGap.beforeCjk(""))
    }
}
