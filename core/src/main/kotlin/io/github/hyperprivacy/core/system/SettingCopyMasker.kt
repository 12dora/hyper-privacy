package io.github.hyperprivacy.core.system

import java.lang.reflect.Constructor
import java.lang.reflect.Field

/**
 * Builds masked copies of SettingsProvider's `SettingsState$Setting` for [SettingsMaskList] keys:
 * a new instance through the inner class's copy constructor `Setting(SettingsState, Setting)`, with
 * [neutral] written into the `String` [fields] of the copy only. The setting passed in is never
 * written (it may be the stored one, or the null setting every reader shares). Reflection handles
 * are resolved once ([of]); [mask] costs one allocation, and only for a masked read.
 */
public class SettingCopyMasker private constructor(
    private val settingClass: Class<*>,
    private val copy: Constructor<*>,
    private val fields: List<Field>,
    public val neutral: String,
) {
    /**
     * The masked copy of [setting] read from [settingsState], or `null` when there is nothing to
     * mask: no state, no setting, or a subclass (the null setting of an absent key).
     */
    public fun mask(settingsState: Any?, setting: Any?): Any? {
        if (settingsState == null || setting == null || setting.javaClass !== settingClass) return null
        val masked = copy.newInstance(settingsState, setting)
        for (field in fields) field.set(masked, neutral)
        return masked
    }

    public companion object {
        /**
         * A masker for inner class [settingClass] of [stateClass], or `null` when the copy
         * constructor or one of the `String` fields named [fieldNames] is missing.
         */
        public fun of(
            stateClass: Class<*>,
            settingClass: Class<*>,
            fieldNames: List<String>,
            neutral: String,
        ): SettingCopyMasker? = runCatching {
            val copy = settingClass.getDeclaredConstructor(stateClass, settingClass).apply { isAccessible = true }
            val fields = fieldNames.map { name ->
                settingClass.getDeclaredField(name).takeIf { it.type == String::class.java }
                    ?.apply { isAccessible = true } ?: return null
            }
            SettingCopyMasker(settingClass, copy, fields, neutral)
        }.getOrNull()
    }
}
