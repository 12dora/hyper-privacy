package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.config.ProtectionPause
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtectionPauseTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `absent or malformed deadlines mean not paused`() {
        assertEquals(0L, ProtectionPause.untilOf(emptyMap<String, Any>()))
        assertEquals(0L, ProtectionPause.untilOf(mapOf(ProtectionPause.KEY to "5")))
        assertEquals(0L, ProtectionPause.untilOf(mapOf(ProtectionPause.KEY to -3L)))
        assertFalse(ProtectionPause.isPaused(0, now))
    }

    @Test
    fun `a timed pause ends at its deadline`() {
        val until = ProtectionPause.deadline(ProtectionPause.Option.QUARTER_HOUR, now)
        assertEquals(now + 15 * 60_000, until)
        assertTrue(ProtectionPause.isPaused(until, now))
        assertTrue(ProtectionPause.isPaused(until, until - 1))
        assertFalse(ProtectionPause.isPaused(until, until))
        assertEquals(now + 60 * 60_000, ProtectionPause.deadline(ProtectionPause.Option.HOUR, now))
    }

    @Test
    fun `a manual pause never ends by itself`() {
        val until = ProtectionPause.deadline(ProtectionPause.Option.UNTIL_RESUMED, now)
        assertEquals(ProtectionPause.UNTIL_MANUAL, until)
        assertTrue(ProtectionPause.isPaused(until, Long.MAX_VALUE - 1))
        assertNull(ProtectionPause.resumeAt(until, now))
        assertFalse(ProtectionPause.isExpired(until, Long.MAX_VALUE - 1))
        assertNull(ProtectionPause.remainingMinutes(until, now))
    }

    @Test
    fun `remaining minutes round up`() {
        assertEquals(15, ProtectionPause.remainingMinutes(now + 15 * 60_000, now))
        assertEquals(2, ProtectionPause.remainingMinutes(now + 60_001, now))
        assertEquals(1, ProtectionPause.remainingMinutes(now + 1, now))
        assertNull(ProtectionPause.remainingMinutes(now, now))
    }

    @Test
    fun `one resume is scheduled at the deadline, none for a pause that is over`() {
        val until = now + 15 * 60_000
        assertEquals(until, ProtectionPause.resumeAt(until, now))
        assertEquals(until, ProtectionPause.resumeAt(until, until - 1))
        assertNull(ProtectionPause.resumeAt(until, until))
        assertNull(ProtectionPause.resumeAt(0, now))
    }

    @Test
    fun `a process starting after the deadline is not paused`() {
        val until = now + 60_000
        // One clock read when the process reads its switches: the stored deadline alone decides.
        assertTrue(ProtectionPause.isPaused(until, now))
        assertFalse(ProtectionPause.isPaused(until, until + 1))
        assertTrue(ProtectionPause.isExpired(until, until))
        assertFalse(ProtectionPause.isExpired(until, until - 1))
        assertFalse(ProtectionPause.isExpired(0, now))
    }

    @Test
    fun `the pause key wakes hooks`() {
        assertTrue(ConfigCodec.affectsHooks(ProtectionPause.KEY))
    }
}
