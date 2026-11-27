package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.status.RelayKeys
import io.github.hyperprivacy.core.tracker.TrackerOrigin
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.core.tracker.TrackerRules
import io.github.hyperprivacy.xposed.origin.AdOrigin
import io.github.hyperprivacy.xposed.origin.AdPageGuard
import io.github.hyperprivacy.xposed.origin.OriginHooks
import io.github.hyperprivacy.xposed.origin.OriginNetGuard
import io.github.hyperprivacy.xposed.origin.OriginWebGuard
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
 * Advertising SDKs are also blocked by origin ([AdOrigin], [OriginNetGuard], [OriginWebGuard],
 * [AdPageGuard]): only when
 * the scan found one (or suspected ad packages), so other protected apps get no extra hook at all.
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
        val rules = TrackerRules.rulesFor(packageName, detected, TrackerOrigin.origin(raw, packageName).userDns)
        val switches = RuleSwitches.open(
            prefs = prefs,
            log = log,
            decode = { TrackerRules.resolve(it, packageName, rules) },
            affects = { TrackerProtection.affects(it, packageName) },
        )
        log.verbose = { switches.observe }
        log.info("tracker protection for $packageName: ${detected.size} SDKs detected, ${rules.size} rules")
        val relay = relayKeys(switches, packageName)
        val extra = { env: GuardEnv ->
            env.log.guard("origin protection") { installOrigin(env, raw, switches) { prefs.all } }
            env.log.guard("live stack guard") { LiveStackGuard(env).install() }
            Unit
        }
        AppGuards(xposed, log, packageName, classLoader, rules, switches, relay, extra).install()
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

    private fun installOrigin(env: GuardEnv, raw: Map<String, *>, switches: RuleSwitches, read: () -> Map<String, *>) {
        if (!AdOrigin.canEverMatch(raw, env.packageName)) return
        // Built from the effective switches: a global pause empties the table like every other rule.
        // refresh() reads the preferences and rebuilds under the origin's own lock, so the last change wins.
        val origin = AdOrigin(env.log, env.packageName, env.reporter, switches, read)
        origin.refresh(raw)
        switches.addListener { origin.refresh() }
        val hooks = OriginHooks(env, origin)
        val count = listOf(
            "network" to { OriginNetGuard(hooks).install() },
            "web" to { OriginWebGuard(hooks).install() },
            "ad pages" to { AdPageGuard(env, origin).install() },
        ).sumOf { (name, install) -> env.log.guard("origin $name") { install() } ?: 0 }
        env.log.info("origin protection for ${env.packageName}: $count entry points, active=${origin.active}")
    }
}
