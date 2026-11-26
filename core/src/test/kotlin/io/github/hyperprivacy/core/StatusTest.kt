package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.status.DisplayStatus
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.RuleReport
import io.github.hyperprivacy.core.status.StatusCodec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StatusTest {
    @Test
    fun `codec round-trips`() {
        val reports = listOf(
            RuleReport("com.a", "r.one", HookState.ACTIVE, 3),
            RuleReport("com.b", "r.two", HookState.MISSED, 0),
        )
        assertEquals(reports, StatusCodec.decode(StatusCodec.encode(reports)))
    }

    @Test
    fun `malformed lines are dropped`() {
        val text = listOf(
            "com.a\tr\tACTIVE\t1",
            "com.a\tr\tBOGUS\t1",
            "com.a\tr\tACTIVE\t-1",
            "com.a\tr\tACTIVE\tx",
            "com.a\tr\tACTIVE",
            "\tr\tACTIVE\t1",
            "",
        ).joinToString("\n")
        assertEquals(listOf(RuleReport("com.a", "r", HookState.ACTIVE, 1)), StatusCodec.decode(text))
        assertEquals(emptyList(), StatusCodec.decode(null))
    }

    @Test
    fun `encode refuses separator characters`() {
        assertFailsWith<IllegalArgumentException> {
            StatusCodec.encode(listOf(RuleReport("com.a\tx", "r", HookState.ACTIVE, 0)))
        }
    }

    @Test
    fun `display status aggregation`() {
        assertEquals(DisplayStatus.NOT_RUN, DisplayStatus.of(emptyList()))
        assertEquals(DisplayStatus.MISSED, DisplayStatus.of(listOf(HookState.MISSED)))
        assertEquals(DisplayStatus.ACTIVE, DisplayStatus.of(listOf(HookState.MISSED, HookState.ACTIVE)))
    }
}
