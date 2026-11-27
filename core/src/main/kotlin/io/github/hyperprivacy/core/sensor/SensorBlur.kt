package io.github.hyperprivacy.core.sensor

import io.github.hyperprivacy.core.id.DerivedIds

/**
 * The per-app constants of rule `sensor.data`: a small gain and offset per axis of the
 * accelerometer, gyroscope and magnetometer, and the seed of the dither noise. Derived once per
 * app from the module secret ([derive]); the per-event work is in [SensorStream].
 *
 * Why an affine change plus dithered quantisation. Motion sensors are factory-calibrated, and the
 * calibration left in the readings identifies a device: SensorID (Zhang, Beresford, Sheret, IEEE
 * S&P 2019) recovers the per-device gain from the lattice of ADC steps (readings are integer
 * multiples of gain x LSB) and proposes, as the fix Apple shipped in iOS 12.2, adding uniform
 * noise of +-0.5 of the nominal step before rounding; Das, Borisov, Caesar ("Tracking Mobile Web
 * Users Through Motion Sensors", NDSS 2016) fingerprint the offset and gain errors and show that
 * adding random calibration errors defeats it. Here every reading is
 * `quantise(gain * x + offset + dither)` with dither uniform in +-0.5 step and the step a few LSB
 * wide ([SensorStream] lists the steps), so the lattice is the same fixed grid on every device, and
 * the per-app gain and offset make the residual calibration differ between apps instead of linking
 * them. Gain and offset are stable per app, so a still phone reads stable values.
 *
 * Magnitudes. Averaging removes the dither, so what an app can still measure is
 * `gain * real calibration + offset`; for two apps on one phone to look no more alike than two
 * phones, the per-app change must be about as large as the residual calibration spread between
 * calibrated phones, while staying inside what apps already tolerate from real sensors:
 * - gain within +-[MAX_GAIN_DELTA] (1 %), the sensitivity tolerance of calibrated MEMS parts; a
 *   360 degree turn reads 356.4..363.6 degrees, |g| reads 9.71..9.91 m/s².
 * - accelerometer offset within +-[ACCEL_OFFSET] m/s² per axis (about 10 mg), the order of the
 *   residual zero-g offset of calibrated phones; a tilt error of at most about 0.6 degrees.
 * - gyroscope offset within +-[GYRO_OFFSET] rad/s (0.11 degrees/s), the order of the residual
 *   zero-rate offset; an app integrating raw rates drifts by up to about 7 degrees a minute, as
 *   on many real phones (fused rotation vectors, which apps use for orientation, are not offset).
 * - magnetometer offset within +-[MAG_OFFSET] µT, the order of residual hard-iron error after
 *   calibration; a compass error of at most about 3 degrees at a weak (20 µT) horizontal field.
 *
 * Gravity gets the accelerometer's gain and offset and linear acceleration its gain, so
 * `accelerometer = gravity + linear acceleration` still holds. Without the module secret
 * ([NEUTRAL]) gain and offset are identity and only the secret-free parts apply.
 */
public class SensorBlur private constructor(
    private val gains: FloatArray,
    private val offsets: FloatArray,
    /** Seed of the dither noise; mixed with the sensor handle, the event timestamp and the value index. */
    public val seed: Long,
) {
    /** The gain of [axis] (0..2) of physical [group] ([ACCEL], [GYRO], [MAG]). */
    public fun gain(group: Int, axis: Int): Float = gains[group * AXES + axis]

    /** The offset of [axis] (0..2) of physical [group], in the sensor's unit. */
    public fun offset(group: Int, axis: Int): Float = offsets[group * AXES + axis]

    public companion object {
        public const val ACCEL: Int = 0
        public const val GYRO: Int = 1
        public const val MAG: Int = 2

        public const val MAX_GAIN_DELTA: Float = 0.01f
        public const val ACCEL_OFFSET: Float = 0.1f
        public const val GYRO_OFFSET: Float = 0.002f
        public const val MAG_OFFSET: Float = 1f

        private const val AXES = 3
        private const val GROUPS = 3
        private const val VALUES = AXES * GROUPS
        private const val LABEL = "hyperprivacy/sensor-data/v1"
        private const val BYTES_PER_VALUE = 2
        private const val SEED_BYTES = 8
        private const val DERIVED_BYTES = VALUES * BYTES_PER_VALUE * 2 + SEED_BYTES
        private const val BYTE_MASK = 0xFF
        private const val BITS_PER_BYTE = 8
        private const val SHORT_MAX = 65_535f
        private val OFFSETS = floatArrayOf(ACCEL_OFFSET, GYRO_OFFSET, MAG_OFFSET)
        private const val NEUTRAL_SEED = 0x5DEECE66DL

        /** Identity gain and offset, for use while the module secret is missing. */
        public val NEUTRAL: SensorBlur = SensorBlur(FloatArray(VALUES) { 1f }, FloatArray(VALUES), NEUTRAL_SEED)

        /**
         * The constants of [packageName] under the module [secret]: the same for one app on one
         * device, different for another app, not derivable without the secret.
         */
        public fun derive(packageName: String, secret: ByteArray): SensorBlur {
            val label = LABEL.toByteArray(Charsets.UTF_8).copyOf(DERIVED_BYTES)
            val bytes = DerivedIds.derive(label, packageName, secret)
            val gains = FloatArray(VALUES) { 1f + signedUnit(bytes, it * BYTES_PER_VALUE) * MAX_GAIN_DELTA }
            val offsets = FloatArray(VALUES) {
                signedUnit(bytes, (VALUES + it) * BYTES_PER_VALUE) * OFFSETS[it / AXES]
            }
            val seed = (0 until SEED_BYTES).fold(0L) { acc, i ->
                (acc shl BITS_PER_BYTE) or (bytes[VALUES * BYTES_PER_VALUE * 2 + i].toLong() and BYTE_MASK.toLong())
            }
            return SensorBlur(gains, offsets, seed)
        }

        /** Two bytes at [index] as a value in [-1, 1]. */
        private fun signedUnit(bytes: ByteArray, index: Int): Float {
            val high = bytes[index].toInt() and BYTE_MASK
            val unit = (high shl BITS_PER_BYTE) or (bytes[index + 1].toInt() and BYTE_MASK)
            return unit / SHORT_MAX * 2f - 1f
        }
    }
}
