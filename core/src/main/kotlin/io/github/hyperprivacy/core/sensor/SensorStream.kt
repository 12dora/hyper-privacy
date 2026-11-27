package io.github.hyperprivacy.core.sensor

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * The events of one sensor ([kind]) as one app's event queue receives them: rate cap, uncalibrated
 * bias removal, the per-app gain and offset of [SensorBlur], and quantisation, all in place on the
 * event's value array. One instance per (event queue, sensor); a queue delivers on one thread, so
 * the state needs no lock. [filter] is O(1), allocation-free and never throws on odd values: a
 * non-finite value stays as it is, an array shorter than the kind expects is left alone.
 *
 * Steps (dithered: uniform noise of +-0.5 step before rounding, see [SensorBlur]), each a few
 * times the sensor's LSB and about its noise floor, so shake, tilt, step, compass, game and
 * AR-lite logic see the same signal:
 * - accelerometer, gravity, linear acceleration: [ACCEL_STEP] m/s² (about 1 mg; LSB at +-8 g /
 *   16 bit is 0.0024, at +-16 g 0.0048; noise at 50 Hz about 0.01).
 * - gyroscope: [GYRO_STEP] rad/s (0.11 degrees/s; LSB at +-2000 dps / 16 bit is 0.0012).
 * - magnetometer: [MAG_STEP] µT (common LSB 0.15 µT, noise 0.3..0.6 µT).
 * - rotation-vector and pose quaternions: [QUATERNION_STEP] (about 0.11 degrees); pose
 *   translation [TRANSLATION_STEP] m; orientation and heading [DEGREE_STEP] degrees; head
 *   tracker [HEAD_TRACKER_STEP] rad and rad/s.
 *
 * Not dithered, because averaging dithered values would recover what they hide, and with
 * hysteresis so a reading near a step edge does not flicker and reveal where the edge is:
 * - pressure: [PRESSURE_STEP] hPa, the precision of aviation QNH reports. One storey is about
 *   0.4 hPa and barometric floor detection needs about 0.1 hPa, so the floor is no longer
 *   inferable (a step is about 8 m of height); weather trends stay visible.
 * - light: a 1-2-5 series from [LIGHT_LEVELS] (0, 10, 20, 50 … 100 000 lux). Screen-content and
 *   tab-history inference from the ambient light sensor relies on small changes (Olejnik et al.,
 *   2017); the W3C Ambient Light Sensor specification rounds to 50 lux for the same reason. Dark,
 *   indoor and daylight stay distinct for theme and reading-mode logic.
 *
 * Uncalibrated sensors deliver the calibrated value with a zero bias estimate: `uncal - bias` is
 * still the calibrated reading an app computes, while the device's own bias (the hard-iron offset
 * of the magnetometer in particular) is no longer exposed.
 *
 * Rate cap: continuous motion kinds are delivered at no more than [MAX_RATE_HZ] (one event per
 * [PERIOD_NS] on average; an event may come less than [SLACK_NS] early, so a jittery 50 Hz stream
 * passes whole and a faster one is decimated to 50 Hz); rates are never raised. That is the rate of
 * `SENSOR_DELAY_GAME`, and the rate Android 12 allows on direct channels without
 * HIGH_SAMPLING_RATE_SENSORS (it caps listeners at 200 Hz); speech and keystroke inference from
 * motion sensors (e.g. Gyrophone, USENIX Security 2014; TapPrints, MobiSys 2012) work better the
 * faster the stream.
 */
public class SensorStream(public val kind: SensorKind, seed: Long) {
    private var next = 0L
    private var noise = if (seed == 0L) NOISE_FALLBACK else seed
    private var last = Float.NaN

    /**
     * Rewrites [values] (the event at [timestamp], ns) for this app under [blur]; `false` when the
     * event is dropped by the rate cap and must not reach the listener.
     */
    public fun filter(values: FloatArray, timestamp: Long, blur: SensorBlur): Boolean {
        if (kind.rateCapped && !admit(timestamp)) return false
        transform(values, blur)
        return true
    }

