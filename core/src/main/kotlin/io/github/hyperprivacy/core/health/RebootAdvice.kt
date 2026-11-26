package io.github.hyperprivacy.core.health

/** Why the device should be restarted for the module to work fully. */
public enum class RebootReason {
    NONE,

    /** system_server was added to the LSPosed scope since this boot started. */
    SCOPE_ADDED,

    /** system_server still runs an older module build than the installed one. */
    MODULE_UPDATED,

    /** system_server is in the scope but has not reported since this boot: its guards are not running. */
    GUARDS_INACTIVE,
}

/**
 * What the module knows about its own system_server half, derived from data that already exists:
 * system_server reports the module build it loaded ([io.github.hyperprivacy.core.status.ProcessMeta.MODULE])
 * and its sink states with every status batch.
 *
 * @property scopeRebootPending system_server joined the scope since this boot.
 * @property installedVersion version code of the installed module (what the app runs as).
 * @property systemReportedThisBoot system_server delivered any status since this boot.
 * @property systemModuleVersion module build system_server reported this boot; `null` if none. A
 *   system_server that reported without it runs a build from before it was reported at all.
 * @property systemInScope the LSPosed scope covers system_server; `null` when unknown.
 * @property uptimeMs time since boot.
 */
public data class SystemHalf(
    val scopeRebootPending: Boolean,
    val installedVersion: Long,
    val systemReportedThisBoot: Boolean,
    val systemModuleVersion: Long?,
    val systemInScope: Boolean?,
    val uptimeMs: Long,
)

public object RebootAdvice {
    /**
     * system_server reports a few seconds after it is ready; allowing this long after boot before
     * calling a missing report a problem keeps the hint from flashing during startup.
     */
    public const val STARTUP_GRACE_MS: Long = 3 * 60_000L

    public fun reason(system: SystemHalf): RebootReason = when {
        system.scopeRebootPending -> RebootReason.SCOPE_ADDED

        system.systemReportedThisBoot && system.systemModuleVersion != system.installedVersion ->
            RebootReason.MODULE_UPDATED

        system.systemInScope == true && !system.systemReportedThisBoot && system.uptimeMs > STARTUP_GRACE_MS ->
            RebootReason.GUARDS_INACTIVE

        else -> RebootReason.NONE
    }
}
