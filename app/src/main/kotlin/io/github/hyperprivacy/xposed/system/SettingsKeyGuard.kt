package io.github.hyperprivacy.xposed.system

import android.content.Context
import android.os.Binder
import android.os.Build
import android.provider.Settings
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.system.AppIndex
import io.github.hyperprivacy.core.system.SettingsDenyList
import io.github.hyperprivacy.core.system.SettingsMaskList
import io.github.hyperprivacy.core.system.SettingsObservation
import io.github.hyperprivacy.core.system.SettingsTable
import io.github.hyperprivacy.xposed.FailOpenHooker
import io.github.hyperprivacy.xposed.HookLog
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.Sinks
import io.github.hyperprivacy.xposed.Verdict
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Field

/**
 * The `settings.identifiers` rule: hides [SettingsDenyList] keys from third-party apps, i.e. apps
 * that are not vendor-trusted ([io.github.hyperprivacy.core.system.VendorTrust]: HyperOS data apps
 * signed like the vendor's system apps keep reading the keys they own), SDK sandboxes included.
 *
 * Hooks `com.android.providers.settings.SettingsState#getSettingLocked(String)` in system_server
 * (SettingsProvider runs in the `system` process, from its own APK class loader). Single reads
 * (`call` GET_*, `query` by name) and table listings (`getAll*Settings`, `call` LIST_*) all go
 * through it, and listings skip a setting whose `isNull()` is true. For a hidden key the original
 * runs with an empty name instead, which returns the state's own null setting: the caller sees
 * "no such key", exactly as on a device that never wrote it.
 *
 * [SettingsMaskList] keys (user-set device names) are never hidden: apps and services read them
 * without a fallback. The original runs and its result, a fresh copy of the stored setting
 * (`return new Setting(setting)` in AOSP 16 / main), gets the neutral name in its `value` and
 * `defaultValue` fields; the stored setting is untouched. The fields are resolved once at install;
 * when the shape differs the keys are left alone (real value) rather than hidden.
 *
 * The method runs for every Settings read on the device, under SettingsProvider's lock: the
 * common path is one hash lookup, a few prefix checks and a volatile read; caller classification
 * is a lookup in the [SystemState.index] snapshot, never a PackageManager call.
 *
 * Diagnostics: with observe on, non-AOSP keys (absent from SettingsProvider's `sAll*Settings`)
 * read by third-party apps are recorded by name only, through the observed-endpoints channel
 * ([SettingsObservation]).
 */
