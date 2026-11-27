package io.github.hyperprivacy.xposed.firewall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import io.github.hyperprivacy.core.firewall.FirewallPlan
import io.github.hyperprivacy.core.firewall.UidPolicy
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RuleKind
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.xposed.HookLog
import io.github.hyperprivacy.xposed.RuleSwitches
import io.github.hyperprivacy.xposed.StatusReporter

/**
 * Keeps the firewall deny list equal to the enabled FIREWALL rules. All state is confined to
 * [handler]'s thread; every entry point is posted there and guarded.
 */
internal class FirewallEnforcer(
    private val context: Context,
    private val handler: Handler,
    private val log: HookLog,
    private val firewall: ConnectivityFirewall,
    private val switches: RuleSwitches,
    private val reporter: StatusReporter,
) {
    private val rules: List<Rule> get() = RULES
    private val targets: Set<String> = rules.flatMapTo(HashSet()) { it.packages }
    private val applied = HashSet<Int>()

    /** The state last handed to [reporter] per "rule package"; an unchanged state is not reported again. */
    private val reported = HashMap<String, HookState>()
    private var chainEnabled = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            log.guard("firewall broadcast") {
                val pkg = intent.data?.schemeSpecificPart
                if (pkg == null || pkg in targets) apply()
            }
        }
    }

    fun start() {
        val boot = IntentFilter().apply {
            addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED)
            addAction(Intent.ACTION_BOOT_COMPLETED)
        }
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        // Both are protected broadcasts sent by system_server itself, so NOT_EXPORTED still receives them.
        context.registerReceiver(receiver, boot, null, handler, Context.RECEIVER_NOT_EXPORTED)
        context.registerReceiver(receiver, packages, null, handler, Context.RECEIVER_NOT_EXPORTED)
        switches.addListener { handler.post(::applySafely) }
        applySafely()
    }

    private fun applySafely() {
        log.guard("apply firewall") { apply() }
    }

    private fun apply() {
        if (!chainEnabled) {
            firewall.enableChain()
            chainEnabled = true
        }
        val desired = HashSet<Int>()
        rules.forEach { rule ->
            rule.packages.forEach { pkg ->
                val uid = eligibleUid(rule, pkg)
                report(rule, pkg, if (uid != null) HookState.ACTIVE else HookState.MISSED)
                if (uid != null && switches.isEnabled(rule.id)) desired += uid
            }
        }
        val plan = FirewallPlan.diff(applied, desired)
        plan.toDeny.forEach { uid -> log.guard("deny uid $uid") { firewall.deny(uid) }?.let { applied += uid } }
        plan.toClear.forEach { uid -> log.guard("clear uid $uid") { firewall.clear(uid) }?.let { applied -= uid } }
        if (!plan.isEmpty) log.info("firewall: denied ${plan.toDeny}, cleared ${plan.toClear}")
    }

    /** Switch changes re-run [apply]; only a state that changed reaches the reporter (and so the module app). */
    private fun report(rule: Rule, pkg: String, state: HookState) {
        if (reported.put("${rule.id} $pkg", state) != state) reporter.state(pkg, rule.id, state)
    }

    /** The package's uid if it is safe to firewall, else `null` with the reason logged. */
    private fun eligibleUid(rule: Rule, pkg: String): Int? {
        val pm = context.packageManager
        val uid = try {
            pm.getPackageUid(pkg, PackageManager.PackageInfoFlags.of(0))
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val sharing = pm.getPackagesForUid(uid)?.size ?: 0
        val refusal = UidPolicy.refusal(uid, sharing) ?: return uid
        log.warn("${rule.id}: refusing to firewall $pkg (uid $uid, $sharing package(s)): $refusal")
        return null
    }

    companion object {
        /** The FIREWALL rules of the catalog: the only ones system_server enforces. */
        val RULES: List<Rule> by lazy { Catalog.rules.filter { it.kind == RuleKind.FIREWALL } }
    }
}
