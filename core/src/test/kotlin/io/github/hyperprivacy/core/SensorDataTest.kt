package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.config.ModuleSecret
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.IdentifierSource
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.sensor.BatteryReadings
import io.github.hyperprivacy.core.sensor.SensorBlur
import io.github.hyperprivacy.core.sensor.SensorKind
import io.github.hyperprivacy.core.sensor.SensorStream
import io.github.hyperprivacy.core.tracker.TrackerRules
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.round
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensorDataTest {
    private val secret = ByteArray(ModuleSecret.BYTES) { (it * 7 + 3).toByte() }
    private val blur = SensorBlur.derive("com.example.a", secret)
    private val rule: Rule = assertNotNull(Catalog.protectedAppRules.singleOrNull { it.id == "sensor.data" })

    @Test
    fun `rule is a protected-app rule, off by default, switched per app`() {
        assertTrue(rule.isProtectedAppRule && rule.preventive)
        assertFalse(rule.defaultOn)
        assertEquals(RulePayload.Identifiers(IdentifierSource.SENSOR_DATA), rule.payload)
        assertNull(Catalog.byId(rule.id))
        assertEquals(mapOf(rule.id to false), ConfigCodec.decode(emptyMap<String, Any>(), listOf(rule)))
        val raw = mapOf<String, Any>(TrackerRules.appRuleKey("com.example.a", rule.id) to true)
        assertTrue(TrackerRules.isAppRuleOn(raw, "com.example.a", rule))
        assertFalse(TrackerRules.isAppRuleOn(raw, "com.example.b", rule))
    }

    @Test
    fun `per-app constants are stable, bounded and differ between apps`() {
        val again = SensorBlur.derive("com.example.a", secret.copyOf())
        val other = SensorBlur.derive("com.example.b", secret)
        val limits = floatArrayOf(SensorBlur.ACCEL_OFFSET, SensorBlur.GYRO_OFFSET, SensorBlur.MAG_OFFSET)
        for (group in 0..2) {
            for (axis in 0..2) {
                assertEquals(blur.gain(group, axis), again.gain(group, axis))
                assertEquals(blur.offset(group, axis), again.offset(group, axis))
                assertTrue(abs(blur.gain(group, axis) - 1f) <= SensorBlur.MAX_GAIN_DELTA + EPS)
                assertTrue(abs(blur.offset(group, axis)) <= limits[group] + EPS)
            }
        }
        assertEquals(blur.seed, again.seed)
        assertNotEquals(blur.offset(SensorBlur.ACCEL, 0), other.offset(SensorBlur.ACCEL, 0))
        assertNotEquals(blur.seed, other.seed)
    }

    @Test
    fun `sensor types map to their kinds`() {
        val expected = mapOf(
            1 to SensorKind.ACCELEROMETER, 2 to SensorKind.MAGNETIC, 3 to SensorKind.ORIENTATION,
            4 to SensorKind.GYROSCOPE, 5 to SensorKind.LIGHT, 6 to SensorKind.PRESSURE, 9 to SensorKind.GRAVITY,
            10 to SensorKind.LINEAR_ACCELERATION, 11 to SensorKind.ROTATION, 14 to SensorKind.MAGNETIC_UNCALIBRATED,
            15 to SensorKind.ROTATION, 16 to SensorKind.GYROSCOPE_UNCALIBRATED, 20 to SensorKind.ROTATION,
            28 to SensorKind.POSE, 35 to SensorKind.ACCELEROMETER_UNCALIBRATED, 37 to SensorKind.HEAD_TRACKER,
            38 to SensorKind.ACCELEROMETER_LIMITED, 39 to SensorKind.GYROSCOPE_LIMITED,
            40 to SensorKind.ACCELEROMETER_LIMITED_UNCALIBRATED, 41 to SensorKind.GYROSCOPE_LIMITED_UNCALIBRATED,
            42 to SensorKind.HEADING,
        )
        expected.forEach { (type, kind) -> assertEquals(kind, SensorKind.of(type), "type $type") }
        // Proximity (8), step counter (19), hinge angle (36) and vendor types are untouched.
        listOf(8, 19, 36, 65_536).forEach { assertEquals(SensorKind.NONE, SensorKind.of(it)) }
        assertFalse(SensorKind.PRESSURE.rateCapped || SensorKind.LIGHT.rateCapped)
        assertTrue(SensorKind.GYROSCOPE.rateCapped)
    }

    @Test
    fun `motion values land on the step grid, close to the per-app affine value`() {
        val stream = SensorStream(SensorKind.ACCELEROMETER, 42)
        val values = floatArrayOf(0.123f, -9.806f, 0.5f)
        val expected = FloatArray(3) {
            values[it] * blur.gain(SensorBlur.ACCEL, it) + blur.offset(SensorBlur.ACCEL, it)
        }
        assertTrue(stream.filter(values, T0, blur))
        for (axis in 0..2) {
            assertTrue(abs(values[axis] - expected[axis]) <= SensorStream.ACCEL_STEP + EPS, "axis $axis")
            val steps = values[axis] / SensorStream.ACCEL_STEP
            assertTrue(abs(steps - round(steps)) < GRID_EPS, "on grid: ${values[axis]}")
        }
    }

    @Test
    fun `dither is zero-mean, hides the input lattice and is the same for every listener`() {
        val stream = SensorStream(SensorKind.HEAD_TRACKER, 7)
        val twin = SensorStream(SensorKind.HEAD_TRACKER, 7)
        // A value a third of a step above a grid point averages to that value, not to the grid point.
        val real = 1.0f + SensorStream.HEAD_TRACKER_STEP / 3
        var sum = 0.0
        val seen = HashSet<Float>()
        repeat(N) { i ->
            val values = floatArrayOf(real, 0f, 0f, 0f, 0f, 0f)
            val copy = values.copyOf()
            stream.filter(values, T0 + i * SensorStream.PERIOD_NS, blur)
            twin.filter(copy, T0 + i * SensorStream.PERIOD_NS, blur)
            assertEquals(values.toList(), copy.toList(), "same event, same values")
            sum += values[0]
            seen += values[0]
        }
        assertEquals(real.toDouble(), sum / N, SensorStream.HEAD_TRACKER_STEP / 20.0)
        assertEquals(2, seen.size, "only the two neighbouring grid points")
    }

    @Test
    fun `quantisation never pushes a value past a grid point it sits on`() {
        repeat(N) { i ->
            val key = i * 0x9E3779B97F4A7C15uL.toLong()
            assertTrue(SensorStream.quantise(1f, SensorStream.QUATERNION_STEP, key, 0) <= 1f)
            assertTrue(SensorStream.quantise(90f, SensorStream.DEGREE_STEP, key, 1) <= 90f)
            assertTrue(SensorStream.quantise(-90f, SensorStream.DEGREE_STEP, key, 2) >= -90.0001f)
        }
    }

    @Test
    fun `rotation vectors stay unit length so orientation math stays finite`() {
        val stream = SensorStream(SensorKind.ROTATION, 3)
        val inputs = listOf(
            floatArrayOf(0.70710677f, 0f, 0f, 0.70710677f, 0.1f),
            floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.1f),
            floatArrayOf(0f, 0f, 0.99999994f, 0.0003f, 0.1f),
            floatArrayOf(0.70710677f, 0f, 0.70710677f, 0f, 0.1f),
        )
        repeat(N) { i ->
            val values = inputs[i % inputs.size].copyOf()
            stream.filter(values, T0 + i * SensorStream.PERIOD_NS, blur)
            val norm = values.take(4).sumOf { it.toDouble() * it }
            assertTrue(norm <= 1.0, "norm $norm")
            assertEquals(0.1f, values[4], "heading accuracy untouched")
            rotationMatrix(values).forEach { assertTrue(it in -1f..1f && !asin(it).isNaN(), "R $it") }
        }
    }

    @Test
    fun `pose keeps every event, its sequence number and unit quaternions`() {
        assertEquals(200, delivered(SensorKind.POSE, 200), "deltas refer to the previous event")
        val values = floatArrayOf(
            0.5f, 0.5f, 0.5f, 0.5f, 1.2345f, -0.5f, 2f, 0f, 0f, 0.0004f, 0.99999f, 0.0011f, 0f, 0f, 42f,
        )
        SensorStream(SensorKind.POSE, 1).filter(values, T0, blur)
        assertTrue(values.slice(0..3).sumOf { it.toDouble() * it } <= 1.0)
        assertTrue(values.slice(7..10).sumOf { it.toDouble() * it } <= 1.0)
        assertEquals(42f, values[14])
        assertEquals(1.234f, values[4], SensorStream.TRANSLATION_STEP)
    }

    @Test
    fun `orientation and heading wrap at 360 degrees`() {
        repeat(N / 10) { i ->
            val orientation = floatArrayOf(359.99f, 89.99f, -179.99f)
            SensorStream(SensorKind.ORIENTATION, 1).filter(orientation, T0 + i, blur)
            assertTrue(orientation[0] >= 0f && orientation[0] < 360f, "azimuth ${orientation[0]}")
            val heading = floatArrayOf(359.97f, 2.5f)
            SensorStream(SensorKind.HEADING, 1).filter(heading, T0 + i, blur)
            assertTrue(heading[0] >= 0f && heading[0] < 360f, "heading ${heading[0]}")
            assertEquals(2.5f, heading[1], "accuracy untouched")
        }
    }

    @Test
    fun `gravity and linear acceleration share the accelerometer calibration`() {
        val g = floatArrayOf(0.3f, 9.5f, 2.1f)
        val l = floatArrayOf(0.2f, -0.4f, 0.05f)
        val a = FloatArray(3) { g[it] + l[it] }
        SensorStream(SensorKind.GRAVITY, 1).filter(g, T0, blur)
        SensorStream(SensorKind.LINEAR_ACCELERATION, 2).filter(l, T0, blur)
        SensorStream(SensorKind.ACCELEROMETER, 3).filter(a, T0, blur)
        for (axis in 0..2) assertEquals(a[axis], g[axis] + l[axis], 3 * SensorStream.ACCEL_STEP)
    }

    @Test
    fun `uncalibrated readings carry the calibrated value and a zero bias`() {
        val stream = SensorStream(SensorKind.MAGNETIC_UNCALIBRATED, 1)
        val values = floatArrayOf(40f, -10f, 25f, 30f, -5f, 12f)
        stream.filter(values, T0, blur)
        val calibrated = floatArrayOf(10f, -5f, 13f)
        for (axis in 0..2) {
            assertEquals(0f, values[3 + axis])
            assertEquals(calibrated[axis], values[axis], SensorBlur.MAG_OFFSET + 0.2f + SensorStream.MAG_STEP)
        }
    }

    @Test
    fun `limited axes keep their flags and leave unsupported axes alone`() {
        val stream = SensorStream(SensorKind.ACCELEROMETER_LIMITED, 1)
        val values = floatArrayOf(0.313f, 0.77f, 9.7f, 1f, 0f, 1f)
        stream.filter(values, T0, blur)
        assertEquals(0.77f, values[1])
        assertEquals(listOf(1f, 0f, 1f), values.slice(3..5))
        assertNotEquals(0.313f, values[0])

        val uncalibrated = floatArrayOf(0.5f, 0.77f, 9.9f, 0.1f, 0.2f, 0.3f, 1f, 0f, 1f)
        SensorStream(SensorKind.GYROSCOPE_LIMITED_UNCALIBRATED, 1).filter(uncalibrated, T0, blur)
        assertEquals(0.77f, uncalibrated[1], "unsupported axis untouched")
        assertEquals(listOf(0f, 0f, 0f), uncalibrated.slice(3..5))
        assertEquals(listOf(1f, 0f, 1f), uncalibrated.slice(6..8))
    }

    @Test
    fun `short arrays and non-finite values are left alone`() {
        val short = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f)
        SensorStream(SensorKind.ACCELEROMETER_LIMITED_UNCALIBRATED, 1).filter(short, T0, blur)
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f), short.toList())
        val two = floatArrayOf(1f, 2f)
        SensorStream(SensorKind.GYROSCOPE, 1).filter(two, T0, blur)
        assertEquals(listOf(1f, 2f), two.toList())
        val odd = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 1f)
        SensorStream(SensorKind.ACCELEROMETER, 1).filter(odd, T0, blur)
        assertTrue(odd[0].isNaN())
        assertEquals(Float.POSITIVE_INFINITY, odd[1])
        val rotation = floatArrayOf(Float.NaN, 0f, 0f, 1f)
        SensorStream(SensorKind.ROTATION, 1).filter(rotation, T0, blur)
        assertTrue(rotation[0].isNaN())
    }

    @Test
    fun `head tracker keeps its discontinuity count bits`() {
        val stream = SensorStream(SensorKind.HEAD_TRACKER, 1)
        val count = Float.fromBits(5)
        val values = floatArrayOf(0.1f, 0.2f, 0.3f, 0.01f, 0.02f, 0.03f, count)
        stream.filter(values, T0, blur)
        assertEquals(5, values[6].toRawBits())
    }

    @Test
    fun `fast motion streams are decimated to the grid, 50 Hz streams pass whole`() {
        assertTrue(delivered(SensorKind.GYROSCOPE, 200) in 60..63)
        assertTrue(delivered(SensorKind.GYROSCOPE, 100) in 60..63)
        assertEquals(50, delivered(SensorKind.ACCELEROMETER, 50, jitterNs = 1_500_000))
        assertEquals(52, delivered(SensorKind.ACCELEROMETER, 52))
        assertEquals(30, delivered(SensorKind.MAGNETIC, 30))
        assertEquals(200, delivered(SensorKind.PRESSURE, 200), "pressure is not rate capped")
    }

    @Test
    fun `a burst after a gap is decimated like any other stream`() {
        val stream = SensorStream(SensorKind.GYROSCOPE, 1)
        assertTrue(stream.admitAt(T0))
        // A 39 ms gap, then a 400 Hz burst: only the first event of each grid slot passes, so after
        // the slot the gap ended in, admitted events are about a slot apart.
        val times = (0 until 40).map { T0 + 39_000_000L + it * 2_500_000L }
        val admitted = times.filter { stream.admitAt(it) }
        assertEquals(times.first(), admitted.first())
        admitted.zipWithNext().drop(1).forEach { (a, b) -> assertTrue(b - a >= SensorStream.PERIOD_NS - 2_500_000L) }
        assertTrue(admitted.size <= 7, "100 ms of a 400 Hz burst")
    }

    @Test
    fun `listeners registered at different times get the same events`() {
        val first = SensorStream(SensorKind.GYROSCOPE, 9)
        val times = (0 until 400).map { T0 + it * 5_000_000L }
        val firstAdmitted = times.filter { first.admitAt(it) }.toSet()
        // A second listener starts in the middle, resuming after the last event the first received.
        val second = SensorStream(SensorKind.GYROSCOPE, 9)
        val replay = SensorStream(SensorKind.GYROSCOPE, 9)
        val split = 137
        times.take(split).forEach { replay.admitAt(it) }
        second.resumeAfter(replay.lastTimestamp)
        val secondAdmitted = times.drop(split).filter { second.admitAt(it) }
        assertEquals(firstAdmitted.filter { it > times[split - 1] }, secondAdmitted)
    }

    @Test
    fun `decimation recovers after a gap or a clock reset`() {
        val stream = SensorStream(SensorKind.ACCELEROMETER, 1)
        assertTrue(stream.admitAt(T0))
        assertFalse(stream.admitAt(T0 + 5_000_000))
        assertFalse(stream.admitAt(T0 + 5_000_000), "duplicate")
        assertTrue(stream.admitAt(T0 + 10_000_000_000))
        assertTrue(stream.admitAt(1_000), "time went back by more than the reset interval")
    }

    @Test
    fun `pressure is whole hPa with hysteresis`() {
        val stream = SensorStream(SensorKind.PRESSURE, 1)
        assertEquals(1013f, stream.pressureOf(1013.2f))
        assertEquals(1013f, stream.pressureOf(1013.6f), "within hysteresis")
        assertEquals(1013f, stream.pressureOf(1012.4f))
        assertEquals(1014f, stream.pressureOf(1013.9f))
        assertEquals(1012f, stream.pressureOf(1012.1f))
    }

    @Test
    fun `light uses 1-2-5 levels with hysteresis`() {
        val stream = SensorStream(SensorKind.LIGHT, 1)
        assertEquals(0f, stream.level(7f))
        assertEquals(0f, stream.level(10.5f), "just above the next level")
        assertEquals(10f, stream.level(12f))
        assertEquals(10f, stream.level(9.5f), "just below the current level")
        assertEquals(500f, stream.level(730f))
        assertEquals(0f, stream.level(3f))
        assertEquals(100_000f, SensorStream(SensorKind.LIGHT, 1).level(250_000f))
    }

    @Test
    fun `light events that repeat the delivered level are dropped`() {
        val stream = SensorStream(SensorKind.LIGHT, 1)
        val first = floatArrayOf(12f)
        assertTrue(stream.filter(first, T0, blur))
        assertEquals(10f, first[0])
        assertFalse(stream.filter(floatArrayOf(13.5f), T0 + 1, blur), "same level")
        assertTrue(stream.filter(floatArrayOf(60f), T0 + 2, blur), "new level")
    }

    @Test
    fun `battery readings are rounded in the documented units, sentinels and other properties pass`() {
        val now = BatteryReadings.PROPERTY_CURRENT_NOW
        assertEquals(-400_000L, BatteryReadings.property(now, -432_100L))
        assertEquals(0L, BatteryReadings.property(now, -19_900L), "no unit guessing for small currents")
        assertEquals(0L, BatteryReadings.property(now, 20_000L))
        assertEquals(100_000L, BatteryReadings.property(now, 60_000L))
        assertEquals(-400_000, BatteryReadings.intProperty(BatteryReadings.PROPERTY_CURRENT_AVERAGE, -432_100))
        assertEquals(3_850_000L, BatteryReadings.property(BatteryReadings.PROPERTY_CHARGE_COUNTER, 3_861_234L))
        assertEquals(1_200_000_000L, BatteryReadings.property(BatteryReadings.PROPERTY_ENERGY_COUNTER, 1_234_567_890L))
        assertEquals(Long.MIN_VALUE, BatteryReadings.property(BatteryReadings.PROPERTY_ENERGY_COUNTER, Long.MIN_VALUE))
        assertEquals(Int.MIN_VALUE, BatteryReadings.intProperty(now, Int.MIN_VALUE))
        assertEquals(87L, BatteryReadings.property(CAPACITY, 87L))
        assertEquals(4_100, BatteryReadings.voltage(4_123))
        assertEquals(4_100_000, BatteryReadings.voltage(4_123_456), "µV device")
        assertEquals(0, BatteryReadings.voltage(0))
        assertEquals(320, BatteryReadings.temperature(317))
        assertEquals(-50, BatteryReadings.temperature(-46))
        assertEquals(0, BatteryReadings.chargeCounter(0))
        assertEquals(4_000_000, BatteryReadings.chargeCounter(4_012_000))
        assertEquals(300_000L, BatteryReadings.chargeTimeRemaining(61_000L))
        assertEquals(-1L, BatteryReadings.chargeTimeRemaining(-1L))
    }

    @Test
    fun `battery rounding never overflows`() {
        val energy = BatteryReadings.property(BatteryReadings.PROPERTY_ENERGY_COUNTER, Long.MAX_VALUE)
        assertTrue(energy in 0..Long.MAX_VALUE && Long.MAX_VALUE - energy < BatteryReadings.ENERGY_STEP_NWH)
        assertTrue(BatteryReadings.voltage(Int.MAX_VALUE) > 0)
        assertTrue(BatteryReadings.intProperty(BatteryReadings.PROPERTY_CHARGE_COUNTER, Int.MAX_VALUE) > 0)
        assertTrue(BatteryReadings.temperature(Int.MIN_VALUE + 1) < 0)
        assertEquals(Long.MAX_VALUE, BatteryReadings.chargeTimeRemaining(Long.MAX_VALUE))
    }

    @Test
    fun `neutral constants change nothing but the grid`() {
        val values = floatArrayOf(0.123f, -9.806f, 0.5f)
        SensorStream(SensorKind.ACCELEROMETER, 1).filter(values, T0, SensorBlur.NEUTRAL)
        assertEquals(0.123f, values[0], SensorStream.ACCEL_STEP)
        assertEquals(-9.806f, values[1], SensorStream.ACCEL_STEP)
    }

    private fun delivered(kind: SensorKind, hz: Int, jitterNs: Long = 0): Int {
        val stream = SensorStream(kind, 1)
        val period = 1_000_000_000L / hz
        var count = 0
        for (i in 0 until hz) {
            val jitter = if (i % 2 == 0) jitterNs else -jitterNs
            if (stream.filter(FloatArray(16), T0 + i * period + jitter, blur)) count++
        }
        return count
    }

    /** `SensorManager.getRotationMatrixFromVector` (3 x 3), in float as AOSP computes it. */
    private fun rotationMatrix(v: FloatArray): FloatArray {
        val q1 = v[0]
        val q2 = v[1]
        val q3 = v[2]
        val q0 = v[3]
        val sq1 = 2 * q1 * q1
        val sq2 = 2 * q2 * q2
        val sq3 = 2 * q3 * q3
        val q1q2 = 2 * q1 * q2
        val q3q0 = 2 * q3 * q0
        val q1q3 = 2 * q1 * q3
        val q2q0 = 2 * q2 * q0
        val q2q3 = 2 * q2 * q3
        val q1q0 = 2 * q1 * q0
        return floatArrayOf(
            1 - sq2 - sq3, q1q2 - q3q0, q1q3 + q2q0,
            q1q2 + q3q0, 1 - sq1 - sq3, q2q3 - q1q0,
            q1q3 - q2q0, q2q3 + q1q0, 1 - sq1 - sq2,
        )
    }

    private fun SensorStream.admitAt(timestamp: Long): Boolean = filter(FloatArray(16), timestamp, blur)

    private fun SensorStream.pressureOf(real: Float): Float = floatArrayOf(real).also { filter(it, T0, blur) }[0]

    private companion object {
        const val T0 = 1_000_000_000_000L
        const val N = 20_000
        const val EPS = 1e-6f
        const val GRID_EPS = 1e-2f
        const val CAPACITY = 4
    }
}
