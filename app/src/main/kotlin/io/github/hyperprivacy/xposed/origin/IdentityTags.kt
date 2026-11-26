package io.github.hyperprivacy.xposed.origin

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * A weak, identity-keyed map from app objects (calls, sockets, requests, web views) to a tag.
 *
 * Keys are compared by identity and hashed with `System.identityHashCode`, so the app's own
 * `hashCode` / `equals` are never called: objects are tagged while their constructor has not run
 * yet, and app subclasses may override both. A key does not keep its object alive; entries of
 * collected objects are dropped on the next [put].
 *
 * Reads are lock-free ([ConcurrentHashMap]) and allocation-free (a per-thread probe key); writes
 * allocate one weak key. Nothing here takes a global lock, so the UI thread never waits on a
 * network thread.
 */
internal class IdentityTags<V : Any> {
    private val queue = ReferenceQueue<Any>()
    private val map = ConcurrentHashMap<Any, V>()
    private val probes = ThreadLocal.withInitial { Probe() }

    /** A stored key: the object held weakly, its identity hash fixed at creation. */
    private class Key(target: Any, queue: ReferenceQueue<Any>) : WeakReference<Any>(target, queue) {
        private val hash = System.identityHashCode(target)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (other === this) return true
            if (other !is Key || other.hash != hash) return false
            val target = get()
            return target != null && target === other.get()
        }
    }

    /** A lookup key; `ConcurrentHashMap` always calls `probe.equals(stored)`, never the reverse. */
    private class Probe {
        var target: Any? = null
        var hash = 0

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = other is Key && other.get().let { it != null && it === target }
    }

    operator fun get(target: Any): V? {
        if (map.isEmpty()) return null
        val probe = probes.get() ?: Probe()
        probe.target = target
        probe.hash = System.identityHashCode(target)
        try {
            return map[probe]
        } finally {
            probe.target = null
        }
    }

    fun put(target: Any, value: V) {
        expunge()
        map[Key(target, queue)] = value
    }

    private fun expunge() {
        while (true) {
            val stale = queue.poll() ?: return
            map.remove(stale)
        }
    }
}
