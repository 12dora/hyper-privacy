package io.github.hyperprivacy.xposed.fp

import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.sensor.SensorBlur
import io.github.hyperprivacy.xposed.GuardEnv
import io.github.hyperprivacy.xposed.Sinks

/**
 * IDENTIFIER rule for `IdentifierSource.SENSOR_DATA` (`sensor.data`): sensor events and battery
 * readings of a protected third-party app are made coarser and get a per-app calibration
 * ([io.github.hyperprivacy.core.sensor.SensorStream], [io.github.hyperprivacy.core.sensor.BatteryReadings]).
 * Only the app's own process is hooked, so system features (auto-brightness, rotation, the battery
 * indicator) keep the real readings. Refuses vendor-catalog and vendor-namespace packages.
 *
 * Ships off; nothing is hooked until it is on ([installWhenOn]). Listeners registered before the
 * switch-on are covered from their next event. Fail-open: a missing secret, missing framework
 * fields or any failure delivers the real reading.
 */
internal class SensorDataGuard(private val env: GuardEnv) {
    /** Returns the number of methods hooked for [rule] (positive while armed for a later switch-on). */
    fun install(rule: Rule): Int {
        val state = FingerprintState(env, rule.id, SensorBlur::derive)
        return env.installWhenOn(state) { hookAll(state) }
    }

    private fun hookAll(state: FingerprintState<SensorBlur>): Int {
        val events = env.reportSink(EVENT_SINK) { SensorEventHooks(env, state).install() }
        val battery = env.reportSink(BATTERY_SINK) { BatteryReadingHooks(env, state).install() }
        env.log.info("${state.ruleId}: hooked events=$events battery=$battery in ${env.packageName}")
        return events + battery
    }

    companion object {
        const val EVENT_SINK = Sinks.SENSOR_EVENTS
        const val BATTERY_SINK = Sinks.SENSOR_BATTERY
    }
}
