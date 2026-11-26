package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.scope.Protection
import io.github.hyperprivacy.core.scope.ScopeDecisions
import io.github.hyperprivacy.core.scope.ScopeDecisions.Answer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopeDecisionsTest {
    @Test
    fun `protection is tri-state and an unreadable preference is unknown, not off`() {
        assertEquals(Protection.ON, Protection.resolve(null) { "a,b" })
        assertEquals(Protection.ON, Protection.resolve(null) { "" })
        assertEquals(Protection.OFF, Protection.resolve(null) { null })
        assertEquals(Protection.UNKNOWN, Protection.resolve(null, null), "preferences not available")
        assertEquals(Protection.UNKNOWN, Protection.resolve(null) { error("remote call failed") })
    }

    @Test
    fun `a pending write wins over what the preferences say`() {
        assertEquals(Protection.ON, Protection.resolve(true) { null })
        assertEquals(Protection.OFF, Protection.resolve(false) { "a" })
        assertEquals(Protection.ON, Protection.resolve(true, null))
    }

    @Test
    fun `reconcile removes only on a definite off`() {
        assertTrue(ScopeDecisions.shouldRemove(Protection.OFF))
        assertFalse(ScopeDecisions.shouldRemove(Protection.UNKNOWN))
        assertFalse(ScopeDecisions.shouldRemove(Protection.ON))
    }

    @Test
    fun `answers to the current request are reported`() {
        assertEquals(Answer.REPORT, answer(approved = true, Protection.ON, current = true, first = true))
        assertEquals(Answer.REPORT, answer(approved = false, Protection.ON, current = true, first = true))
        assertEquals(Answer.REPORT, answer(approved = true, Protection.UNKNOWN, current = true, first = true))
        assertEquals(Answer.IGNORE, answer(approved = true, Protection.ON, current = true, first = false))
    }

    @Test
    fun `protection turned off meanwhile takes the app out, whenever the answer comes`() {
        assertEquals(Answer.REMOVE, answer(approved = true, Protection.OFF, current = false, first = true))
        assertEquals(Answer.REMOVE, answer(approved = true, Protection.OFF, current = true, first = false))
    }

    @Test
    fun `ON, OFF, ON then the first request is approved - the scope stays and the user is told`() {
        // Request 1 was cancelled by OFF and superseded by request 2; protection is ON again.
        assertEquals(Answer.REPORT, answer(approved = true, Protection.ON, current = false, first = true))
        // A stale denial or timeout says nothing about request 2, which is still open.
        assertEquals(Answer.IGNORE, answer(approved = false, Protection.ON, current = false, first = true))
    }

    @Test
    fun `an unknown state never removes, even for a stale answer`() {
        assertEquals(Answer.IGNORE, answer(approved = true, Protection.UNKNOWN, current = false, first = true))
        assertEquals(Answer.IGNORE, answer(approved = false, Protection.UNKNOWN, current = false, first = false))
    }

    private fun answer(approved: Boolean, protection: Protection, current: Boolean, first: Boolean) =
        ScopeDecisions.onAnswer(approved, protection, current, first)
}
