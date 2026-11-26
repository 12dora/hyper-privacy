package io.github.hyperprivacy.xposed

import android.content.SharedPreferences
import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.rule.Rule
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Live, read-only view of the user's switches inside a hooked process: the [rules] that apply
 * here (only those are decoded, so work scales with this process's rules) and the global
 * diagnostics switch.
 *
 * Backed by libxposed remote preferences, whose change listener fires when the module app writes,
 * so toggles apply without restarting the target or hot-reloading the module.
 */
internal class RuleSwitches private constructor(
    private val prefs: SharedPreferences?,
    private val log: HookLog,
    private val rules: List<Rule>,
) {
    private class Snapshot(val enabled: Map<String, Boolean>, val observe: Boolean)

    private val snapshot = AtomicReference(read())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    // Held in a field: listener registries are commonly weak, and losing it would freeze the config.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (ConfigCodec.affectsHooks(key)) refresh()
    }

    init {
        prefs?.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun isEnabled(ruleId: String): Boolean = snapshot.get().enabled[ruleId] ?: false

    /** Diagnostics mode; a single volatile read, cheap enough for every network call. */
    val observe: Boolean get() = snapshot.get().observe

    /** Registers [listener] to run (on an arbitrary thread) after switches change. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    private fun refresh() {
        snapshot.set(read())
        listeners.forEach { listener -> log.guard("switch listener") { listener() } }
    }

    private fun read(): Snapshot {
        val raw = log.guard("read switches") { prefs?.all }.orEmpty()
        return Snapshot(ConfigCodec.decode(raw, rules), ConfigCodec.isObserveEnabled(raw))
    }

    companion object {
        /**
         * Opens the remote preferences for [rules]; falls back to rule defaults (and observe off)
         * if the framework refuses.
         */
        fun open(xposed: XposedInterface, log: HookLog, rules: List<Rule>): RuleSwitches {
            val prefs = log.guard("open remote preferences") { xposed.getRemotePreferences(ConfigCodec.PREFS_GROUP) }
            return RuleSwitches(prefs, log, rules)
        }
    }
}