    /** Timestamp-based decimation; see the class comment. */
    internal fun admit(timestamp: Long): Boolean {
        val diff = timestamp - next
        // First event, a gap of a period or more, or time went backwards: start over.
        when {
            diff >= PERIOD_NS || diff < -(PERIOD_NS + SLACK_NS) -> next = timestamp + PERIOD_NS
            diff > -SLACK_NS -> next += PERIOD_NS
            else -> return false
        }
        return true
    }

    @Suppress("CyclomaticComplexMethod") // One branch per kind, a flat table switch.
    private fun transform(values: FloatArray, blur: SensorBlur) {
        when (kind) {
            SensorKind.NONE -> Unit
            SensorKind.ACCELEROMETER -> vector(values, blur, SensorBlur.ACCEL, ACCEL_STEP, NO_FLAGS)
            SensorKind.GYROSCOPE -> vector(values, blur, SensorBlur.GYRO, GYRO_STEP, NO_FLAGS)
            SensorKind.MAGNETIC -> vector(values, blur, SensorBlur.MAG, MAG_STEP, NO_FLAGS)
            SensorKind.ACCELEROMETER_UNCALIBRATED -> folded(values, blur, SensorBlur.ACCEL, ACCEL_STEP, NO_FLAGS)
            SensorKind.GYROSCOPE_UNCALIBRATED -> folded(values, blur, SensorBlur.GYRO, GYRO_STEP, NO_FLAGS)
            SensorKind.MAGNETIC_UNCALIBRATED -> folded(values, blur, SensorBlur.MAG, MAG_STEP, NO_FLAGS)
            SensorKind.ACCELEROMETER_LIMITED -> vector(values, blur, SensorBlur.ACCEL, ACCEL_STEP, AXES)
            SensorKind.GYROSCOPE_LIMITED -> vector(values, blur, SensorBlur.GYRO, GYRO_STEP, AXES)
            SensorKind.ACCELEROMETER_LIMITED_UNCALIBRATED -> folded(values, blur, SensorBlur.ACCEL, ACCEL_STEP, FLAGS)
            SensorKind.GYROSCOPE_LIMITED_UNCALIBRATED -> folded(values, blur, SensorBlur.GYRO, GYRO_STEP, FLAGS)
            SensorKind.FUSED_ACCELERATION -> dithered(values, 0, AXES, ACCEL_STEP)
            SensorKind.ROTATION -> dithered(values, 0, QUATERNION, QUATERNION_STEP)
            SensorKind.ORIENTATION -> degrees(values, AXES)
            SensorKind.HEADING -> degrees(values, 1)
            SensorKind.POSE -> pose(values)
            SensorKind.HEAD_TRACKER -> dithered(values, 0, 2 * AXES, HEAD_TRACKER_STEP)
            SensorKind.PRESSURE -> if (values.isNotEmpty()) values[0] = pressure(values[0])
            SensorKind.LIGHT -> if (values.isNotEmpty()) values[0] = light(values[0])
        }
    }

    /**
     * Gain, offset and dithered quantisation of `values[0..2]`; with [flagsAt] >= 0 only the axes
     * whose supported-axis flag `values[flagsAt + axis]` is set (limited-axes sensors).
     */
    private fun vector(values: FloatArray, blur: SensorBlur, group: Int, step: Float, flagsAt: Int) {
        if (values.size < AXES || (flagsAt >= 0 && values.size < flagsAt + AXES)) return
        for (axis in 0 until AXES) {
            val real = values[axis]
            val supported = flagsAt < 0 || values[flagsAt + axis] != 0f
            if (supported && real.isFinite()) {
                values[axis] = quantise(real * blur.gain(group, axis) + blur.offset(group, axis), step)
            }
        }
    }

    /** Folds the bias estimate `values[3..5]` into the reading and reports it as zero, then [vector]. */
    private fun folded(values: FloatArray, blur: SensorBlur, group: Int, step: Float, flagsAt: Int) {
        if (values.size < 2 * AXES) return
        for (axis in 0 until AXES) {
            val bias = values[AXES + axis]
            if (bias.isFinite()) values[axis] -= bias
            values[AXES + axis] = 0f
        }
        vector(values, blur, group, step, flagsAt)
    }

