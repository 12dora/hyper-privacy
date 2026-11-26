package io.github.hyperprivacy.xposed.firewall

import android.annotation.SuppressLint
import android.os.Handler
import android.os.HandlerThread
import io.github.hyperprivacy.xposed.HookLog
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.RuleSwitches
import io.github.hyperprivacy.xposed.StatusReporter
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bootstraps [FirewallEnforcer] in system_server.
 *
 * `onSystemServerStarting` runs before ActivityManagerService exists, so receivers cannot be
 * registered yet. We observe `ActivityManagerService#systemReady` (every overload, matched by
 * name only so signature changes across releases do not matter): the original runs unchanged,
 * then we hand off to our own thread. No arguments or results are touched.
 *
 * Everything here is guarded: an exception escaping into system_server is a bootloop.
 */
internal class FirewallGuard(private val xposed: XposedInterface, private val log: HookLog) {
    private val started = AtomicBoolean(false)

    // system_server internals are the whole point here; absence is handled and logged below.
    @SuppressLint("PrivateApi")
    fun install(classLoader: ClassLoader): Boolean {
        val ams = runCatching { Class.forName(AMS_CLASS, false, classLoader) }.getOrNull()
        val targets = ams?.declaredMethods?.filter { it.name == SYSTEM_READY }.orEmpty()
        if (targets.isEmpty()) {
            log.warn("firewall: $AMS_CLASS#$SYSTEM_READY not found; FIREWALL rules inactive")
            return false
        }
        targets.forEach { method ->
            xposed.hook(method).intercept { chain ->
                val result = chain.proceed()
                log.guard("firewall bootstrap") { onSystemReady() }
                result
            }
        }
        log.info("firewall: waiting for systemReady (${targets.size} overload(s))")
        return true
    }

    private fun onSystemReady() {
        if (!started.compareAndSet(false, true)) return
        val thread = HandlerThread(THREAD_NAME).apply { start() }
        val handler = Handler(thread.looper)
        handler.post { log.guard("firewall start") { start(handler) } }
    }

    private fun start(handler: Handler) {
        val context = Reflection.systemContext() ?: run {
            log.warn("firewall: system context unavailable")
            return
        }
        val firewall = ConnectivityFirewall.resolve(context, log) ?: return
        val switches = RuleSwitches.open(xposed, log, FirewallEnforcer.RULES)
        val reporter = StatusReporter(log, context = { context })
        FirewallEnforcer(context, handler, log, firewall, switches, reporter).start()
    }

    private companion object {
        const val AMS_CLASS = "com.android.server.am.ActivityManagerService"
        const val SYSTEM_READY = "systemReady"
        const val THREAD_NAME = "HyperPrivacyFirewall"
    }
}
