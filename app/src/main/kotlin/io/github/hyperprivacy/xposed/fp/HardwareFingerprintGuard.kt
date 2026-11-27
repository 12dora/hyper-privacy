package io.github.hyperprivacy.xposed.fp

import io.github.hyperprivacy.core.fingerprint.HardwareTraits
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.xposed.GuardEnv
import io.github.hyperprivacy.xposed.Sinks

/**
 * IDENTIFIER rule for `IdentifierSource.HARDWARE_TRAITS` (`fingerprint.hardware`): permission-free
 * hardware readings that SDKs hash into a device fingerprint are made different per app (see
 * [io.github.hyperprivacy.core.fingerprint.HardwareTraits] for exactly what changes and what is
 * deliberately left alone). Only for protected third-party apps; refuses vendor-catalog and
 * vendor-namespace packages.
 *
 * The rule ships off, so nothing is hooked until it is on ([installWhenOn]).
 *
 * Fail-open throughout: hooks run in `ExceptionMode.PROTECTIVE`, the original always runs, and a
 * missing module secret or any derivation failure returns the real value.
 */
internal class HardwareFingerprintGuard(private val env: GuardEnv) {
    /** Returns the number of methods hooked for [rule] (positive while armed for a later switch-on). */
    fun install(rule: Rule): Int {
        val state = FingerprintState(env, rule.id, HardwareTraits::derive)
        return env.installWhenOn(state) { hookAll(state) }
    }

    private fun hookAll(state: FingerprintState<HardwareTraits>): Int {
        val sensors = env.reportSink(SENSOR_SINK) { SensorTraitHooks(env, state).install() }
        val fsid = env.reportSink(FS_SINK) { FsIdHooks(env, state).install() }
        val battery = env.reportSink(BATTERY_SINK) { BatteryTraitHooks(env, state).install() }
        env.log.info("${state.ruleId}: hooked sensors=$sensors fsid=$fsid battery=$battery in ${env.packageName}")
        return sensors + fsid + battery
    }

    companion object {
        /** Sink names: hook state per group, and the group's cost in diagnostics mode. */
        const val SENSOR_SINK = Sinks.FP_SENSOR
        const val FS_SINK = Sinks.FP_FSID
        const val BATTERY_SINK = Sinks.FP_BATTERY
    }
}
