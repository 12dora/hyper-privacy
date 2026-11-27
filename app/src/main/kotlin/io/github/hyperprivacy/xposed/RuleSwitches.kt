package io.github.hyperprivacy.xposed

import android.content.SharedPreferences
import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.config.ModuleSecret
import io.github.hyperprivacy.core.config.ProtectionPause
import io.github.hyperprivacy.core.rule.Rule
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Live, read-only view of the user's switches inside a hooked process: the [rules] that apply
 * here (only those are decoded, so work scales with this process's rules) and the global
 * diagnostics switch, plus the module secret used for per-app identifier derivation.
 *
 * Backed by libxposed remote preferences, whose change listener fires when the module app writes,
 * so toggles apply without restarting the target or hot-reloading the module.
 *
 * While protection is paused ([ProtectionPause]) the effective switches are simply empty: every
 * check, in every guard, already reads them through [isEnabled], so pausing adds nothing to any hot
 * path. Nothing here runs a timer: the paused state is derived with one clock read whenever the
 * preferences change, and the module app ends a timed pause by removing the deadline (one alarm),
 * which is such a change. A process started after the deadline therefore starts unpaused.
 *
 * [decode] resolves the rule switches from the raw preferences (catalog rules: [ConfigCodec.decode];
 * tracker-protected apps: `TrackerRules.resolve`), and [affects] says which keys it reads, so
 * edits for other processes' rules or apps do not rebuild anything here. The observe switch and
 * the module secret are always watched.
 *
 * Single writer: [refresh] reads the preferences and publishes the snapshot under one lock, with a
 * version that only grows, so a slow reader can never publish an older view over a newer one.
 * Listeners run after the lock is released and read whatever is current; each one serializes its
 * own cold-path rebuild (and re-reads the current switches inside it), so the last change always wins.
 */
internal class RuleSwitches private constructor(
    private val prefs: SharedPreferences?,
    private val log: HookLog,
    private val decode: (Map<String, *>) -> Map<String, Boolean>,
    private val affects: (String?) -> Boolean,
) {
    private class Snapshot(
        val enabled: Map<String, Boolean>,
        val observe: Boolean,
        val secret: ByteArray?,
        val paused: Boolean,
        val version: Long,
    )

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Published under `this`; read lock-free (one volatile read) by every check. */
    @Volatile
    private var snapshot = Snapshot(emptyMap(), observe = false, secret = null, paused = false, version = 0)

    // Held in a field: listener registries are commonly weak, and losing it would freeze the config.
    // Runs on the framework's callback thread (in system_server too): nothing may escape it.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        log.guard("switch change") { if (key == null || key in ALWAYS_WATCHED || affects(key)) refresh() }
    }

    init {
        log.guard("publish switches") { publish() }
        log.guard("watch switches") { prefs?.registerOnSharedPreferenceChangeListener(prefsListener) }
    }

    fun isEnabled(ruleId: String): Boolean = snapshot.enabled[ruleId] ?: false

    /** Diagnostics mode; a single volatile read, cheap enough for every network call. */
    val observe: Boolean get() = snapshot.observe

    /** Whether all protection is paused right now ([ProtectionPause]); every switch then reads as off. */
    val paused: Boolean get() = snapshot.paused

    /** The module secret ([ModuleSecret]), or `null` while the module app has not written it. */
    val secret: ByteArray? get() = snapshot.secret

    /** Registers [listener] to run (on an arbitrary thread) after switches change. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    private fun refresh() {
        log.guard("publish switches") { publish() }
        listeners.forEach { listener -> log.guard("switch listener") { listener() } }
    }

    /** Reads and publishes atomically; the diagnostics flag follows inside the lock, so it cannot go stale either. */
    @Synchronized
    private fun publish() {
        // No preferences: defaults. A read that fails keeps the previous snapshot (the last good switches).
        val raw = if (prefs == null) emptyMap() else log.guard("read switches") { prefs.all } ?: return
        // The only clock read: once per preference change, never per check and never from a timer.
        // Corrupt values read as the safe defaults: not paused, diagnostics off, no secret.
        val paused = log.guard("read pause") {
            ProtectionPause.isPaused(ProtectionPause.untilOf(raw), System.currentTimeMillis())
        } == true
        val next = Snapshot(
            // A rule set that fails to resolve (e.g. after an app or system change) means no rule, never a crash.
            enabled = if (paused) emptyMap() else log.guard("decode switches") { decode(raw) }.orEmpty(),
            observe = log.guard("read observe") { ConfigCodec.isObserveEnabled(raw) } == true,
            secret = log.guard("read secret") { ModuleSecret.parse(raw[ModuleSecret.KEY]) },
            paused = paused,
            version = snapshot.version + 1,
        )
        snapshot = next
        SinkStats.setEnabled(next.observe)
    }

    companion object {
        /** Keys every process reads, whatever its rules. */
        private val ALWAYS_WATCHED = setOf(ConfigCodec.OBSERVE_KEY, ModuleSecret.KEY, ProtectionPause.KEY)

        /**
         * Opens the remote preferences for [rules]; falls back to rule defaults (and observe off)
         * if the framework refuses.
         */
        fun open(xposed: XposedInterface, log: HookLog, rules: List<Rule>): RuleSwitches {
            val ids = rules.mapTo(HashSet()) { it.id }
            return RuleSwitches(
                openPrefs(xposed, log),
                log,
                { raw -> ConfigCodec.decode(raw, rules) },
                { key -> ConfigCodec.ruleIdOf(key) in ids },
            )
        }

        /** Switches with a custom [decode] over keys matched by [affects]. */
        fun open(
            prefs: SharedPreferences?,
            log: HookLog,
            decode: (Map<String, *>) -> Map<String, Boolean>,
            affects: (String?) -> Boolean,
        ): RuleSwitches = RuleSwitches(prefs, log, decode, affects)

        fun openPrefs(xposed: XposedInterface, log: HookLog): SharedPreferences? =
            log.guard("open remote preferences") { xposed.getRemotePreferences(ConfigCodec.PREFS_GROUP) }
    }
}
