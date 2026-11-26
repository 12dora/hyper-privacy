package io.github.hyperprivacy.xposed

import android.content.SharedPreferences
import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Live, read-only view of the user's rule switches inside a hooked process.
 *
 * Backed by libxposed remote preferences, whose change listener fires when the module app writes,
 * so toggles apply without restarting the target or hot-reloading the module.
 */
internal class RuleSwitches private constructor(private val prefs: SharedPreferences?, private val log: HookLog) {
    private val states = AtomicReference(read())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    // Held in a field: listener registries are commonly weak, and losing it would freeze the config.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || ConfigCodec.ruleIdOf(key) != null) refresh()
    }

    init {
        prefs?.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun isEnabled(ruleId: String): Boolean = states.get()[ruleId] ?: false

    /** Registers [listener] to run (on an arbitrary thread) after switches change. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    private fun refresh() {
        states.set(read())
        listeners.forEach { listener -> log.guard("switch listener") { listener() } }
    }

    private fun read(): Map<String, Boolean> = ConfigCodec.decode(log.guard("read switches") { prefs?.all }.orEmpty())

    companion object {
        /** Opens the remote preferences; falls back to catalog defaults if the framework refuses. */
        fun open(xposed: XposedInterface, log: HookLog): RuleSwitches {
            val prefs = log.guard("open remote preferences") { xposed.getRemotePreferences(ConfigCodec.PREFS_GROUP) }
            return RuleSwitches(prefs, log)
        }
    }
}
