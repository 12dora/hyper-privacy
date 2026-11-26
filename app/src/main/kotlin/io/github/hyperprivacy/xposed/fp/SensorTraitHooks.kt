package io.github.hyperprivacy.xposed.fp

import android.hardware.Sensor
import io.github.hyperprivacy.core.fingerprint.HardwareTraits
import io.github.hyperprivacy.xposed.GuardEnv

/**
 * `Sensor#getResolution`, `#getPower` and `#getVersion`: the calibration-like numbers that make a
 * device's sensor list unique. The change is small and stable per app ([HardwareTraits]); sensor
 * events, maximum range, delays, FIFO sizes, name, vendor and type are never touched, so motion,
 * compass, proximity and light logic behave as before.
 *
 * `Sensor#toString` reads its fields directly, not through these getters, and still shows the real
 * values; it is a logging aid that fingerprinting code does not normally hash.
 */
internal class SensorTraitHooks(private val env: GuardEnv, private val state: FingerprintState) {
    /** Returns the number of methods hooked. */
    fun install(): Int = hook<Float>("getResolution", HardwareTraits::resolution) +
        hook<Float>("getPower", HardwareTraits::power) +
        hook<Int>("getVersion", HardwareTraits::version)

    private inline fun <reified T : Any> hook(name: String, noinline map: (HardwareTraits, T) -> T): Int {
        val method = env.declared(Sensor::class.java, name) ?: return 0
        val hooker = ResultHook(state) { real -> (real as? T)?.let { state.replace(GROUP, it, map) } }
        return env.hookAll(listOf(method), hooker)
    }

    companion object {
        val GROUP = TraitGroup("sensor")
    }
}
