package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.health.RebootAdvice
import io.github.hyperprivacy.core.health.RebootReason
import io.github.hyperprivacy.core.health.SystemHalf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RebootAdviceTest {
    private val healthy = SystemHalf(
        scopeRebootPending = false,
        installedVersion = 5,
        systemReportedThisBoot = true,
        systemModuleVersion = 5,
        systemInScope = true,
        uptimeMs = 10 * 60_000,
    )

    private fun reason(change: (SystemHalf) -> SystemHalf) = RebootAdvice.reason(change(healthy))

    @Test
    fun `a healthy system half needs nothing`() {
        assertEquals(RebootReason.NONE, RebootAdvice.reason(healthy))
    }

    @Test
    fun `scope added since boot`() {
        val reason = reason { it.copy(scopeRebootPending = true, systemReportedThisBoot = false) }
        assertEquals(RebootReason.SCOPE_ADDED, reason)
    }

    @Test
    fun `an updated module needs a restart until system_server runs it`() {
        assertEquals(RebootReason.MODULE_UPDATED, reason { it.copy(installedVersion = 6) })
        assertEquals(RebootReason.MODULE_UPDATED, reason { it.copy(installedVersion = 4) })
    }

    @Test
    fun `a system_server that reports no module build predates reporting it`() {
        assertEquals(RebootReason.MODULE_UPDATED, reason { it.copy(systemModuleVersion = null) })
    }

    @Test
    fun `no report from a scoped system_server after the startup grace`() {
        val silent = healthy.copy(systemReportedThisBoot = false, systemModuleVersion = null)
        assertEquals(RebootReason.GUARDS_INACTIVE, RebootAdvice.reason(silent))
        assertEquals(RebootReason.NONE, RebootAdvice.reason(silent.copy(uptimeMs = 60_000)))
        assertEquals(RebootReason.NONE, RebootAdvice.reason(silent.copy(systemInScope = false)))
        assertEquals(RebootReason.NONE, RebootAdvice.reason(silent.copy(systemInScope = null)))
    }
}