    private fun pose(values: FloatArray) {
        if (values.size < POSE_VALUES) return
        dithered(values, 0, QUATERNION, QUATERNION_STEP)
        dithered(values, QUATERNION, AXES, TRANSLATION_STEP)
        dithered(values, QUATERNION + AXES, QUATERNION, QUATERNION_STEP)
        dithered(values, 2 * QUATERNION + AXES, AXES, TRANSLATION_STEP)
    }

    /** Degrees, quantised; an azimuth or heading that rounds up to 360 wraps to 0. */
    private fun degrees(values: FloatArray, count: Int) {
        if (dithered(values, 0, count, DEGREE_STEP) && values[0] >= FULL_TURN) values[0] -= FULL_TURN
    }

    /** Dithered quantisation of `values[from until from + count]`; `false` if the array is too short. */
    private fun dithered(values: FloatArray, from: Int, count: Int, step: Float): Boolean {
        val end = from + count
        if (values.size < end) return false
        for (i in from until end) {
            val real = values[i]
            if (real.isFinite()) values[i] = quantise(real, step)
        }
        return true
    }

    /** [value] rounded to [step] after adding dither uniform in [-0.5, 0.5) step (xorshift64, top 24 bits). */
    private fun quantise(value: Float, step: Float): Float {
        var x = noise
        x = x xor (x shl XORSHIFT_A)
        x = x xor (x ushr XORSHIFT_B)
        x = x xor (x shl XORSHIFT_C)
        noise = x
        val dither = (x ushr NOISE_SHIFT).toFloat() / NOISE_RANGE - HALF
        return floor(value / step + dither + HALF) * step
    }

    /** Whole [PRESSURE_STEP]s; a new step only once the reading is [HYSTERESIS] steps away from the last. */
    internal fun pressure(real: Float): Float {
        if (!real.isFinite()) return real
        val previous = last
        if (!previous.isNaN() && abs(real - previous) <= PRESSURE_STEP * HYSTERESIS) return previous
        val out = round(real / PRESSURE_STEP) * PRESSURE_STEP
        last = out
        return out
    }

    /**
     * The largest level of [LIGHT_LEVELS] at or below [real]; moving to the next level up needs
     * [LIGHT_MARGIN] times that level, moving one level down needs falling below the current level
     * divided by [LIGHT_MARGIN].
     */
    internal fun light(real: Float): Float {
        if (!real.isFinite()) return real
        var index = LIGHT_LEVELS.size - 1
        while (index > 0 && real < LIGHT_LEVELS[index]) index--
        val previous = last
        val level = LIGHT_LEVELS[index]
        val justAbove = index > 0 && LIGHT_LEVELS[index - 1] == previous && real < level * LIGHT_MARGIN
        val justBelow = index + 1 < LIGHT_LEVELS.size && LIGHT_LEVELS[index + 1] == previous &&
            real >= previous / LIGHT_MARGIN
        val out = if (justAbove || justBelow) previous else level
        last = out
        return out
    }

    public companion object {
        public const val MAX_RATE_HZ: Int = 50
        public const val PERIOD_NS: Long = 1_000_000_000L / MAX_RATE_HZ
        public const val SLACK_NS: Long = PERIOD_NS / 4

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

        @Suppress("MagicNumber") // The level table itself.
        public val LIGHT_LEVELS: FloatArray = floatArrayOf(
            0f, 10f, 20f, 50f, 100f, 200f, 500f, 1_000f, 2_000f, 5_000f, 10_000f, 20_000f, 50_000f, 100_000f,
        )

        private const val AXES = 3
        private const val QUATERNION = 4
        private const val POSE_VALUES = 2 * QUATERNION + 2 * AXES
        private const val NO_FLAGS = -1
        private const val FLAGS = 2 * AXES
        private const val FULL_TURN = 360f
        private const val HALF = 0.5f
        private const val XORSHIFT_A = 13
        private const val XORSHIFT_B = 7
        private const val XORSHIFT_C = 17
        private const val NOISE_SHIFT = 40
        private const val NOISE_RANGE = 16_777_216f
        private const val NOISE_FALLBACK = 0x2545F4914F6CDD1DL
    }
}
