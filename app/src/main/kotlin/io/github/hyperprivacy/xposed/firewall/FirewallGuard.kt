package io.github.hyperprivacy.xposed.firewall

import android.os.Handler
import io.github.hyperprivacy.xposed.HookLog
import io.github.hyperprivacy.xposed.ModuleThreads
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.RuleSwitches
import io.github.hyperprivacy.xposed.StatusReporter
import io.github.hyperprivacy.xposed.SystemReady
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bootstraps [FirewallEnforcer] in system_server.
 *
 * `onSystemServerStarting` runs before ActivityManagerService exists, so receivers cannot be
 * registered yet. We observe `ActivityManagerService#systemReady` ([SystemReady]), then hand off
 * to our own thread ([ModuleThreads]).
 *
 * Everything here is guarded: an exception escaping into system_server is a bootloop.
 */
internal class FirewallGuard(private val xposed: XposedInterface, private val log: HookLog) {
    private val started = AtomicBoolean(false)

    fun install(classLoader: ClassLoader): Boolean {
        val hooked = SystemReady.observe(xposed, log, classLoader, "firewall", ::onSystemReady)
        if (hooked == 0) {
            log.warn("firewall: FIREWALL rules inactive")
            return false
        }
        log.info("firewall: waiting for systemReady ($hooked overload(s))")
        return true
    }

    private fun onSystemReady() {
        if (!started.compareAndSet(false, true)) return
        val handler = ModuleThreads.handler(THREAD_NAME, log)
        handler.post { log.guard("firewall start") { start(handler) } }
    }

    private fun start(handler: Handler) {
        val context = Reflection.systemContext() ?: run {
            log.warn("firewall: system context unavailable")
            return
        }
        val firewall = ConnectivityFirewall.resolve(context, log) ?: return
        val switches = RuleSwitches.open(xposed, log, FirewallEnforcer.RULES)
        val reporter = StatusReporter(log, context = { context }, systemServer = true)
        FirewallEnforcer(context, handler, log, firewall, switches, reporter).start()
    }

    private companion object {
        const val THREAD_NAME = "HyperPrivacyFirewall"
    }
}
