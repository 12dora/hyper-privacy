package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.system.SettingCopyMasker
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

/** Mirrors the AOSP shape: an inner `Setting` with a copy constructor and a shared null subclass. */
class FakeSettingsState {
    val nullSetting: Setting = object : Setting(null, null) {}

    open inner class Setting(private var value: String?, private var defaultValue: String?) {
        constructor(other: Setting) : this(other.value, other.defaultValue)

        fun value() = value

        fun defaultValue() = defaultValue
    }
}

class FakeNoCopyState {
    inner class Setting(val value: String?)
}

class SettingCopyMaskerTest {
    private val state = FakeSettingsState()
    private val masker = assertNotNull(
        SettingCopyMasker.of(
            FakeSettingsState::class.java,
            FakeSettingsState.Setting::class.java,
            listOf("value", "defaultValue"),
            "Xiaomi 15",
        ),
    )

    @Test
    fun `masks a copy and never writes the original`() {
        val stored = state.Setting("Alice's phone", "Model")
        val masked = assertNotNull(masker.mask(state, stored)) as FakeSettingsState.Setting
        assertNotSame(stored, masked)
        assertEquals("Xiaomi 15", masked.value())
        assertEquals("Xiaomi 15", masked.defaultValue())
        assertEquals("Alice's phone", stored.value())
        assertEquals("Model", stored.defaultValue())
    }

    @Test
    fun `null setting, null result and missing state are left alone`() {
        assertNull(masker.mask(state, state.nullSetting))
        assertNull(state.nullSetting.value())
        assertNull(masker.mask(state, null))
        assertNull(masker.mask(null, state.Setting("x", "y")))
    }

    @Test
    fun `missing constructor or fields disable masking`() {
        val noCopy = FakeNoCopyState.Setting::class.java
        assertNull(SettingCopyMasker.of(FakeNoCopyState::class.java, noCopy, listOf("value"), "n"))
        assertNull(
            SettingCopyMasker.of(
                FakeSettingsState::class.java,
                FakeSettingsState.Setting::class.java,
                listOf("value", "missing"),
                "n",
            ),
        )
    }
}
