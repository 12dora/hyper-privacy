package io.github.hyperprivacy.core.sensor

import io.github.hyperprivacy.core.sensor.SensorBlur.Companion.ACCEL
import io.github.hyperprivacy.core.sensor.SensorBlur.Companion.GYRO
import io.github.hyperprivacy.core.sensor.SensorBlur.Companion.MAG
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sqrt

/**
 * The events of one sensor ([kind], [handle]) as one app's event queue receives them: rate cap,
 * uncalibrated bias removal, the per-app gain and offset of [SensorBlur], and quantisation, all in
 * place on the event's value array. One instance per (event queue, sensor); a queue delivers on one
 * thread, so the state needs no lock. [filter] is O(1), allocation-free and never throws on odd
 * values: a non-finite value stays as it is, an array shorter than the kind expects is left alone.
 *
 * Every decision depends only on the event itself and on the events this queue received before,
 * never on when the queue was registered: the dither is a hash of (app seed, handle, timestamp,
 * value index), and the rate cap keeps the first event of each slot of a fixed time grid. Several
 * listeners on one sensor therefore receive the same subset of events with the same values, and
 * combining them recovers neither a faster stream nor the undithered value.
 *
 * Steps (dithered: uniform noise of +-0.5 step before rounding, see [SensorBlur]), each a few
 * times the sensor's LSB and about its noise floor, so shake, tilt, step, compass, game and
 * AR-lite logic see the same signal:
 * - accelerometer, gravity, linear acceleration: [ACCEL_STEP] m/s² (about 1 mg; LSB at +-8 g /
 *   16 bit is 0.0024, at +-16 g 0.0048; noise at 50 Hz about 0.01).
 * - gyroscope: [GYRO_STEP] rad/s (0.11 degrees/s; LSB at +-2000 dps / 16 bit is 0.0012).
 * - magnetometer: [MAG_STEP] µT (common LSB 0.15 µT, noise 0.3..0.6 µT).
 * - rotation-vector and pose quaternions: [QUATERNION_STEP] (about 0.11 degrees), then scaled back
 *   to just under unit length, so rotation matrices, `SensorManager.getOrientation` (whose `asin`
 *   is not clamped) and slerp stay finite; pose translation [TRANSLATION_STEP] m; orientation and
 *   heading [DEGREE_STEP] degrees; head tracker [HEAD_TRACKER_STEP] rad and rad/s.
 *
 * Not dithered, because averaging dithered values would recover what they hide, and with
 * hysteresis so a reading near a step edge does not flicker and reveal where the edge is:
 * - pressure: [PRESSURE_STEP] hPa, the precision of aviation QNH reports. One storey is about
 *   0.4 hPa and barometric floor detection needs about 0.1 hPa, so the floor is no longer
 *   inferable (a step is about 8 m of height); weather trends stay visible, floor counting and
 *   barometric altimeters stop working.
 * - light: a 1-2-5 series from [LIGHT_LEVELS] (0, 10, 20, 50 … 100 000 lux). Screen-content and
 *   tab-history inference from the ambient light sensor relies on small changes (Olejnik et al.,
 *   2017); the W3C Ambient Light Sensor specification rounds to 50 lux for the same reason. Dark,
 *   indoor and daylight stay distinct for theme and reading-mode logic. Light is an on-change
 *   sensor, so an event that repeats the delivered level is dropped: its timing alone would show
 *   when the light changed within a level.
 *
 * Uncalibrated sensors deliver the calibrated value with a zero bias estimate: `uncal - bias` is
 * still the calibrated reading an app computes, while the device's own bias (the hard-iron offset
 * of the magnetometer in particular) is no longer exposed.
 *
 * Rate cap: continuous motion kinds keep only the first event in each [PERIOD_NS] slot of a fixed
 * grid, at most [MAX_RATE_HZ] events a second; rates are never raised. A 50 Hz stream
 * (`SENSOR_DELAY_GAME`, or a 52 Hz sensor data rate) passes whole unless two events come less than
 * a slot apart; faster streams are decimated. Android 12 caps listeners at 200 Hz without
 * HIGH_SAMPLING_RATE_SENSORS. Speech inference from motion sensors (Gyrophone, USENIX Security
 * 2014) needs high rates; tap and keystroke inference (TouchLogger, 2011; TapPrints, MobiSys 2012)
 * still works at this rate, but less well with coarser values.
 */
