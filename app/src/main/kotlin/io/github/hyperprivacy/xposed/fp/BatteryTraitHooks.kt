package io.github.hyperprivacy.xposed.fp

import android.content.Intent
import android.os.BatteryManager
import io.github.hyperprivacy.core.fingerprint.HardwareTraits
import io.github.hyperprivacy.xposed.GuardEnv

/**
 * The battery cycle count, which `BatteryManager` publishes only as [BatteryManager.EXTRA_CYCLE_COUNT]
 * of the `ACTION_BATTERY_CHANGED` broadcast (no public `BATTERY_PROPERTY_*` carries it). It wears
 * with use and is therefore nearly unique per device; many devices do not report it at all, so apps
 * already treat it as optional and the extra is simply removed, on a copy, at the points of
 * [BatteryIntentHooks]. Every other extra stays as delivered (rule `sensor.data` rounds some of
 * them separately).
 */
internal class BatteryTraitHooks(private val env: GuardEnv, private val state: FingerprintState<HardwareTraits>) {
    /** Returns the number of methods hooked. */
    fun install(): Int = BatteryIntentHooks(env, state, HardwareFingerprintGuard.BATTERY_SINK, ::stripped).install()

    /** A copy of a battery broadcast without the cycle count, or `null` to keep [intent] as it is. */
    private fun stripped(intent: Intent): Intent? {
        if (!intent.hasExtra(BatteryManager.EXTRA_CYCLE_COUNT)) return null
        state.hit(GROUP)
        return Intent(intent).apply { removeExtra(BatteryManager.EXTRA_CYCLE_COUNT) }
    }

    companion object {
        val GROUP = TraitGroup("battery")
    }
}
