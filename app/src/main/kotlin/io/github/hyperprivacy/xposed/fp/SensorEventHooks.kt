package io.github.hyperprivacy.xposed.fp

import android.annotation.SuppressLint
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
import java.lang.reflect.Method

/**
 * Sensor events of rule `sensor.data` ([SensorStream] says what changes), at the point every
 * event of the Java sensor API passes: `SystemSensorManager$SensorEventQueue#dispatchSensorEvent(int
 * handle, float[] values, int accuracy, long timestamp)`, called from JNI (`android_hardware_SensorManager.cpp`
 * looks it up as `(I[FIJ)V`) once per event and queue, before the queue copies `values` into the
 * listener's `SensorEvent`. `values` is the queue's native scratch array, refilled from the HAL
 * buffer before every call, so it is rewritten in place; dropping an event is returning before the
 * original runs. Trigger (one-shot) sensors use another queue and are none of the kinds changed.
 *
 * Also covered, for the kinds changed: additional-info events (`dispatchAdditionalInfoEvent`,
 * `(III[F[I)V`, which can carry the sensor's calibration, internal temperature and sampling
 * details) are not delivered, as on a sensor without additional info; direct channels (shared
 * memory written by the HAL without passing through Java) are reported as unsupported
 * (`Sensor#isDirectChannelTypeSupported` false, `#getHighestDirectReportRateLevel` `RATE_STOP`) and
 * `SensorDirectChannel#configure` fails with 0 as for an unsupported sensor; stopping still works.
 *
 * Not covered: the NDK sensor API (`ASensorManager`, `ASensorEventQueue` in libandroid) reads
 * events in native code without passing through these Java methods.
 *
 * Per event: one volatile read of the switch and of the per-app constants, a scan of the streams
 * this process has registered (an array published once per new queue and sensor, read without a
 * lock), then O(1) arithmetic in place. No allocation (beyond the boxed arguments the hook API
 * always creates), no lock, no logging, no reflection on the event path. Streams are created when
 * a sensor is added to a queue (`SensorEventQueue#addSensorEvent`, which also replaces the stream
 * of a reused dynamic-sensor handle); a queue registered before the hooks were installed gets its
 * stream on its first event, classified by reading the queue's manager and its handle map. A
 * stream that cannot be classified is passed through untouched. Without the module secret the
 * per-app calibration is identity ([SensorBlur.NEUTRAL]) and everything else still applies.
 */
internal class SensorEventHooks(private val env: GuardEnv, private val state: FingerprintState<SensorBlur>) {
    private val cell = SinkStats.cell(SensorDataGuard.EVENT_SINK)

    /** Returns the number of methods hooked. */
    fun install(): Int = installQueue() + installDirect()

    private fun installQueue(): Int {
        val queue = Reflection.loadClass(EVENT_QUEUE, null)
        val dispatch = queue?.let { env.declared(it, "dispatchSensorEvent", INT, FloatArray::class.java, INT, LONG) }
        val streams = streams()
        if (queue == null || dispatch == null || streams == null) {
            env.log.warn("sensor event queue not found in ${env.packageName}; events untouched")
            return 0
        }
        val info = env.declared(
            queue,
            "dispatchAdditionalInfoEvent",
            INT,
            INT,
            INT,
            FloatArray::class.java,
            IntArray::class.java,
        )
        val add = env.declared(queue, "addSensorEvent", Sensor::class.java)
        return env.hookAll(listOf(dispatch), DispatchHook(streams)) +
            env.hookAll(listOfNotNull(info), InfoHook(streams)) +
            env.hookAll(listOfNotNull(add), AddHook(streams))
    }

