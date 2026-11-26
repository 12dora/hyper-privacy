package io.github.hyperprivacy.xposed.system

import android.annotation.SuppressLint
import android.os.Handler
import android.os.HandlerThread
import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.IdentifierSource
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.scope.PackageRules
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.RetryBackoff
import io.github.hyperprivacy.xposed.HookLog
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.RuleSwitches
import io.github.hyperprivacy.xposed.Sinks
import io.github.hyperprivacy.xposed.StatusReporter
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * system_server-side protections that act on behalf of every app: [SettingsKeyGuard] (rule
 * `settings.identifiers`) and [AppListGuard] (per-app app-list hiding), plus the status relay of
 * protected third-party apps ([StatusRelayGuard]).
 *
 * Lifecycle:
 * 1. `onSystemServerStarting`: hook `AppsFilterBase` (services.jar) and `ActivityManagerService#
 *    systemReady`. Policies start empty, so nothing is hidden yet.
 * 2. `onPackageReady` for SettingsProvider, if LSPosed calls back for that package inside
 *    system_server: hook `SettingsState` and `SettingsProvider#call` from its class loader.
 * 3. After `systemReady`, on our own thread: open switches and the reporter, build the app index
 *    and policy ([PackageIndexer]), hook both from the live provider if step 2 did not, and report
 *    hook states.
 *
 * Everything is guarded: an exception escaping into system_server is a bootloop.
 */
internal class SystemServerGuards(private val xposed: XposedInterface, private val log: HookLog) {
    private val state = SystemState()
    private val settings = SettingsKeyGuard(xposed, log, state)
    private val appList = AppListGuard(xposed, log, state)
    private val relay = StatusRelayGuard(xposed, log, state)
    private val started = AtomicBoolean(false)

    // system_server internals are the whole point here; absence is handled and logged.
    @SuppressLint("PrivateApi")
    fun install(classLoader: ClassLoader) {
        log.guard("app-list filter install") { appList.install(classLoader) }
        val ams = Reflection.loadClass(AMS_CLASS, classLoader)
        val targets = ams?.declaredMethods?.filter { it.name == SYSTEM_READY }.orEmpty()
        if (targets.isEmpty()) {
            log.warn("system guards: $AMS_CLASS#$SYSTEM_READY not found; settings and app-list policies stay empty")
            return
        }
        targets.forEach { method ->
            xposed.hook(method).intercept { chain ->
                val result = chain.proceed()
                log.guard("system guards bootstrap") { onSystemReady() }
                result
            }
        }
    }

    /** Any package loaded into system_server; only SettingsProvider matters. */
    fun onPackageReady(packageName: String, classLoader: ClassLoader) {
        if (packageName != SettingsKeyGuard.SETTINGS_PACKAGE) return
        settings.install(classLoader, "onPackageReady")
        log.guard("status relay install") { relay.install(classLoader, "onPackageReady") }
    }

    private fun onSystemReady() {
        if (!started.compareAndSet(false, true)) return
        val thread = HandlerThread(THREAD_NAME).apply { start() }
        val handler = Handler(thread.looper)
        handler.post { log.guard("system guards start") { start(handler) } }
    }

    private fun start(handler: Handler) {
        val context = Reflection.systemContext() ?: run {
            log.warn("system guards: system context unavailable")
            return
        }
        // Never slows below the 5 min cap: relayed status must flow again soon after the user unlocks.
        val backoff = RetryBackoff(slowMillis = RetryBackoff.DEFAULT_MAX_MILLIS)
        val reporter = StatusReporter(log, context = { context }, backoff = backoff, systemServer = true)
        state.reporter = reporter
        state.switches = RuleSwitches.open(xposed, log, RULES)
        val prefs = log.guard("open app-list preferences") { xposed.getRemotePreferences(ConfigCodec.PREFS_GROUP) }
        log.guard("app index") { PackageIndexer(context, handler, log, state, prefs).start() }
        val providerLoader = log.guard("settings provider loader") { settings.providerLoader(context) }
        if (providerLoader != null) {
            log.guard("settings provider hook") { settings.install(providerLoader, "provider instance") }
            log.guard("status relay hook") { relay.install(providerLoader, "provider instance") }
        }
        val settingsState = if (settings.hooked) HookState.ACTIVE else HookState.MISSED
        RULES.filter { (it.payload as? RulePayload.Identifiers)?.source == IdentifierSource.SETTINGS_KEYS }
            .forEach { reporter.state(Catalog.SYSTEM_SCOPE, it.id, settingsState) }
        reporter.sink(Catalog.SYSTEM_SCOPE, Sinks.SETTINGS_KEYS, settingsState)
        val appListState = if (appList.hooked) HookState.ACTIVE else HookState.MISSED
        reporter.sink(Catalog.SYSTEM_SCOPE, Sinks.APP_LIST_FILTER, appListState)
        val relayState = if (relay.hooked) HookState.ACTIVE else HookState.MISSED
        reporter.sink(Catalog.SYSTEM_SCOPE, Sinks.STATUS_RELAY, relayState)
    }

    private companion object {
        const val AMS_CLASS = "com.android.server.am.ActivityManagerService"
        const val SYSTEM_READY = "systemReady"
        const val THREAD_NAME = "HyperPrivacySystem"

        /** Rules enforced in system_server on behalf of all apps (FIREWALL rules have their own path). */
        val RULES by lazy { PackageRules.catalog.inProcess(Catalog.SYSTEM_SCOPE) }
    }
}
