package io.github.hyperprivacy.core.status

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.system.SettingsObservation
import io.github.hyperprivacy.core.tracker.TrackerProtection

/** One status batch as carried by the status contract: every table optional. */
public data class StatusBatch(
    val reports: List<RuleReport> = emptyList(),
    val sinks: List<SinkReport> = emptyList(),
    val observed: List<ObservedEndpoint> = emptyList(),
    val meta: List<ProcessMeta> = emptyList(),
) {
    val isEmpty: Boolean get() = reports.isEmpty() && sinks.isEmpty() && observed.isEmpty() && meta.isEmpty()

    /** Rows of both batches, [other]'s after this one's (no merging). */
    public operator fun plus(other: StatusBatch): StatusBatch = when {
        other.isEmpty -> this
        isEmpty -> other
        else -> StatusBatch(reports + other.reports, sinks + other.sinks, observed + other.observed, meta + other.meta)
    }
}

/**
 * Status delivery for protected third-party apps ([TrackerProtection]), which must never see the
 * module package or its status provider (module self-hiding stays in force for them).
 *
 * Such an app hands its batch to system_server instead, as a `call` on the Settings provider (which
 * runs in system_server and is visible to every app) that the stock provider refuses silently:
 * method [METHOD] (a device-config write; without `WRITE_DEVICE_CONFIG` the provider throws a
 * `SecurityException` and logs nothing), `arg` the package's [RelayKeys.token] and the sealed
 * payload ([RelayKeys.seal] over [RelayPayload]) under [EXTRA_PAYLOAD]. The token and the payload
 * key are derived from the module secret and the package name, so they differ per app and per
 * install, and the payload is opaque to anything in the app that watches its own calls.
 *
 * system_server answers only a call whose token is that of a protected package, from that
 * package's own uid, within the rate limit, and keeps only what [accept] lets through; it then
 * forwards it to the module app with its own status reports. Everything else runs the stock
 * provider (the same `SecurityException`), so the channel is no oracle, and if the hook is not
 * installed yet (boot) the call fails the same way and is retried later.
 */
public object StatusRelay {
    /** The Settings provider method a relay call uses; see the class comment. */
    public const val METHOD: String = "PUT_config"

    /** Bundle key of the sealed payload (a `byte[]`, the only extra system_server reads). */
    public const val EXTRA_PAYLOAD: String = "data"

    /** Upper bound on rows of one table accepted from one call; the reporter sends far fewer. */
    public const val MAX_ROWS_PER_TABLE: Int = 512

    /** Upper bound on one plaintext payload (UTF-8 bytes); reporters split larger batches. */
    public const val MAX_PAYLOAD_BYTES: Int = 48 * 1024

    /** Upper bound on one sealed payload: [MAX_PAYLOAD_BYTES] plus the cipher's overhead. */
    public const val MAX_SEALED_BYTES: Int = MAX_PAYLOAD_BYTES + RelayKeys.OVERHEAD

    /** Hit delta accepted per relayed report; far above what one flush interval can produce. */
    public const val MAX_HITS_PER_REPORT: Long = 1_000_000L

    /** Rule ids a protected app reports for itself: its SDK rules and [Catalog.protectedAppRules]. */
    public fun isProtectedAppRuleId(ruleId: String): Boolean =
        TrackerProtection.sdkIdOf(ruleId) != null || Catalog.protectedAppRuleIds.contains(ruleId)

    /**
     * What system_server relays from one caller: rows about packages [ownedByCaller] accepts (the
     * protected packages of the calling uid); rule reports only for [isProtectedAppRuleId] rules,
     * hit deltas capped at [MAX_HITS_PER_REPORT]; sinks only those [isKnownSink] accepts; observed
     * network endpoints only (Settings reads are recorded by system_server itself); every time
     * clamped to [now]; each table capped at [MAX_ROWS_PER_TABLE]. Vendor packages never pass:
     * they report directly.
     */
    public fun accept(
        batch: StatusBatch,
        ownedByCaller: (String) -> Boolean,
        isKnownSink: (String) -> Boolean,
        now: Long,
    ): StatusBatch {
        fun owned(packageName: String) = packageName !in Catalog.allPackages &&
            packageName != Catalog.SYSTEM_SCOPE && ownedByCaller(packageName)
        return StatusBatch(
            reports = batch.reports.asSequence()
                .filter { owned(it.packageName) && isProtectedAppRuleId(it.ruleId) && it.hits >= 0 }
                .map { if (it.hits > MAX_HITS_PER_REPORT) it.copy(hits = MAX_HITS_PER_REPORT) else it }
                .take(MAX_ROWS_PER_TABLE).toList(),
            sinks = batch.sinks.asSequence().filter { owned(it.packageName) && isKnownSink(it.sink) }
                .take(MAX_ROWS_PER_TABLE).toList(),
            observed = batch.observed.asSequence()
                .filter { owned(it.packageName) && it.count >= 0 && !SettingsObservation.isSettingsHost(it.host) }
                .map { it.clampedTo(now) }
                .take(MAX_ROWS_PER_TABLE).toList(),
            meta = batch.meta.asSequence().filter { owned(it.packageName) }.map { it.clampedTo(now) }
                .take(MAX_ROWS_PER_TABLE).toList(),
        )
    }
}

/** This endpoint with its times no later than [now] (a hostile far-future time would pin it forever). */
public fun ObservedEndpoint.clampedTo(now: Long): ObservedEndpoint =
    if (lastSeen <= now) this else copy(firstSeen = minOf(firstSeen, now), lastSeen = now)

/** These facts with a start time no later than [now]. */
public fun ProcessMeta.clampedTo(now: Long): ProcessMeta =
    if (processStartAt <= now) this else copy(processStartAt = now)

/** [a] + [b] for non-negative counts, saturating at [Long.MAX_VALUE] instead of overflowing. */
public fun saturatingAdd(a: Long, b: Long): Long {
    val sum = a + b
    return if (b > 0 && sum < a) Long.MAX_VALUE else sum
}
