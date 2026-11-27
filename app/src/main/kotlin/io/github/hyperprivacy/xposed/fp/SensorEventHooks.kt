package io.github.hyperprivacy.xposed.fp

import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import io.github.hyperprivacy.core.sensor.SensorBlur
import io.github.hyperprivacy.core.sensor.SensorKind
import io.github.hyperprivacy.core.sensor.SensorStream
import io.github.hyperprivacy.xposed.GuardEnv
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.SinkStats
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.ref.WeakReference
import java.lang.reflect.Field

/**
 * Sensor events of rule `sensor.data` ([SensorStream] says what changes), at the one point every
 * listener event passes: `SystemSensorManager$SensorEventQueue#dispatchSensorEvent(int handle,
 * float[] values, int accuracy, long timestamp)`, called from JNI (`android_hardware_SensorManager.cpp`
 * looks it up as `(I[FIJ)V`) once per event and queue, before the queue copies `values` into the
 * listener's `SensorEvent`. `values` is the queue's native scratch array, refilled from the HAL
 * buffer before every call, so it is rewritten in place; dropping an event is returning before the
 * original runs. Trigger (one-shot) sensors use another queue and are none of the kinds changed.
 *
 * Direct channels (`SensorDirectChannel`, shared memory written by the HAL without passing through
 * Java) cannot be rewritten: for the kinds changed, `Sensor#isDirectChannelTypeSupported` answers
 * `false` and `SensorDirectChannel#configure` fails as the platform does for an unsupported sensor
 * (returns 0); stopping a report still works. Apps fall back to listeners.
 *
 * Per event: one volatile read of the switch and of the per-app constants, a scan of the few
 * streams this process has registered (an array published once per new queue and sensor, read
 * without a lock), then O(1) arithmetic in place. No allocation (beyond the boxed arguments the
 * hook API always creates), no lock, no logging, no reflection: the sensor of a (queue, handle)
 * pair is classified once, on its first event, by reading the queue's manager and its handle map.
 * A queue that cannot be classified (fields missing, sensor gone) is passed through untouched.
 */
internal class SensorEventHooks(private val env: GuardEnv, private val state: FingerprintState<SensorBlur>) {
    private val cell = SinkStats.cell(SensorDataGuard.EVENT_SINK)

    /** Returns the number of methods hooked. */
    fun install(): Int = installDispatch() + installDirect()

    private fun installDispatch(): Int {
        val queue = Reflection.loadClass(EVENT_QUEUE, null)
        val base = Reflection.loadClass(BASE_QUEUE, null)
        val method = queue?.let {
            env.declared(
                it,
                "dispatchSensorEvent",
                Int::class.javaPrimitiveType!!,
                FloatArray::class.java,
                Int::class.javaPrimitiveType!!,
                Long::class.javaPrimitiveType!!,
            )
        }
        val manager = base?.let { field(it, MANAGER_FIELD) }
        val handles = Reflection.loadClass(MANAGER, null)?.let { field(it, HANDLES_FIELD) }
        if (method == null || manager == null || handles == null) {
            env.log.warn("sensor event queue not found in ${env.packageName}; events untouched")
            return 0
        }
        return env.hookAll(listOf(method), DispatchHook(Streams(manager, handles)))
    }

    private fun installDirect(): Int {
        val configure = env.declared(
            SensorDirectChannel::class.java,
            "configure",
            Sensor::class.java,
            Int::class.javaPrimitiveType!!,
        )
        val supported = env.declared(Sensor::class.java, "isDirectChannelTypeSupported", Int::class.javaPrimitiveType!!)
        return (configure?.let { env.hookAll(listOf(it), DirectConfigureHook()) } ?: 0) +
            (supported?.let { env.hookAll(listOf(it), DirectSupportedHook()) } ?: 0)
    }

