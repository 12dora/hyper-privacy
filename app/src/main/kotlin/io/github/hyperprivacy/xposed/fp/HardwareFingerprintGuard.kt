package io.github.hyperprivacy.xposed.fp

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.xposed.GuardEnv
import io.github.hyperprivacy.xposed.Sinks
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IDENTIFIER rule for `IdentifierSource.HARDWARE_TRAITS` (`fingerprint.hardware`): permission-free
 * hardware readings that SDKs hash into a device fingerprint are made different per app (see
 * [io.github.hyperprivacy.core.fingerprint.HardwareTraits] for exactly what changes and what is
 * deliberately left alone). Only for protected third-party apps; refuses vendor packages.
 *
 * The rule ships off, so nothing is hooked until it is on: hooks are installed at process start
 * when the switch is already on, otherwise the first time it is switched on (the apps' next reads
 * are then covered; values read earlier are not retrofitted). Once installed, switching it off
 * makes every hook a pass-through after one volatile read.
 *
 * Fail-open throughout: hooks run in `ExceptionMode.PROTECTIVE`, the original always runs, and a
 * missing module secret or any derivation failure returns the real value.
 */
internal class HardwareFingerprintGuard(private val env: GuardEnv) {
    private val installed = AtomicBoolean()

    /**
     * Returns the number of methods hooked for [rule]; a positive value when hooks are armed for a
     * later switch-on, since an off rule is not a failure.
     */
    fun install(rule: Rule): Int {
        if (env.packageName in Catalog.allPackages) {
            env.log.warn("${rule.id}: ${env.packageName} is a vendor package; hardware traits left untouched")
            return 0
        }
        val state = FingerprintState(env, rule.id).apply { refresh() }
        env.switches.addListener {
            state.refresh()
            if (state.enabled && !installed.get()) {
                val hooked = hookAll(state)
                if (hooked == 0) env.reporter.state(env.packageName, rule.id, HookState.MISSED)
            }
        }
        return if (state.enabled) hookAll(state) else ARMED
    }

    private fun hookAll(state: FingerprintState): Int {
        if (!installed.compareAndSet(false, true)) return ARMED
        val sensors = env.report(SENSOR_SINK) { SensorTraitHooks(env, state).install() }
        val fsid = env.report(FS_SINK) { FsIdHooks(env, state).install() }
        val battery = env.report(BATTERY_SINK) { BatteryTraitHooks(env, state).install() }
        env.log.info("fingerprint.hardware: hooked sensors=$sensors fsid=$fsid battery=$battery in ${env.packageName}")
        return sensors + fsid + battery
    }

    /** Runs [install] (a failure counts as zero hooks) and records the group's sink state. */
    private fun GuardEnv.report(sink: String, install: () -> Int): Int {
        val hooked = log.guard(sink) { install() } ?: 0
        reporter.sink(packageName, sink, if (hooked > 0) HookState.ACTIVE else HookState.MISSED)
        return hooked
    }

    private companion object {
        const val ARMED = 1
        const val SENSOR_SINK = Sinks.FP_SENSOR
        const val FS_SINK = Sinks.FP_FSID
        const val BATTERY_SINK = Sinks.FP_BATTERY
    }
}