public class SensorStream(public val kind: SensorKind, public val handle: Int) {
    private val handleMix = handle.toLong() * HANDLE_MIX
    private var previous = UNSET
    private var last = Float.NaN

    /**
     * Rewrites [values] (the event at [timestamp], ns) for this app under [blur]; `false` when the
     * event is dropped and must not reach the listener.
     */
    public fun filter(values: FloatArray, timestamp: Long, blur: SensorBlur): Boolean {
        if (kind.rateCapped && !admit(timestamp)) return false
        return transform(values, timestamp, blur)
    }

    /**
     * Starts the rate cap after [timestamp], the last event another queue of this sensor received,
     * so a newly registered listener cannot pick up an extra event in a slot already delivered.
     */
    public fun resumeAfter(timestamp: Long) {
        if (previous == UNSET || timestamp > previous) previous = timestamp
    }

    /** The last timestamp this stream received, or [UNSET]. */
    public val lastTimestamp: Long get() = previous

    /** The first event of each grid slot; a duplicate or older event is dropped. */
    internal fun admit(timestamp: Long): Boolean {
        val before = previous
        if (before == UNSET || before - timestamp > RESET_NS) {
            // First event, or the clock went back by more than a reset interval: start over.
            previous = timestamp
            return true
        }
        if (timestamp <= before) return false
        previous = timestamp
        return Math.floorDiv(timestamp, PERIOD_NS) != Math.floorDiv(before, PERIOD_NS)
    }

    @Suppress("CyclomaticComplexMethod") // One branch per kind, a flat table switch.
    private fun transform(values: FloatArray, timestamp: Long, blur: SensorBlur): Boolean {
        val key = blur.seed xor handleMix xor (timestamp * TIME_MIX)
        when (kind) {
            SensorKind.NONE -> Unit
            SensorKind.ACCELEROMETER -> vector(values, key, blur, ACCEL, ACCEL_STEP, NO_FLAGS)
            SensorKind.GYROSCOPE -> vector(values, key, blur, GYRO, GYRO_STEP, NO_FLAGS)
            SensorKind.MAGNETIC -> vector(values, key, blur, MAG, MAG_STEP, NO_FLAGS)
            SensorKind.ACCELEROMETER_UNCALIBRATED -> folded(values, key, blur, ACCEL, ACCEL_STEP, NO_FLAGS)
            SensorKind.GYROSCOPE_UNCALIBRATED -> folded(values, key, blur, GYRO, GYRO_STEP, NO_FLAGS)
            SensorKind.MAGNETIC_UNCALIBRATED -> folded(values, key, blur, MAG, MAG_STEP, NO_FLAGS)
            SensorKind.ACCELEROMETER_LIMITED -> vector(values, key, blur, ACCEL, ACCEL_STEP, AXES)
            SensorKind.GYROSCOPE_LIMITED -> vector(values, key, blur, GYRO, GYRO_STEP, AXES)
            SensorKind.ACCELEROMETER_LIMITED_UNCALIBRATED -> folded(values, key, blur, ACCEL, ACCEL_STEP, FLAGS)
            SensorKind.GYROSCOPE_LIMITED_UNCALIBRATED -> folded(values, key, blur, GYRO, GYRO_STEP, FLAGS)
            SensorKind.GRAVITY -> vector(values, key, blur, ACCEL, ACCEL_STEP, NO_FLAGS)
            SensorKind.LINEAR_ACCELERATION -> vector(values, key, blur, LINEAR, ACCEL_STEP, NO_FLAGS)
            SensorKind.ROTATION -> quaternion(values, key, 0)
            SensorKind.ORIENTATION -> degrees(values, key, AXES)
            SensorKind.HEADING -> degrees(values, key, 1)
            SensorKind.POSE -> pose(values, key)
            SensorKind.HEAD_TRACKER -> dithered(values, key, 0, 2 * AXES, HEAD_TRACKER_STEP)
            SensorKind.PRESSURE -> if (values.isNotEmpty()) values[0] = pressure(values[0])
            SensorKind.LIGHT -> return values.isEmpty() || light(values)
        }
        return true
    }

