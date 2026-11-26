package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.core.tracker.TrackerRules
import io.github.libxposed.api.XposedInterface

/**
 * Entry for an app outside the catalog that the user protects from tracker SDKs. The module app
 * scanned it offline and stored the detected SDK ids ([TrackerProtection.protectKey]); nothing is
 * scanned here. Builds the synthetic SDK rules plus the app-wide catalog rules and installs the
 * regular guards with switches resolved by [TrackerRules.resolve].
 *
 * The rule set is fixed for the life of the process (a new scan result applies on next start);
 * per-SDK switches and turning protection off apply live.
 */
internal object TrackerGuards {
    fun install(xposed: XposedInterface, log: HookLog, packageName: String, classLoader: ClassLoader) {
        val prefs = RuleSwitches.openPrefs(xposed, log) ?: return
        val raw = log.guard("read tracker config") { prefs.all }.orEmpty()
        val detected = TrackerProtection.detected(raw, packageName) ?: return
        val rules = TrackerRules.rulesFor(packageName, detected)
        val switches = RuleSwitches.open(
            prefs = prefs,
            log = log,
            decode = { TrackerRules.resolve(it, packageName, rules) },
            affects = TrackerProtection::affects,
        )
        log.info("tracker protection for $packageName: ${detected.size} SDKs detected, ${rules.size} rules")
        AppGuards(xposed, log, packageName, classLoader, rules, switches).install()
    }
}
