package io.github.hyperprivacy.xposed

import android.os.Process
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.status.HookState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies the process's host-level block list ([NetworkPolicy], DOMAIN rules with keep hosts) to
 * name resolution from native code (rule `native.dns`, protected third-party apps only).
 *
 * Mechanism: `libhpnative.so` redirects the import slots (GOT) of `getaddrinfo`, its Android
 * network variants and `gethostbyname*` in the app's own libraries (never platform libraries,
 * never this module's) to hooks that answer a blocked name with the platform's own "no such
 * host" result and tail-call the original otherwise. Libraries loaded later are patched after each
 * `System.loadLibrary` / `System.load` ([NativeLibraries.afterEveryLoad]).
 *
 * Zero cost elsewhere: the library is loaded only in a process where the rule is on as the app
 * starts (turning it on applies after a restart). Turning it off, or pausing protection, applies
 * at once: the switch listener publishes an empty table, and every lookup passes straight through;
 * libraries loaded meanwhile are patched when it is turned back on.
 *
 * Kill switch: one attempt per process ([attempted]); any failure leaves everything unpatched,
 * reports the rule as not active and is never retried. The native side validates every ELF
 * structure it reads and skips any slot or library it cannot prove safe (fail open).
 */
internal class NativeResolverGuard(private val env: GuardEnv, private val policy: NetworkPolicy) {
    private val lock = Any()

    /** The table last handed to native code (`null`: none); guarded by [lock]. */
    private var published: ByteArray? = null

    /** Whether the rule was on at the last [publish]; read by the load observer without the lock. */
    @Volatile
    private var active = false

    /** Set once the native side is installed; before that a rescan would be refused anyway. */
    @Volatile
    private var installed = false

    fun install(rule: Rule) {
        // Off at start: nothing is loaded and nothing is reported (it is not expected to run).
        if (!env.switches.isEnabled(rule.id)) return
        // Isolated and other non-app processes of the package: silently out of scope, so their
        // state never overwrites the main process's report.
        if (!Process.isApplicationUid(Process.myUid())) return
        // Another package of the same process (shared uid) already installed it: nothing to report.
        if (!attempted.compareAndSet(false, true)) return
        val installed = env.log.guard("native resolver install") { start(rule) } == true
        env.reporter.state(env.packageName, rule.id, if (installed) HookState.ACTIVE else HookState.MISSED)
    }

    private fun start(rule: Rule): Boolean {
        if (!load()) return false
        // The table goes first, so the hooks never see a lookup before it is in place.
        publish(rule)
        val patched = NativeResolver.nativeInstall()
        if (patched < 0) {
            env.log.warn("native resolver unavailable ($patched)")
            synchronized(lock) {
                NativeResolver.nativeSetTable(null)
                published = null
            }
            return false
        }
        installed = true
        // Runs after NetworkPolicy's own listener (registered earlier), so the policy is current.
        env.switches.addListener { publish(rule) }
        // A change that landed while installing found no listener yet: catch up once.
        publish(rule)
        // Libraries loaded while the rule is off are patched when it is turned back on (publish).
        val observed = NativeLibraries.afterEveryLoad(env.xposed, env.log) {
            if (active) NativeResolver.nativeRescan()
        }
        // A library loaded between the first scan and the observers going in: one more (cheap) pass.
        if (active) NativeResolver.nativeRescan()
        env.log.info("native resolver in ${env.packageName}: $patched slots, $observed load observers")
        return true
    }

    /**
     * Serialized, and re-reads the switch inside, so the last change always wins. Hands native
     * code a new table only when its content changed (each one stays allocated for good there).
     * Turning the rule back on rescans for libraries loaded meanwhile, outside the lock (a scan
     * waits for the loader, which must never wait for this lock).
     */
    private fun publish(rule: Rule) {
        val resume = synchronized(lock) {
            val enabled = env.switches.isEnabled(rule.id)
            val next = if (enabled) policy.nativeTable().takeIf { it.isNotEmpty() } else null
            val wasActive = active
            active = enabled
            if (!(next contentEquals published)) {
                NativeResolver.nativeSetTable(next)
                published = next
            }
            enabled && !wasActive && installed
        }
        if (resume) NativeResolver.nativeRescan()
    }

    private fun load(): Boolean = try {
        System.loadLibrary(NativeResolver.LIBRARY)
        true
    } catch (error: Throwable) {
        // No library for this ABI, or the framework's class loader cannot map it: rule not active.
        env.log.warn("native resolver library not loaded", error)
        false
    }

    private companion object {
        /** Set on the first install attempt in this process; never reset. */
        val attempted = AtomicBoolean()
    }
}