internal class SettingsKeyGuard(
    private val xposed: XposedInterface,
    private val log: HookLog,
    private val state: SystemState,
) {
    private val hookedLoaders = HashSet<ClassLoader?>()

    /** Whether at least one class loader's `getSettingLocked` is hooked. */
    @Volatile
    var hooked: Boolean = false
        private set

    /**
     * Installs into [classLoader] (the one defining SettingsProvider); a no-op for a loader already
     * hooked. A second loader is hooked too if the framework handed us a different one than the
     * live provider uses; each hook is independent.
     */
    @Synchronized
    fun install(classLoader: ClassLoader?, via: String): Boolean {
        if (classLoader in hookedLoaders) return true
        val type = Reflection.loadClass(STATE_CLASS, classLoader) ?: run {
            log.warn("settings: $STATE_CLASS not found via $via")
            return false
        }
        val method = runCatching { type.getDeclaredMethod(GET_SETTING, String::class.java) }.getOrNull()
        if (method == null || method.returnType.name != SETTING_CLASS) {
            log.warn("settings: $STATE_CLASS#$GET_SETTING(String) not found via $via")
            return false
        }
        val stateKey = runCatching { type.getDeclaredField(STATE_KEY).apply { isAccessible = true } }.getOrNull()
        val hook = Hook(stateKey, readAospKeys(classLoader), masker(method.returnType, via))
        // PROTECTIVE: we only ever proceed (possibly with another argument), never throw on purpose.
        xposed.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept(FailOpenHooker(log, Sinks.SETTINGS_KEYS, hook::decide))
        hookedLoaders += classLoader
        if (hookedLoaders.size > 1) log.warn("settings: hooked a second class loader via $via")
        hooked = true
        log.info("settings: hooked $STATE_CLASS#$GET_SETTING via $via")
        return true
    }

    /**
     * The class loader of the live SettingsProvider instance (found through the local provider
     * client), or `null` (logged) when it is not local. Hooked after `systemReady` whether or not
     * LSPosed called back for SettingsProvider's package earlier.
     */
    fun providerLoader(context: Context): ClassLoader? {
        val client = context.contentResolver.acquireContentProviderClient(Settings.AUTHORITY) ?: run {
            log.warn("settings: provider ${Settings.AUTHORITY} not resolvable")
            return null
        }
        val loader = try {
            client.localContentProvider?.javaClass?.classLoader
        } finally {
            client.close()
        }
        if (loader == null) log.warn("settings: provider ${Settings.AUTHORITY} is not local to system_server")
        return loader
    }

    /** The device-name substitution for [settingClass], or `null` (logged: names stay real) when its fields differ. */
    private fun masker(settingClass: Class<*>, via: String): Masker? {
        val fields = runCatching {
            MASKED_FIELDS.map { name ->
                val field = settingClass.getDeclaredField(name)
                field.takeIf { it.type == String::class.java }?.apply { isAccessible = true }
            }
        }.getOrNull()
        if (fields == null || fields.any { it == null }) {
            log.warn("settings: $SETTING_CLASS fields $MASKED_FIELDS not found via $via; device names not masked")
            return null
        }
        val marketName = log.guard("read market name") { systemProperty(MARKET_NAME_PROPERTY) }
        return Masker(settingClass, fields.filterNotNull(), SettingsMaskList.neutralName(marketName, Build.MODEL))
    }

    private fun systemProperty(name: String): String? {
        val type = Reflection.loadClass(SYSTEM_PROPERTIES_CLASS, null) ?: return null
        return type.getMethod("get", String::class.java).invoke(null, name) as? String
    }

    /** Writes [neutral] into a returned copy of a masked setting; [settingClass] excludes the null setting. */
    private class Masker(private val settingClass: Class<*>, private val fields: List<Field>, val neutral: String) {
        fun mask(setting: Any?): Any? {
            // The null setting is an anonymous subclass shared by every reader: never written.
            if (setting == null || setting.javaClass !== settingClass) return setting
            for (field in fields) field.set(setting, neutral)
            return setting
        }
    }

    /** SettingsProvider's static `sAll{Global,System,Secure}Settings`: every AOSP-declared key. */
    private fun readAospKeys(classLoader: ClassLoader?): Array<Set<*>?> {
        val provider = Reflection.loadClass(PROVIDER_CLASS, classLoader)
        val sets = arrayOfNulls<Set<*>>(SettingsTable.entries.size)
        SettingsTable.entries.forEach { table ->
            sets[table.type] = log.guard("read AOSP ${table.label} keys") {
                val field = provider?.getDeclaredField(AOSP_FIELDS.getValue(table))
                field?.apply { isAccessible = true }?.get(null) as? Set<*>
            }
        }
        if (sets.any { it == null }) log.warn("settings: AOSP key sets unreadable; vendor-key observation limited")
        return sets
    }

    /** One hooked `getSettingLocked`, with the reflection handles of its own class loader. */
    private inner class Hook(
        private val stateKey: Field?,
        private val aospKeys: Array<Set<*>?>,
        private val masker: Masker?,
    ) {
        fun decide(chain: Chain): Verdict {
            val key = chain.getArg(0) as? String ?: return Verdict.Proceed
            val masked = SettingsMaskList.matches(key) && masker != null
            val denied = masked || SettingsDenyList.matcher.matches(key)
            val switches = state.switches ?: return Verdict.Proceed
            val observe = switches.observe
            if (!denied && !observe) return Verdict.Proceed
            val index = state.index
            val appId = thirdPartyCaller(index)
            if (appId < 0) return Verdict.Proceed
            val hide = denied && switches.isEnabled(RULE_ID)
            if (observe) observe(chain.thisObject, index, appId, key, hide)
            if (!hide) return Verdict.Proceed
            state.reporter?.hit(Catalog.SYSTEM_SCOPE, RULE_ID, "${index.packageOf(appId)} reading $key")
            if (masked) return masked(chain)
            // An empty name makes the original return this state's null setting (TextUtils.isEmpty check).
            return Verdict.ProceedWith(arrayOf(""))
        }

        /** The calling app id when it is a third-party app (SDK sandboxes included), else -1. */
        private fun thirdPartyCaller(index: AppIndex): Int {
            // Other non-application uids proceed.
            val appId = AppIndex.callerAppId(Binder.getCallingUid())
            if (appId < 0) return -1
            // An app id missing from the snapshot is third-party (installed since): fail closed, and rebuild.
            if (!index.isKnown(appId) && !index.isEmpty) state.requestIndexRefresh(appId)
            return if (index.isThirdParty(appId)) appId else -1
        }

        /** The original's result with the neutral name; it is a fresh copy, the stored setting is untouched. */
        private fun masked(chain: Chain): Verdict {
            val masker = masker ?: return Verdict.Proceed
            return Verdict.Return(masker.mask(chain.proceed()))
        }

        private fun observe(settingsState: Any?, index: AppIndex, appId: Int, key: String, hidden: Boolean) {
            val table = stateKey?.getLong(settingsState)?.let(SettingsTable::ofStateKey) ?: return
            val aosp = aospKeys[table.type] ?: return
            if (!hidden && aosp.contains(key)) return
            val pkg = index.packageOf(appId) ?: return
            val reporter = state.reporter ?: return
            val fresh = reporter.observe(pkg, SettingsObservation.host(table), SettingsObservation.path(key), hidden)
            val kind = if (aosp.contains(key)) "key" else "vendor key"
            if (fresh != null) log.info("settings: $pkg read $kind ${table.label}/$key (hidden: $hidden)")
        }
    }

    companion object {
        const val RULE_ID = "settings.identifiers"
        const val SETTINGS_PACKAGE = "com.android.providers.settings"
        private const val STATE_CLASS = "com.android.providers.settings.SettingsState"
        private const val SETTING_CLASS = "$STATE_CLASS\$Setting"
        private const val PROVIDER_CLASS = "com.android.providers.settings.SettingsProvider"
        private const val GET_SETTING = "getSettingLocked"
        private const val STATE_KEY = "mKey"
        private val MASKED_FIELDS = listOf("value", "defaultValue")
        private const val SYSTEM_PROPERTIES_CLASS = "android.os.SystemProperties"
        private const val MARKET_NAME_PROPERTY = "ro.product.marketname"
        private val AOSP_FIELDS = mapOf(
            SettingsTable.GLOBAL to "sAllGlobalSettings",
            SettingsTable.SYSTEM to "sAllSystemSettings",
            SettingsTable.SECURE to "sAllSecureSettings",
        )
    }
}