    /** The stream table, or `null` when the framework fields it reads are missing. */
    // Framework internals read once at install; every lookup fails open when they are absent.
    @SuppressLint("DiscouragedPrivateApi")
    private fun streams(): Streams? {
        val base = Reflection.loadClass(BASE_QUEUE, null) ?: return null
        val manager = field(base, MANAGER_FIELD) ?: return null
        val native = field(base, NATIVE_FIELD) ?: return null
        val handles = Reflection.loadClass(MANAGER, null)?.let { field(it, HANDLES_FIELD) } ?: return null
        val getHandle = runCatching { Sensor::class.java.getDeclaredMethod(GET_HANDLE).apply { isAccessible = true } }
        return Streams(manager, native, handles, getHandle.getOrNull())
    }

    private fun installDirect(): Int {
        val configure = env.declared(SensorDirectChannel::class.java, "configure", Sensor::class.java, INT)
        val supported = env.declared(Sensor::class.java, "isDirectChannelTypeSupported", INT)
        val highest = env.declared(Sensor::class.java, "getHighestDirectReportRateLevel")
        return env.hookAll(listOfNotNull(configure), DirectConfigureHook()) +
            env.hookAll(listOfNotNull(supported), DirectCapabilityHook(false)) +
            env.hookAll(listOfNotNull(highest), DirectCapabilityHook(SensorDirectChannel.RATE_STOP))
    }

