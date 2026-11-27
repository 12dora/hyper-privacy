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
        assertEquals(SensorKind.ACCELEROMETER, SensorKind.of(SensorKind.TYPE_ACCELEROMETER))
        assertEquals(SensorKind.MAGNETIC_UNCALIBRATED, SensorKind.of(SensorKind.TYPE_MAGNETIC_FIELD_UNCALIBRATED))
        assertEquals(SensorKind.ROTATION, SensorKind.of(SensorKind.TYPE_GAME_ROTATION_VECTOR))
        assertEquals(SensorKind.LIGHT, SensorKind.of(SensorKind.TYPE_LIGHT))
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
    fun `dither is zero-mean and hides the input lattice`() {
        val stream = SensorStream(SensorKind.FUSED_ACCELERATION, 7)
        // A value a third of a step above a grid point averages to that value, not to the grid point.
        val real = 1.0f + SensorStream.ACCEL_STEP / 3
        var sum = 0.0
        val seen = HashSet<Float>()
        repeat(N) { i ->
            val values = floatArrayOf(real, 0f, 0f)
            stream.filter(values, T0 + i * SensorStream.PERIOD_NS, blur)
            sum += values[0]
            seen += values[0]
        }
        assertEquals(real.toDouble(), sum / N, SensorStream.ACCEL_STEP / 20.0)
        assertEquals(2, seen.size, "only the two neighbouring grid points")
    }

    @Test
    fun `uncalibrated readings carry the calibrated value and a zero bias`() {
        val stream = SensorStream(SensorKind.MAGNETIC_UNCALIBRATED, 1)
        val values = floatArrayOf(40f, -10f, 25f, 30f, -5f, 12f)
        stream.filter(values, T0, blur)
        val calibrated = floatArrayOf(10f, -5f, 13f)
        for (axis in 0..2) {
            assertEquals(0f, values[3 + axis])
            assertEquals(calibrated[axis], values[axis], SensorBlur.MAG_OFFSET + 0.1f + SensorStream.MAG_STEP)
        }
    }

    @Test
    fun `limited axes keep their flags and leave unsupported axes alone`() {
        val stream = SensorStream(SensorKind.ACCELEROMETER_LIMITED, 1)
        val values = floatArrayOf(0.313f, 0f, 9.7f, 1f, 0f, 1f)
        stream.filter(values, T0, blur)
        assertEquals(0f, values[1])
        assertEquals(listOf(1f, 0f, 1f), values.slice(3..5))
        assertNotEquals(0.313f, values[0])
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
    fun `fast motion streams are decimated to 50 Hz, slower ones pass whole`() {
        assertEquals(SensorStream.MAX_RATE_HZ, delivered(SensorKind.GYROSCOPE, 200))
        assertEquals(SensorStream.MAX_RATE_HZ, delivered(SensorKind.GYROSCOPE, 100))
        assertTrue(delivered(SensorKind.GYROSCOPE, 60) in 45..SensorStream.MAX_RATE_HZ)
        assertEquals(50, delivered(SensorKind.ACCELEROMETER, 50, jitterNs = 1_500_000))
        assertEquals(30, delivered(SensorKind.MAGNETIC, 30))
        assertEquals(200, delivered(SensorKind.PRESSURE, 200), "pressure is not rate capped")
    }

    @Test
    fun `decimation recovers after a gap or a clock reset`() {
        val stream = SensorStream(SensorKind.ACCELEROMETER, 1)
        assertTrue(stream.admitAt(T0))
        assertFalse(stream.admitAt(T0 + 5_000_000))
        assertTrue(stream.admitAt(T0 + 10_000_000_000))
        assertTrue(stream.admitAt(1_000), "time went backwards")
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
        assertEquals(0f, stream.lightOf(7f))
        assertEquals(0f, stream.lightOf(10.5f), "just above the next level")
        assertEquals(10f, stream.lightOf(12f))
        assertEquals(10f, stream.lightOf(9.5f), "just below the current level")
        assertEquals(500f, stream.lightOf(730f))
        assertEquals(0f, stream.lightOf(3f))
        assertEquals(100_000f, SensorStream(SensorKind.LIGHT, 1).lightOf(250_000f))
    }

    @Test
    fun `battery readings are rounded, sentinels and other properties pass`() {
        assertEquals(-400_000L, BatteryReadings.property(BatteryReadings.PROPERTY_CURRENT_NOW, -432_100L))
        assertEquals(-400L, BatteryReadings.property(BatteryReadings.PROPERTY_CURRENT_AVERAGE, -432L), "mA device")
        assertEquals(3_850_000L, BatteryReadings.property(BatteryReadings.PROPERTY_CHARGE_COUNTER, 3_861_234L))
        assertEquals(Long.MIN_VALUE, BatteryReadings.property(BatteryReadings.PROPERTY_ENERGY_COUNTER, Long.MIN_VALUE))
        assertEquals(
            Int.MIN_VALUE.toLong(),
            BatteryReadings.property(BatteryReadings.PROPERTY_CURRENT_NOW, Int.MIN_VALUE.toLong()),
        )
        assertEquals(87L, BatteryReadings.property(CAPACITY, 87L))
        assertEquals(4_100, BatteryReadings.voltage(4_123))
        assertEquals(4_100_000, BatteryReadings.voltage(4_123_456), "µV device")
        assertEquals(0, BatteryReadings.voltage(0))
        assertEquals(320, BatteryReadings.temperature(317))
        assertEquals(-50, BatteryReadings.temperature(-46))
        assertEquals(0, BatteryReadings.chargeCounter(0))
        assertEquals(4_000_000, BatteryReadings.chargeCounter(4_012_000))
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

    private fun SensorStream.admitAt(timestamp: Long): Boolean = filter(FloatArray(16), timestamp, blur)

    private fun SensorStream.pressureOf(real: Float): Float = floatArrayOf(real).also { filter(it, T0, blur) }[0]

    private fun SensorStream.lightOf(real: Float): Float = floatArrayOf(real).also { filter(it, T0, blur) }[0]

    private companion object {
        const val T0 = 1_000_000_000_000L
        const val N = 20_000
        const val EPS = 1e-6f
        const val GRID_EPS = 1e-2f
        const val CAPACITY = 4
    }
}
