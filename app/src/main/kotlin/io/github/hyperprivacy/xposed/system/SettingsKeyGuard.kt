package io.github.hyperprivacy.xposed.system

import android.content.Context
import android.os.Binder
import android.os.Build
import android.provider.Settings
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.system.AppIndex
import io.github.hyperprivacy.core.system.SettingCopyMasker
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
import java.lang.reflect.Modifier

/**
 * The `settings.identifiers` rule: hides [SettingsDenyList] keys from third-party apps, i.e. apps
 * that are not vendor-trusted ([io.github.hyperprivacy.core.system.VendorTrust]: HyperOS data apps
 * signed like the vendor's system apps keep reading the keys they own), SDK sandboxes included.
 *
 * Hooks `com.android.providers.settings.SettingsState#getSettingLocked(String)` in system_server
 * (SettingsProvider runs in the `system` process, from its own APK class loader). Single reads
 * (`call` GET_*, `query` by name) and table listings (`getAll*Settings`, `call` LIST_*) all go
 * through it, and listings skip a setting whose `isNull()` is true. For a hidden key the hook returns
 * the state's own null setting (`mNullSetting`, what the original returns for an absent key)
 * without running the original: the caller sees "no such key", exactly as on a device that never
 * wrote it. The field is resolved and type-checked at install; without it nothing is hidden.
 *
 * [SettingsMaskList] keys (user-set device names) are never hidden: apps and services read them
 * without a fallback. The original runs once ([Verdict.Transform]) and the caller gets a new copy of
 * its result (`Setting(Setting)` copy constructor, AOSP 16 / main) with the neutral name in `value`
 * and `defaultValue`. The returned object itself is never written, so even a build that returned
 * the stored setting keeps it intact; the null setting (key absent) is returned as is and counts no
 * hit. Constructor and fields are resolved once at install; when the shape differs the keys are
 * left alone (real value) rather than hidden, and any failure returns the original result.
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
        val nullSetting = runCatching {
            type.getDeclaredField(NULL_SETTING)
                .takeIf { !Modifier.isStatic(it.modifiers) && it.type == method.returnType }
                ?.apply { isAccessible = true }
        }.getOrNull()
        if (nullSetting == null) log.warn("settings: $STATE_CLASS#$NULL_SETTING not found via $via; keys not hidden")
        val hook = Hook(stateKey, nullSetting, readAospKeys(classLoader), masker(type, method.returnType, via))
        // PROTECTIVE: we only ever proceed or return a value of the method's type, never throw on purpose.
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

    /** The device-name substitution for [settingClass], or `null` (logged: names stay real) when its shape differs. */
    private fun masker(stateClass: Class<*>, settingClass: Class<*>, via: String): SettingCopyMasker? {
        val marketName = log.guard("read market name") { systemProperty(MARKET_NAME_PROPERTY) }
        val neutral = SettingsMaskList.neutralName(marketName, Build.MODEL)
        return SettingCopyMasker.of(stateClass, settingClass, MASKED_FIELDS, neutral) ?: run {
            log.warn("settings: $SETTING_CLASS copy constructor or $MASKED_FIELDS not found via $via; names not masked")
            null
        }
    }

    private fun systemProperty(name: String): String? {
        val type = Reflection.loadClass(SYSTEM_PROPERTIES_CLASS, null) ?: return null
        return type.getMethod("get", String::class.java).invoke(null, name) as? String
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
        private val nullSetting: Field?,
        private val aospKeys: Array<Set<*>?>,
        private val masker: SettingCopyMasker?,
    ) {
        fun decide(chain: Chain): Verdict {
            val key = chain.getArg(0) as? String ?: return Verdict.Proceed
            val masked = SettingsMaskList.matches(key) && masker != null
            val denied = masked || (nullSetting != null && SettingsDenyList.matcher.matches(key))
            val switches = state.switches ?: return Verdict.Proceed
            val observe = switches.observe
            if (!denied && !observe) return Verdict.Proceed
            val index = state.index
            val appId = thirdPartyCaller(index)
            if (appId < 0) return Verdict.Proceed
            val hide = denied && switches.isEnabled(RULE_ID)
            if (observe) observe(chain.thisObject, index, appId, key, hide)
            if (!hide) return Verdict.Proceed
            val detail = "${index.packageOf(appId)} reading $key"
            if (masked) return masked(chain, detail)
            // The state's own "absent" answer, of the method's declared type; unreadable: the real value.
            val absent = nullSetting?.get(chain.thisObject) ?: return Verdict.Proceed
            state.reporter?.hit(Catalog.SYSTEM_SCOPE, RULE_ID, detail)
            return Verdict.Return(absent)
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

        /** The original runs once; its result is replaced by a masked copy (a hit) when the key is set. */
        private fun masked(chain: Chain, detail: String): Verdict {
            val masker = masker ?: return Verdict.Proceed
            val settingsState = chain.thisObject
            return Verdict.Transform { setting ->
                masker.mask(settingsState, setting)?.also { state.reporter?.hit(Catalog.SYSTEM_SCOPE, RULE_ID, detail) }
                    ?: setting
            }
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
        private const val NULL_SETTING = "mNullSetting"
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
