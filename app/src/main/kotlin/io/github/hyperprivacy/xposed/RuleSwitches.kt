package io.github.hyperprivacy.xposed

import android.content.SharedPreferences
import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.config.ModuleSecret
import io.github.hyperprivacy.core.config.ProtectionPause
import io.github.hyperprivacy.core.rule.Rule
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicReference

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
 * path. The snapshot is rebuilt when the deadline preference changes and by a timer when a timed
 * pause ends ([scheduleResume]), which also notifies the listeners (network lists, the firewall).
 *
 * [decode] resolves the rule switches from the raw preferences (catalog rules: [ConfigCodec.decode];
 * tracker-protected apps: `TrackerRules.resolve`), and [affects] says which keys it reads, so
 * edits for other processes' rules or apps do not rebuild anything here. The observe switch and
 * the module secret are always watched.
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
    )

    /** The pending end-of-pause check; guarded by `this`. */
    private var resumeCheck: ScheduledFuture<*>? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val snapshot = AtomicReference(read())

    // Held in a field: listener registries are commonly weak, and losing it would freeze the config.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in ALWAYS_WATCHED || affects(key)) refresh()
    }

    init {
        SinkStats.setEnabled(snapshot.get().observe)
        prefs?.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun isEnabled(ruleId: String): Boolean = snapshot.get().enabled[ruleId] ?: false

    /** Diagnostics mode; a single volatile read, cheap enough for every network call. */
    val observe: Boolean get() = snapshot.get().observe

    /** Whether all protection is paused right now ([ProtectionPause]); every switch then reads as off. */
    val paused: Boolean get() = snapshot.get().paused

    /** The module secret ([ModuleSecret]), or `null` while the module app has not written it. */
    val secret: ByteArray? get() = snapshot.get().secret

    /** Registers [listener] to run (on an arbitrary thread) after switches change. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    private fun refresh() {
        val next = read()
        snapshot.set(next)
        SinkStats.setEnabled(next.observe)
        listeners.forEach { listener -> log.guard("switch listener") { listener() } }
    }

    @Synchronized
    private fun read(): Snapshot {
        val raw = log.guard("read switches") { prefs?.all }.orEmpty()
        val until = ProtectionPause.untilOf(raw)
        // The only clock read: once per preference change or timer tick, never per check.
        val now = System.currentTimeMillis()
        val paused = ProtectionPause.isPaused(until, now)
        scheduleResume(until, now)
        return Snapshot(
            enabled = if (paused) emptyMap() else decode(raw),
            observe = ConfigCodec.isObserveEnabled(raw),
            secret = ModuleSecret.parse(raw[ModuleSecret.KEY]),
            paused = paused,
        )
    }

    /** Looks again at the end of a timed pause (re-armed each time, so a device that slept is caught up). */
    private fun scheduleResume(until: Long, now: Long) {
        resumeCheck?.cancel(false)
        val delay = ProtectionPause.nextCheckDelayMs(until, now)
        resumeCheck = if (delay == null) null else ResumeTimer.schedule(delay, ::refresh)
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
