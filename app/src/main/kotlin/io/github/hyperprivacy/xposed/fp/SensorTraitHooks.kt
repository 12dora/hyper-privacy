package io.github.hyperprivacy.xposed.fp

import android.hardware.Sensor
import io.github.hyperprivacy.core.fingerprint.HardwareTraits
import io.github.hyperprivacy.xposed.GuardEnv
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Field

/**
 * `Sensor#getResolution`, `#getPower` and `#getVersion`: the calibration-like numbers that make a
 * device's sensor list unique. The change is small and stable per app ([HardwareTraits]); sensor
 * events, maximum range, delays, FIFO sizes, name, vendor and type are never touched, so motion,
 * compass, proximity and light logic behave as before.
 *
 * `Sensor#toString` reads its fields directly, not through these getters; its `version=`,
 * `resolution=` and `power=` tokens are rewritten to the same per-app values so the string cannot
 * be used to read the real ones. It is called rarely (logging, dumps), so it reads the three
 * private fields by reflection (looked up once); if they are absent the string is left as it is.
 */
internal class SensorTraitHooks(private val env: GuardEnv, private val state: FingerprintState<HardwareTraits>) {
    /** Returns the number of methods hooked. */
    fun install(): Int = hook<Float>("getResolution", HardwareTraits::resolution) +
        hook<Float>("getPower", HardwareTraits::power) +
        hook<Int>("getVersion", HardwareTraits::version) +
        installToString()

    private inline fun <reified T : Any> hook(name: String, noinline map: (HardwareTraits, T) -> T): Int {
        val method = env.declared(Sensor::class.java, name) ?: return 0
        val hooker = ResultHook(state, HardwareFingerprintGuard.SENSOR_SINK) { real ->
            (real as? T)?.let { state.replace(GROUP, it, map) }
        }
        return env.hookAll(listOf(method), hooker)
    }

    private fun installToString(): Int {
        val method = env.declared(Sensor::class.java, "toString") ?: return 0
        // Read with getInt / getFloat: a field of any other type (an OEM change) leaves toString alone.
        val int = Int::class.javaPrimitiveType
        val float = Float::class.javaPrimitiveType
        val fields = listOf(VERSION_FIELD to int, RESOLUTION_FIELD to float, POWER_FIELD to float).map { (name, type) ->
            runCatching {
                Sensor::class.java.getDeclaredField(name).takeIf { it.type == type }?.apply { isAccessible = true }
            }.getOrNull()
        }
        val (version, resolution, power) = fields
        if (version == null || resolution == null || power == null) {
            env.log.warn("Sensor fields not found or reshaped in ${env.packageName}; toString left as it is")
            return 0
        }
        return env.hookAll(listOf(method), ToStringHook(version, resolution, power))
    }

    private inner class ToStringHook(
        private val version: Field,
        private val resolution: Field,
        private val power: Field,
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val real = chain.proceed()
            return state.safely { rewrite(chain, real) } ?: real
        }

        /** The string with the per-app tokens, or `null` to keep [real]. */
        private fun rewrite(chain: Chain, real: Any?): String? {
            if (!state.enabled) return null
            val text = real as? String ?: return null
            val sensor = chain.thisObject as? Sensor ?: return null
            val traits = state.value() ?: return null
            val v = version.getInt(sensor)
            val r = resolution.getFloat(sensor)
            val p = power.getFloat(sensor)
            // The tokens as AOSP writes them, each after the closing quote of name / vendor or a field.
            val out = text.replaceFirst("\", version=$v,", "\", version=${traits.version(v)},")
                .replaceFirst(", resolution=$r,", ", resolution=${traits.resolution(r)},")
                .replaceFirst(", power=$p,", ", power=${traits.power(p)},")
            if (out == text) return null
            state.hit(GROUP)
            return out
        }
    }

    companion object {
        val GROUP = TraitGroup("sensor")
        private const val VERSION_FIELD = "mVersion"
        private const val RESOLUTION_FIELD = "mResolution"
        private const val POWER_FIELD = "mPower"
    }
}
