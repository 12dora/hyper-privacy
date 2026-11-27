package io.github.hyperprivacy.xposed.fp

import android.content.Intent
import android.os.BatteryManager
import io.github.hyperprivacy.core.sensor.BatteryReadings
import io.github.hyperprivacy.core.sensor.SensorBlur
import io.github.hyperprivacy.xposed.GuardEnv
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker

/**
 * Battery readings of rule `sensor.data`, rounded as [BatteryReadings] describes:
 * `BatteryManager#getIntProperty(int)` and `#getLongProperty(int)` (current now / average, charge
 * and energy counters; every other property passes), `#computeChargeTimeRemaining`, and the
 * voltage, temperature and hidden `charge_counter` extras of `ACTION_BATTERY_CHANGED` at the
 * points of [BatteryIntentHooks], on a copy. Level, scale, status, health, plug type and the rest stay as delivered.
 *
 * Property reads are rare (each one is a binder call to the battery service, far heavier than the
 * rounding); a battery intent is copied only when a rounded extra differs.
 */
internal class BatteryReadingHooks(private val env: GuardEnv, private val state: FingerprintState<SensorBlur>) {
    /** Returns the number of methods hooked. */
    fun install(): Int {
        val properties = listOfNotNull(
            env.declared(BatteryManager::class.java, "getIntProperty", java.lang.Integer.TYPE),
            env.declared(BatteryManager::class.java, "getLongProperty", java.lang.Integer.TYPE),
        )
        val chargeTime = listOfNotNull(env.declared(BatteryManager::class.java, "computeChargeTimeRemaining"))
        return env.hookAll(properties, PropertyHook()) +
            env.hookAll(chargeTime, ResultHook(state, SensorDataGuard.BATTERY_SINK, ::chargeTime)) +
            BatteryIntentHooks(env, state, SensorDataGuard.BATTERY_SINK, ::rounded).install()
    }

    private fun chargeTime(real: Any?): Any? {
        val millis = real as? Long ?: return null
        val out = BatteryReadings.chargeTimeRemaining(millis)
        if (out == millis) return null
        state.hit(GROUP)
        return out
    }

    /** A copy of [intent] with rounded readings, or `null` when rounding changes nothing. */
    private fun rounded(intent: Intent): Intent? {
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val charge = intent.getIntExtra(EXTRA_CHARGE_COUNTER, 0)
        val newVoltage = BatteryReadings.voltage(voltage)
        val newTemperature = BatteryReadings.temperature(temperature)
        val newCharge = BatteryReadings.chargeCounter(charge)
        if (newVoltage == voltage && newTemperature == temperature && newCharge == charge) return null
        state.hit(GROUP)
        return Intent(intent).apply {
            if (newVoltage != voltage) putExtra(BatteryManager.EXTRA_VOLTAGE, newVoltage)
            if (newTemperature != temperature) putExtra(BatteryManager.EXTRA_TEMPERATURE, newTemperature)
            if (newCharge != charge) putExtra(EXTRA_CHARGE_COUNTER, newCharge)
        }
    }

    /** After the original: the rounded value of the property named by the first argument. */
    private inner class PropertyHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val real = chain.proceed()
            return state.safely { rounded(chain, real) } ?: real
        }

        /** The rounded property, of the same box type as [real], or `null` to keep it. */
        private fun rounded(chain: Chain, real: Any?): Any? {
            if (!state.enabled) return null
            val id = chain.getArg(0) as? Int ?: return null
            val out: Any = when (real) {
                is Int -> BatteryReadings.intProperty(id, real)
                is Long -> BatteryReadings.property(id, real)
                else -> return null
            }
            if (out == real) return null
            state.hit(GROUP)
            return out
        }
    }

    companion object {
        val GROUP = TraitGroup("battery-readings")

        /** `BatteryManager.EXTRA_CHARGE_COUNTER`, hidden from the SDK. */
        private const val EXTRA_CHARGE_COUNTER = "charge_counter"
    }
}
