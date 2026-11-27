package io.github.hyperprivacy.core.sensor

import kotlin.math.abs

/**
 * Battery readings under rule `sensor.data`: the fine-grained electrical values, readable without
 * a permission, are rounded. Battery current and voltage follow what the phone is doing (screen
 * content, radio use, location: e.g. PowerSpy, Michalevsky et al., USENIX Security 2015) and,
 * read precisely, identify a device (Olejnik et al., "The leaking battery", 2015); the charge
 * counter divided by the level reveals the battery's remaining capacity, a wear value as unique as
 * the cycle count. Level, scale, status, health, plug type and technology stay exact; so does every
 * "unsupported" value (`Int.MIN_VALUE` / `Long.MIN_VALUE`, and 0 where it means "unknown").
 *
 * Steps: current to [CURRENT_STEP_UA] (100 mA), charge counter to [CHARGE_STEP_UAH] (50 mAh, about
 * 1 % of a phone battery), energy counter to [ENERGY_STEP_NWH] (0.2 Wh, the same charge at a
 * nominal 3.85 V), voltage to [VOLTAGE_STEP_MV] (100 mV), temperature to whole degrees. A charging
 * screen still shows a plausible current; a thermometer still reads whole degrees.
 *
 * Some devices report current in mA and voltage in µV instead of the documented µA and mV; the
 * step follows the magnitude ([CURRENT_MA_BELOW], [VOLTAGE_UV_ABOVE]) so both stay coarse but sane.
 */
public object BatteryReadings {
    /** `BatteryManager.BATTERY_PROPERTY_*` ids. */
    public const val PROPERTY_CHARGE_COUNTER: Int = 1
    public const val PROPERTY_CURRENT_NOW: Int = 2
    public const val PROPERTY_CURRENT_AVERAGE: Int = 3
    public const val PROPERTY_ENERGY_COUNTER: Int = 5

    public const val CURRENT_STEP_UA: Long = 100_000L
    public const val CHARGE_STEP_UAH: Long = 50_000L
    public const val ENERGY_STEP_NWH: Long = 200_000_000L
    public const val VOLTAGE_STEP_MV: Long = 100L
    public const val TEMPERATURE_STEP: Long = 10L

    /**
     * Below this magnitude a current is taken to be in mA and rounded to 100 (mA-reporting devices
     * never reach 20 A); a µA reading this small (deep idle, under 20 mA) keeps 0.1 mA steps.
     */
    public const val CURRENT_MA_BELOW: Long = 20_000L

    /** Above this a voltage is taken to be in µV (no battery is at 100 V). */
    public const val VOLTAGE_UV_ABOVE: Long = 100_000L

    private const val MILLI = 1_000L

    /** `BatteryManager#getIntProperty` / `#getLongProperty` of [id] as this app sees it. */
    public fun property(id: Int, real: Long): Long {
        if (real == Long.MIN_VALUE || real == Int.MIN_VALUE.toLong()) return real
        return when (id) {
            PROPERTY_CURRENT_NOW, PROPERTY_CURRENT_AVERAGE -> current(real)
            PROPERTY_CHARGE_COUNTER -> round(real, CHARGE_STEP_UAH)
            PROPERTY_ENERGY_COUNTER -> round(real, ENERGY_STEP_NWH)
            else -> real
        }
    }

    /** A current in µA (or mA, see the class comment). */
    public fun current(real: Long): Long =
        round(real, if (abs(real) < CURRENT_MA_BELOW) CURRENT_STEP_UA / MILLI else CURRENT_STEP_UA)

    /** `EXTRA_VOLTAGE`, mV (or µV); 0 (unknown) stays 0. */
    public fun voltage(real: Int): Int {
        if (real <= 0) return real
        val step = if (real > VOLTAGE_UV_ABOVE) VOLTAGE_STEP_MV * MILLI else VOLTAGE_STEP_MV
        return round(real.toLong(), step).toInt()
    }

    /** `EXTRA_TEMPERATURE`, tenths of a degree Celsius, to whole degrees. */
    public fun temperature(real: Int): Int =
        if (real == Int.MIN_VALUE) real else round(real.toLong(), TEMPERATURE_STEP).toInt()

    /** The hidden `charge_counter` extra, µAh; 0 or negative (unknown) stays as it is. */
    public fun chargeCounter(real: Int): Int = if (real <= 0) real else round(real.toLong(), CHARGE_STEP_UAH).toInt()

    /** [value] to the nearest multiple of [step], halves away from zero. */
    internal fun round(value: Long, step: Long): Long {
        val half = step / 2
        return if (value >= 0) (value + half) / step * step else -((-value + half) / step * step)
    }
}