    private fun field(type: Class<*>, name: String): Field? =
        runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    private inner class DispatchHook(private val streams: Streams) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val deliver = state.safely { !state.enabled || if (SinkStats.enabled) measured(chain) else deliver(chain) }
            // void: returning null without proceeding drops the event.
            return if (deliver != false) chain.proceed() else null
        }

        private fun measured(chain: Chain): Boolean {
            val start = System.nanoTime()
            try {
                return deliver(chain)
            } finally {
                cell.record(System.nanoTime() - start)
            }
        }

        /** Rewrites the event in place; `false` when it is dropped. */
        private fun deliver(chain: Chain): Boolean {
            val queue = chain.thisObject ?: return true
            val handle = chain.getArg(0) as? Int ?: return true
            val stream = streams.find(queue, handle) ?: return true
            if (stream.kind == SensorKind.NONE) return true
            val values = chain.getArg(1) as? FloatArray ?: return true
            val timestamp = chain.getArg(TIMESTAMP_ARG) as? Long ?: return true
            state.hit(GROUP)
            return stream.filter(values, timestamp, state.value() ?: SensorBlur.NEUTRAL)
        }
    }

    /** `dispatchAdditionalInfoEvent`: not delivered for a changed kind. */
    private inner class InfoHook(private val streams: Streams) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val drop = state.safely { drops(chain) } == true
            return if (drop) null else chain.proceed()
        }

        private fun drops(chain: Chain): Boolean {
            if (!state.enabled) return false
            val queue = chain.thisObject ?: return false
            val handle = chain.getArg(0) as? Int ?: return false
            val kind = streams.find(queue, handle)?.kind ?: SensorKind.NONE
            if (kind == SensorKind.NONE) return false
            state.hit(GROUP)
            return true
        }
    }

    /** `addSensorEvent(Sensor)`: a fresh stream for the sensor just added to this queue. */
    private inner class AddHook(private val streams: Streams) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            state.safely {
                val queue = chain.thisObject
                val sensor = chain.getArg(0) as? Sensor
                if (queue != null && sensor != null) streams.register(queue, sensor)
            }
            return result
        }
    }

    /** `SensorDirectChannel#configure(Sensor, int)`: starting a changed kind fails with 0. */
    private inner class DirectConfigureHook : Hooker {
        // int configure(Sensor, int): 0 is its own "not supported" answer.
        override fun intercept(chain: Chain): Any? = if (state.safely { refuses(chain) } == true) 0 else chain.proceed()

        private fun refuses(chain: Chain): Boolean {
            if (!state.enabled) return false
            val sensor = chain.getArg(0) as? Sensor ?: return false
            val rate = chain.getArg(1) as? Int ?: return false
            if (rate == SensorDirectChannel.RATE_STOP || SensorKind.of(sensor.type) == SensorKind.NONE) return false
            state.hit(GROUP)
            return true
        }
    }

    /** A direct-channel capability of a `Sensor`: [unsupported] for a changed kind. */
    private inner class DirectCapabilityHook(private val unsupported: Any) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val real = chain.proceed()
            val hide = state.safely {
                state.enabled && real != unsupported &&
                    (chain.thisObject as? Sensor)?.let { SensorKind.of(it.type) != SensorKind.NONE } == true
            }
            return if (hide == true) unsupported else real
        }
    }

    /**
     * The streams of this process, one per (event queue, sensor handle). Read lock-free from the
     * queues' threads; entries are added or replaced under the lock, at most once per queue and
     * sensor registration. Entries hold their queue weakly and are pruned, whenever one is added,
     * once the queue is collected or disposed (its native queue is 0), so the table holds only the
     * queues the app is actually using and an active stream is never evicted.
     */
    private class Streams(
        private val manager: Field,
        private val native: Field,
        private val handles: Field,
        private val getHandle: Method?,
    ) {
        private class Entry(queue: Any, val stream: SensorStream) {
            val queue = WeakReference(queue)
        }

        @Volatile
        private var entries: Array<Entry> = emptyArray()

        fun find(queue: Any, handle: Int): SensorStream? {
            val current = entries
            for (entry in current) {
                if (entry.stream.handle == handle && entry.queue.get() === queue) return entry.stream
            }
            return add(queue, handle)
        }

        /** A new stream for [sensor] on [queue], replacing any earlier one of the same handle. */
        fun register(queue: Any, sensor: Sensor) {
            val handle = runCatching { getHandle?.invoke(sensor) as? Int }.getOrNull() ?: return
            put(queue, SensorStream(SensorKind.of(sensor.type), handle), replace = true)
        }

        private fun add(queue: Any, handle: Int): SensorStream? {
            val kind = kind(queue, handle) ?: return null
            return put(queue, SensorStream(kind, handle), replace = false)
        }

        @Synchronized
        private fun put(queue: Any, stream: SensorStream, replace: Boolean): SensorStream {
            val handle = stream.handle
            val existing = entries.firstOrNull { it.stream.handle == handle && it.queue.get() === queue }
            if (existing != null && !replace) return existing.stream
            // Start after the last event any queue received from this sensor (see resumeAfter).
            entries.forEach { if (it.stream.handle == handle) stream.resumeAfter(it.stream.lastTimestamp) }
            val kept = entries.filter { it !== existing && live(it) }
            entries = (kept + Entry(queue, stream)).toTypedArray()
            return stream
        }

        private fun live(entry: Entry): Boolean {
            val queue = entry.queue.get() ?: return false
            return runCatching { native.getLong(queue) != 0L }.getOrDefault(true)
        }

        /** The kind of the sensor [handle] of [queue]'s manager; `null` if it cannot be read. */
        private fun kind(queue: Any, handle: Int): SensorKind? = runCatching {
            val sensors = handles.get(manager.get(queue)) as? Map<*, *>
            (sensors?.get(handle) as? Sensor)?.let { SensorKind.of(it.type) }
        }.getOrNull()
    }

    companion object {
        val GROUP = TraitGroup("sensor-events")
        private val INT = Int::class.javaPrimitiveType!!
        private val LONG = Long::class.javaPrimitiveType!!
        private const val MANAGER = "android.hardware.SystemSensorManager"
        private const val BASE_QUEUE = "$MANAGER\$BaseEventQueue"
        private const val EVENT_QUEUE = "$MANAGER\$SensorEventQueue"
        private const val MANAGER_FIELD = "mManager"
        private const val NATIVE_FIELD = "mNativeSensorEventQueue"
        private const val HANDLES_FIELD = "mHandleToSensor"
        private const val GET_HANDLE = "getHandle"
        private const val TIMESTAMP_ARG = 3
    }
}
