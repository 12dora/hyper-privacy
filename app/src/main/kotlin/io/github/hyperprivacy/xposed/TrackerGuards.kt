package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.status.RelayKeys
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.core.tracker.TrackerRules
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicReference

/**
 * Entry for an app outside the catalog that the user protects from tracker SDKs. The module app
 * scanned it offline and stored the detected SDK ids ([TrackerProtection.protectKey]); nothing is
 * scanned here. Builds the synthetic SDK rules plus the app-wide catalog rules and installs the
 * regular guards with switches resolved by [TrackerRules.resolve].
 *
 * The rule set is fixed for the life of the process (a new scan result applies on next start);
 * per-SDK switches and turning protection off apply live.
 *
 * Status goes through system_server ([io.github.hyperprivacy.core.status.StatusRelay]) with this
 * app's own [RelayKeys]: it never resolves the module package, which stays hidden from it.
 */
internal object TrackerGuards {
    fun install(xposed: XposedInterface, log: HookLog, packageName: String, classLoader: ClassLoader) {
        // This app's SDKs read its logcat: nothing about the module is written outside diagnostics mode.
        log.quiet = true
        log.verbose = { false }
        val prefs = RuleSwitches.openPrefs(xposed, log) ?: return
        val raw = log.guard("read tracker config") { prefs.all }.orEmpty()
        val detected = TrackerProtection.detected(raw, packageName) ?: return
        val rules = TrackerRules.rulesFor(packageName, detected)
        val switches = RuleSwitches.open(
            prefs = prefs,
            log = log,
            decode = { TrackerRules.resolve(it, packageName, rules) },
            affects = { TrackerProtection.affects(it, packageName) },
        )
        log.verbose = { switches.observe }
        log.info("tracker protection for $packageName: ${detected.size} SDKs detected, ${rules.size} rules")
        AppGuards(xposed, log, packageName, classLoader, rules, switches, relayKeys(switches, packageName)).install()
    }

    /** This app's relay credentials for the current module secret, derived once per secret. */
    private fun relayKeys(switches: RuleSwitches, packageName: String): () -> RelayKeys? {
        val cached = AtomicReference<Pair<ByteArray, RelayKeys>?>()
        return {
            switches.secret?.let { secret ->
                cached.get()?.takeIf { it.first === secret }?.second
                    ?: RelayKeys.derive(secret, packageName).also { cached.set(secret to it) }
            }
        }
    }
}