    /**
     * Gain, offset and dithered quantisation of `values[0..2]` under [group] ([LINEAR]: the
     * accelerometer's gain, no offset); with [flagsAt] >= 0 only the axes whose supported-axis flag
     * `values[flagsAt + axis]` is set (limited-axes sensors).
     */
    private fun vector(values: FloatArray, key: Long, blur: SensorBlur, group: Int, step: Float, flagsAt: Int) {
        if (values.size < needed(flagsAt)) return
        for (axis in 0 until AXES) {
            val real = values[axis]
            if (supported(values, flagsAt, axis) && real.isFinite()) {
                val physical = if (group == LINEAR) ACCEL else group
                val offset = if (group == LINEAR) 0f else blur.offset(physical, axis)
                values[axis] = quantise(real * blur.gain(physical, axis) + offset, step, key, axis)
            }
        }
    }

    /** Folds the bias estimate `values[3..5]` into the reading and reports it as zero, then [vector]. */
    private fun folded(values: FloatArray, key: Long, blur: SensorBlur, group: Int, step: Float, flagsAt: Int) {
        if (values.size < maxOf(2 * AXES, needed(flagsAt))) return
        for (axis in 0 until AXES) {
            val bias = values[AXES + axis]
            if (supported(values, flagsAt, axis) && bias.isFinite()) values[axis] -= bias
            values[AXES + axis] = 0f
        }
        vector(values, key, blur, group, step, flagsAt)
    }

    /** Whole [PRESSURE_STEP]s; a new step only once the reading is [HYSTERESIS] steps away from the last. */
    internal fun pressure(real: Float): Float {
        if (!real.isFinite()) return real
        val before = last
        if (!before.isNaN() && abs(real - before) <= PRESSURE_STEP * HYSTERESIS) return before
        val out = round(real / PRESSURE_STEP) * PRESSURE_STEP
        last = out
        return out
    }

    /** Rewrites `values[0]` to its level ([level]); `false` when that repeats the level already delivered. */
    private fun light(values: FloatArray): Boolean {
        val real = values[0]
        if (!real.isFinite()) return true
        val before = last
        val out = level(real)
        values[0] = out
        return before.isNaN() || out != before
    }

    /**
     * The largest level of [LIGHT_LEVELS] at or below [real]; moving to the next level up needs
     * [LIGHT_MARGIN] times that level, moving one level down needs falling below the current level
     * divided by [LIGHT_MARGIN].
     */
    internal fun level(real: Float): Float {
        var index = LIGHT_LEVELS.size - 1
        while (index > 0 && real < LIGHT_LEVELS[index]) index--
        val before = last
        val level = LIGHT_LEVELS[index]
        val justAbove = index > 0 && LIGHT_LEVELS[index - 1] == before && real < level * LIGHT_MARGIN
        val justBelow = index + 1 < LIGHT_LEVELS.size && LIGHT_LEVELS[index + 1] == before &&
            real >= before / LIGHT_MARGIN
        val out = if (justAbove || justBelow) before else level
        last = out
        return out
    }

