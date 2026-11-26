package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.firewall.FirewallPlan
import io.github.hyperprivacy.core.firewall.UidPolicy
import io.github.hyperprivacy.core.firewall.UidPolicy.Refusal
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirewallTest {
    @Test
    fun `plan computes deny and clear sets`() {
        val plan = FirewallPlan.diff(applied = setOf(1, 2), desired = setOf(2, 3))
        assertEquals(setOf(3), plan.toDeny)
        assertEquals(setOf(1), plan.toClear)
        assertTrue(FirewallPlan.diff(setOf(5), setOf(5)).isEmpty)
    }

    @Test
    fun `own app uid is allowed in any user`() {
        assertNull(UidPolicy.refusal(10_155, packagesSharingUid = 1))
        assertNull(UidPolicy.refusal(1_010_155, packagesSharingUid = 1))
    }

    @Test
    fun `system and non-app uids are refused`() {
        listOf(0, 1000, 1001, 9_999, 20_000, 99_000, 101_000, -1).forEach {
            assertEquals(Refusal.NOT_APP_UID, UidPolicy.refusal(it, packagesSharingUid = 1), "uid $it")
        }
    }

    @Test
    fun `shared or unknown uids are refused`() {
        assertEquals(Refusal.SHARED_UID, UidPolicy.refusal(10_200, packagesSharingUid = 2))
        assertEquals(Refusal.SHARED_UID, UidPolicy.refusal(10_200, packagesSharingUid = 0))
    }
}
