package io.github.hyperprivacy.ui

import java.util.concurrent.ConcurrentHashMap

/** Writes not yet visible in the preferences, so a toggled switch reflects the tap immediately. */
internal class PendingWrites {
    private val values = ConcurrentHashMap<String, Boolean>()

    fun put(key: String, value: Boolean) {
        values[key] = value
    }

    fun remove(key: String) {
        values.remove(key)
    }

    fun clear() = values.clear()

    /** Keys of writes not yet visible in the preferences. */
    fun keys(): Set<String> = values.keys.toSet()

    /** [stored] with the writes still on their way applied, for decisions that must see what the UI shows. */
    fun overlay(stored: Map<String, *>): Map<String, Any?> = if (values.isEmpty()) stored else stored + values

    /** The stored value for [key], unless a write of ours is still on its way. */
    fun resolve(stored: Map<String, *>, key: String, default: Boolean): Boolean {
        val current = stored[key] as? Boolean ?: default
        val pending = values[key] ?: return current
        if (pending == current) values.remove(key)
        return pending
    }
}