    private fun field(type: Class<*>, name: String): Field? =
        runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    private inner class DispatchHook(private val streams: Streams) : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (!state.enabled) return chain.proceed()
            val deliver = if (SinkStats.enabled) measured(chain) else deliver(chain)
            return if (deliver) chain.proceed() else null
        }

        private fun measured(chain: Chain): Boolean {
            val start = System.nanoTime()
            try {
                return deliver(chain)
            } finally {
                cell.record(System.nanoTime() - start)
            }
        }

        /** Rewrites the event in place; `false` when the rate cap drops it. */
        private fun deliver(chain: Chain): Boolean {
            val blur = state.value() ?: return true
            val queue = chain.thisObject ?: return true
            val handle = chain.getArg(0) as? Int ?: return true
            val stream = streams.find(queue, handle, blur) ?: return true
            if (stream.kind == SensorKind.NONE) return true
            val values = chain.getArg(1) as? FloatArray ?: return true
            val timestamp = chain.getArg(TIMESTAMP_ARG) as? Long ?: return true
            state.hit(GROUP)
            return stream.filter(values, timestamp, blur)
        }
    }

    /** `SensorDirectChannel#configure(Sensor, int)`: starting a changed kind fails with 0. */
    private inner class DirectConfigureHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (!state.enabled) return chain.proceed()
            val sensor = chain.getArg(0) as? Sensor ?: return chain.proceed()
            val rate = chain.getArg(1) as? Int ?: return chain.proceed()
            if (rate == SensorDirectChannel.RATE_STOP || SensorKind.of(sensor.type) == SensorKind.NONE) {
                return chain.proceed()
            }
            state.hit(GROUP)
            return 0
        }
    }

    /** `Sensor#isDirectChannelTypeSupported(int)`: `false` for a changed kind. */
    private inner class DirectSupportedHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val real = chain.proceed()
            if (!state.enabled || real != true) return real
            val sensor = chain.thisObject as? Sensor ?: return real
            return if (SensorKind.of(sensor.type) == SensorKind.NONE) real else false
        }
    }

    /**
     * The streams of this process, one per (event queue, sensor handle). Read lock-free from the
     * queues' threads; a new pair is added under the lock, at most once per queue and sensor.
     * Entries hold their queue weakly; cleared ones are pruned when the next pair is added, and the
     * table never holds more than [MAX_STREAMS] (the oldest entry makes room).
     */
    private class Streams(private val manager: Field, private val handles: Field) {
        private class Entry(queue: Any, val handle: Int, val stream: SensorStream) {
            val queue = WeakReference(queue)
        }

        @Volatile
        private var entries: Array<Entry> = emptyArray()

        fun find(queue: Any, handle: Int, blur: SensorBlur): SensorStream? {
            val current = entries
            for (entry in current) {
                if (entry.handle == handle && entry.queue.get() === queue) return entry.stream
            }
            return add(queue, handle, blur)
        }

        @Synchronized
        private fun add(queue: Any, handle: Int, blur: SensorBlur): SensorStream? {
            entries.firstOrNull { it.handle == handle && it.queue.get() === queue }?.let { return it.stream }
            val kind = kind(queue, handle) ?: return null
            val stream = SensorStream(kind, blur.seed xor (handle.toLong() * SEED_MIX))
            val kept = entries.filter { it.queue.get() != null }.takeLast(MAX_STREAMS - 1)
            entries = (kept + Entry(queue, handle, stream)).toTypedArray()
            return stream
        }

        /** The kind of the sensor [handle] of [queue]'s manager; `null` if it cannot be read. */
        private fun kind(queue: Any, handle: Int): SensorKind? = runCatching {
            val sensors = handles.get(manager.get(queue)) as? Map<*, *>
            (sensors?.get(handle) as? Sensor)?.let { SensorKind.of(it.type) }
        }.getOrNull()
    }

    companion object {
        val GROUP = TraitGroup("sensor-events")
        private const val MANAGER = "android.hardware.SystemSensorManager"
        private const val BASE_QUEUE = "$MANAGER\$BaseEventQueue"
        private const val EVENT_QUEUE = "$MANAGER\$SensorEventQueue"
        private const val MANAGER_FIELD = "mManager"
        private const val HANDLES_FIELD = "mHandleToSensor"
        private const val TIMESTAMP_ARG = 3
        private const val MAX_STREAMS = 64
        private const val SEED_MIX = -0x61c8864680b583ebL
    }
}
