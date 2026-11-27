package io.github.hyperprivacy.core.sensor

/**
 * How rule `sensor.data` treats one sensor type (`android.hardware.Sensor#getType`); see
 * [SensorStream] for what each kind does to an event. Sensors not listed (proximity, step counter,
 * heart rate, hinge angle, vendor types …) are [NONE] and never touched.
 *
 * [rateCapped] kinds are continuous streams fast enough for input and speech inference, delivered
 * at no more than [SensorStream.MAX_RATE_HZ]; pressure and light keep their rate (they are slow or
 * on-change; light drops only events that repeat the delivered level), and pose keeps it because
 * its delta fields refer to the previous event.
 */
public enum class SensorKind(public val rateCapped: Boolean) {
    NONE(rateCapped = false),

    /** `values[0..2]`, m/s². */
    ACCELEROMETER(rateCapped = true),

    /** `values[0..2]`, rad/s. */
    GYROSCOPE(rateCapped = true),

    /** `values[0..2]`, µT. */
    MAGNETIC(rateCapped = true),

    /** `values[0..2]` uncalibrated, `values[3..5]` the bias estimate. */
    ACCELEROMETER_UNCALIBRATED(rateCapped = true),
    GYROSCOPE_UNCALIBRATED(rateCapped = true),
    MAGNETIC_UNCALIBRATED(rateCapped = true),

    /** `values[0..2]`, `values[3..5]` 1/0 per supported axis. */
    ACCELEROMETER_LIMITED(rateCapped = true),
    GYROSCOPE_LIMITED(rateCapped = true),

    /** `values[0..2]` uncalibrated, `values[3..5]` bias, `values[6..8]` supported axes. */
    ACCELEROMETER_LIMITED_UNCALIBRATED(rateCapped = true),
    GYROSCOPE_LIMITED_UNCALIBRATED(rateCapped = true),

    /** Fused gravity, `values[0..2]`, m/s²: the accelerometer's gain and offset. */
    GRAVITY(rateCapped = true),

    /** Fused linear acceleration, `values[0..2]`, m/s²: the accelerometer's gain only. */
    LINEAR_ACCELERATION(rateCapped = true),

    /** Rotation vectors: unit quaternion `values[0..3]`; `values[4]` (heading accuracy) untouched. */
    ROTATION(rateCapped = true),

    /** Deprecated orientation: azimuth, pitch, roll in degrees, `values[0..2]`. */
    ORIENTATION(rateCapped = true),

    /** Heading: `values[0]` degrees in [0, 360); `values[1]` (accuracy) untouched. */
    HEADING(rateCapped = true),

    /**
     * Pose 6DOF: quaternions `values[0..3]` and `[7..10]`, translations `[4..6]` and `[11..13]`; the
     * sequence number `[14]` untouched. Not rate capped: the deltas and the sequence number refer
     * to the previous event, so dropping events would break them.
     */
    POSE(rateCapped = false),

    /** Head tracker: rotation and angular velocity `values[0..5]`; the discontinuity count `values[6]` untouched. */
    HEAD_TRACKER(rateCapped = true),

    /** `values[0]`, hPa. */
    PRESSURE(rateCapped = false),

    /** `values[0]`, lux. */
    LIGHT(rateCapped = false),
    ;

    public companion object {
        /** `Sensor.TYPE_*` values; part of the public SDK, so stable across releases. */
        public const val TYPE_ACCELEROMETER: Int = 1
        public const val TYPE_MAGNETIC_FIELD: Int = 2
        public const val TYPE_ORIENTATION: Int = 3
        public const val TYPE_GYROSCOPE: Int = 4
        public const val TYPE_LIGHT: Int = 5
        public const val TYPE_PRESSURE: Int = 6
        public const val TYPE_GRAVITY: Int = 9
        public const val TYPE_LINEAR_ACCELERATION: Int = 10
        public const val TYPE_ROTATION_VECTOR: Int = 11
        public const val TYPE_MAGNETIC_FIELD_UNCALIBRATED: Int = 14
        public const val TYPE_GAME_ROTATION_VECTOR: Int = 15
        public const val TYPE_GYROSCOPE_UNCALIBRATED: Int = 16
        public const val TYPE_GEOMAGNETIC_ROTATION_VECTOR: Int = 20
        public const val TYPE_POSE_6DOF: Int = 28
        public const val TYPE_ACCELEROMETER_UNCALIBRATED: Int = 35
        public const val TYPE_HEAD_TRACKER: Int = 37
        public const val TYPE_ACCELEROMETER_LIMITED_AXES: Int = 38
        public const val TYPE_GYROSCOPE_LIMITED_AXES: Int = 39
        public const val TYPE_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED: Int = 40
        public const val TYPE_GYROSCOPE_LIMITED_AXES_UNCALIBRATED: Int = 41
        public const val TYPE_HEADING: Int = 42

        /** The kind of a sensor of [type]; called once per sensor and event queue, not per event. */
        @Suppress("CyclomaticComplexMethod") // A flat lookup table; one branch per sensor type.
        public fun of(type: Int): SensorKind = when (type) {
            TYPE_ACCELEROMETER -> ACCELEROMETER
            TYPE_GYROSCOPE -> GYROSCOPE
            TYPE_MAGNETIC_FIELD -> MAGNETIC
            TYPE_ACCELEROMETER_UNCALIBRATED -> ACCELEROMETER_UNCALIBRATED
            TYPE_GYROSCOPE_UNCALIBRATED -> GYROSCOPE_UNCALIBRATED
            TYPE_MAGNETIC_FIELD_UNCALIBRATED -> MAGNETIC_UNCALIBRATED
            TYPE_ACCELEROMETER_LIMITED_AXES -> ACCELEROMETER_LIMITED
            TYPE_GYROSCOPE_LIMITED_AXES -> GYROSCOPE_LIMITED
            TYPE_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED -> ACCELEROMETER_LIMITED_UNCALIBRATED
            TYPE_GYROSCOPE_LIMITED_AXES_UNCALIBRATED -> GYROSCOPE_LIMITED_UNCALIBRATED
            TYPE_GRAVITY -> GRAVITY
            TYPE_LINEAR_ACCELERATION -> LINEAR_ACCELERATION
            TYPE_ROTATION_VECTOR, TYPE_GAME_ROTATION_VECTOR, TYPE_GEOMAGNETIC_ROTATION_VECTOR -> ROTATION
            TYPE_ORIENTATION -> ORIENTATION
            TYPE_HEADING -> HEADING
            TYPE_POSE_6DOF -> POSE
            TYPE_HEAD_TRACKER -> HEAD_TRACKER
            TYPE_PRESSURE -> PRESSURE
            TYPE_LIGHT -> LIGHT
            else -> NONE
        }
    }
}
