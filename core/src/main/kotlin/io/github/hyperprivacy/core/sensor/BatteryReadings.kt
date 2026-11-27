package io.github.hyperprivacy.core.sensor

/**
 * Battery readings under rule `sensor.data`: the fine-grained electrical values, readable without
 * a permission, are rounded. Battery current and voltage follow what the phone is doing (screen
 * content, radio use, location: e.g. PowerSpy, Michalevsky et al., USENIX Security 2015) and,
 * read precisely, identify a device (Olejnik et al., "The leaking battery", 2015); the charge
 * counter divided by the level reveals the battery's remaining capacity, a wear value as unique as
 * the cycle count. Level, scale, status, health, plug type and technology stay exact; so does every
 * "unsupported" value (`Int.MIN_VALUE` / `Long.MIN_VALUE`, and 0 or less where it means "unknown").
 *
 * Steps: current to [CURRENT_STEP_UA] (100 mA), charge counter to [CHARGE_STEP_UAH] (50 mAh, about
 * 1 % of a phone battery), energy counter to [ENERGY_STEP_NWH] (0.2 Wh, the same charge at a
 * nominal 3.85 V), voltage to [VOLTAGE_STEP_MV] (100 mV), temperature to whole degrees, the time to
 * full charge to [CHARGE_TIME_STEP_MS] (5 minutes, rounded up). A charging screen still shows a
 * plausible current; a thermometer still reads whole degrees.
 *
 * Units are those of the `BatteryManager` contract (current µA, charge µAh, energy nWh, voltage
 * mV): the health HAL reports in those units, and guessing a unit from the magnitude would leave
 * small currents fine-grained. The one exception is voltage, which some devices report in µV:
 * above [VOLTAGE_UV_ABOVE] no reading can be in mV (no battery is at 100 V), so the step follows.
 *
 * Rounding never overflows: a value whose rounded result would leave its type is rounded towards
 * zero instead.
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
    public const val CHARGE_TIME_STEP_MS: Long = 300_000L

    /** Above this a voltage is taken to be in µV. */
    public const val VOLTAGE_UV_ABOVE: Long = 100_000L

    private const val MILLI = 1_000L

    /** `BatteryManager#getLongProperty` of [id] as this app sees it. */
    public fun property(id: Int, real: Long): Long {
        if (real == Long.MIN_VALUE || real == Int.MIN_VALUE.toLong()) return real
        val step = step(id) ?: return real
        return round(real, step, Long.MIN_VALUE, Long.MAX_VALUE)
    }

    /** `BatteryManager#getIntProperty` of [id] as this app sees it. */
    public fun intProperty(id: Int, real: Int): Int {
        if (real == Int.MIN_VALUE) return real
        val step = step(id) ?: return real
        return round(real.toLong(), step, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /** `EXTRA_VOLTAGE`, mV (or µV); 0 (unknown) stays 0. */
    public fun voltage(real: Int): Int {
        if (real <= 0) return real
        val step = if (real > VOLTAGE_UV_ABOVE) VOLTAGE_STEP_MV * MILLI else VOLTAGE_STEP_MV
        return roundInt(real, step)
    }

    /** `EXTRA_TEMPERATURE`, tenths of a degree Celsius, to whole degrees. */
    public fun temperature(real: Int): Int = if (real == Int.MIN_VALUE) real else roundInt(real, TEMPERATURE_STEP)

    /** The hidden `charge_counter` extra, µAh; 0 or negative (unknown) stays as it is. */
    public fun chargeCounter(real: Int): Int = if (real <= 0) real else roundInt(real, CHARGE_STEP_UAH)

    /** `BatteryManager#computeChargeTimeRemaining`, ms, rounded up; 0 and -1 (unknown) stay. */
    public fun chargeTimeRemaining(real: Long): Long {
        if (real <= 0) return real
        val steps = real / CHARGE_TIME_STEP_MS + if (real % CHARGE_TIME_STEP_MS == 0L) 0 else 1
        return if (steps > Long.MAX_VALUE / CHARGE_TIME_STEP_MS) real else steps * CHARGE_TIME_STEP_MS
    }

    private fun step(id: Int): Long? = when (id) {
        PROPERTY_CURRENT_NOW, PROPERTY_CURRENT_AVERAGE -> CURRENT_STEP_UA
        PROPERTY_CHARGE_COUNTER -> CHARGE_STEP_UAH
        PROPERTY_ENERGY_COUNTER -> ENERGY_STEP_NWH
        else -> null
    }

    private fun roundInt(value: Int, step: Long): Int =
        round(value.toLong(), step, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    /**
     * [value] to the nearest multiple of [step] (halves away from zero) within [min]..[max]; towards
     * zero when the nearest multiple is outside.
     */
    internal fun round(value: Long, step: Long, min: Long, max: Long): Long {
        val quotient = value / step
        val remainder = value % step
        val truncated = quotient * step
        val nearest = when {
            2 * remainder >= step -> quotient + 1
            2 * remainder <= -step -> quotient - 1
            else -> return truncated
        }
        if (nearest > max / step || nearest < min / step) return truncated
        return nearest * step
    }
}