    public companion object {
        /** Timestamp of a stream that has received nothing yet. */
        public const val UNSET: Long = Long.MIN_VALUE

        public const val PERIOD_NS: Long = 16_000_000L
        public const val MAX_RATE_HZ: Double = 1e9 / PERIOD_NS

        /** A clock jump back by more than this restarts the rate cap instead of dropping events. */
        public const val RESET_NS: Long = 1_000_000_000L

        public const val ACCEL_STEP: Float = 0.01f
        public const val GYRO_STEP: Float = 0.002f
        public const val MAG_STEP: Float = 0.2f
        public const val QUATERNION_STEP: Float = 0.001f
        public const val TRANSLATION_STEP: Float = 0.001f
        public const val DEGREE_STEP: Float = 0.1f
        public const val HEAD_TRACKER_STEP: Float = 0.002f
        public const val PRESSURE_STEP: Float = 1f
        public const val HYSTERESIS: Float = 0.75f
        public const val LIGHT_MARGIN: Float = 1.1f

        /** Quaternions are scaled to this length, just below 1 so float rounding never exceeds 1. */
        public const val UNIT_BELOW: Double = 1.0 - 1.0 / (1 shl 20)

        @Suppress("MagicNumber") // The level table itself.
        public val LIGHT_LEVELS: FloatArray = floatArrayOf(
            0f, 10f, 20f, 50f, 100f, 200f, 500f, 1_000f, 2_000f, 5_000f, 10_000f, 20_000f, 50_000f, 100_000f,
        )

        /** [value] rounded to [step] after adding dither uniform in [-0.5, 0.5) step, a hash of [key] and [index]. */
        internal fun quantise(value: Float, step: Float, key: Long, index: Int): Float {
            // SplitMix64 finaliser; the top 53 bits as a double in [0, 1).
            var z = key + (index + 1) * GOLDEN
            z = (z xor (z ushr MIX_SHIFT_A)) * MIX_A
            z = (z xor (z ushr MIX_SHIFT_B)) * MIX_B
            z = z xor (z ushr MIX_SHIFT_C)
            val unit = (z ushr DOUBLE_SHIFT).toDouble() / DOUBLE_RANGE
            return (floor(value.toDouble() / step + unit) * step).toFloat()
        }

        private const val AXES = 3
        private const val QUATERNION = 4
        private const val POSE_VALUES = 2 * QUATERNION + 2 * AXES
        private const val NO_FLAGS = -1
        private const val FLAGS = 2 * AXES
        private const val LINEAR = -1
        private const val FULL_TURN = 360f
        private const val HANDLE_MIX = -0x61c8864680b583ebL
        private const val TIME_MIX = -0x4b47d5b1d8a1e2ddL
        private const val GOLDEN = -0x61c8864680b583ebL
        private const val MIX_A = -0x40a7b892e31b1a47L
        private const val MIX_B = -0x6b2fb644ecceee15L
        private const val MIX_SHIFT_A = 30
        private const val MIX_SHIFT_B = 27
        private const val MIX_SHIFT_C = 31
        private const val DOUBLE_SHIFT = 11
        private const val DOUBLE_RANGE = 9.007199254740992e15

        private fun pose(values: FloatArray, key: Long) {
            if (values.size < POSE_VALUES) return
            quaternion(values, key, 0)
            dithered(values, key, QUATERNION, AXES, TRANSLATION_STEP)
            quaternion(values, key, QUATERNION + AXES)
            dithered(values, key, 2 * QUATERNION + AXES, AXES, TRANSLATION_STEP)
        }

        /**
         * `values[from until from + 4]` quantised, then scaled to [UNIT_BELOW] length (signs, and so
         * the sign convention of w, are kept); a non-finite or zero quaternion is left as it is.
         */
        private fun quaternion(values: FloatArray, key: Long, from: Int) {
            val end = from + QUATERNION
            if (values.size < end) return
            var norm = 0.0
            for (i in from until end) {
                val real = values[i]
                if (!real.isFinite()) return
                norm += real.toDouble() * real
            }
            if (norm == 0.0) return
            var quantised = 0.0
            for (i in from until end) {
                val q = quantise(values[i], QUATERNION_STEP, key, i).toDouble()
                values[i] = q.toFloat()
                quantised += q * q
            }
            if (quantised == 0.0) return
            val scale = UNIT_BELOW / sqrt(quantised)
            for (i in from until end) values[i] = (values[i] * scale).toFloat()
        }

        /** Degrees, quantised; an azimuth or heading that rounds up to 360 wraps to 0. */
        private fun degrees(values: FloatArray, key: Long, count: Int) {
            if (dithered(values, key, 0, count, DEGREE_STEP) && values[0] >= FULL_TURN) values[0] -= FULL_TURN
        }

        /** Dithered quantisation of `values[from until from + count]`; `false` if the array is too short. */
        private fun dithered(values: FloatArray, key: Long, from: Int, count: Int, step: Float): Boolean {
            val end = from + count
            if (values.size < end) return false
            for (i in from until end) {
                val real = values[i]
                if (real.isFinite()) values[i] = quantise(real, step, key, i)
            }
            return true
        }

        private fun needed(flagsAt: Int): Int = if (flagsAt >= 0) flagsAt + AXES else AXES

        private fun supported(values: FloatArray, flagsAt: Int, axis: Int): Boolean =
            flagsAt < 0 || values[flagsAt + axis] != 0f
    }
}
