package io.github.hyperprivacy.core.status

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.tracker.TrackerProtection
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One status batch as carried by the status contract: every table optional. */
public data class StatusBatch(
    val reports: List<RuleReport> = emptyList(),
    val sinks: List<SinkReport> = emptyList(),
    val observed: List<ObservedEndpoint> = emptyList(),
    val meta: List<ProcessMeta> = emptyList(),
    val perf: List<SinkPerf> = emptyList(),
) {
    val isEmpty: Boolean
        get() = reports.isEmpty() && sinks.isEmpty() && observed.isEmpty() && meta.isEmpty() && perf.isEmpty()
}

/**
 * Status delivery for protected third-party apps ([TrackerProtection]), which must never see the
 * module package or its status provider (module self-hiding stays in force for them).
 *
 * Such an app hands its batch to system_server instead: a `call` on the Settings provider (which
 * runs in system_server and is visible to every app) whose method name is [method], derived from
 * the module secret, so it is unknown to anything but the module's own code and differs on every
 * install. system_server answers only that method, only for a caller uid that belongs to a
 * protected, non-vendor app, keeps only what [accept] lets through, and forwards it to the module
 * app with its own status reports. Any other caller (or a wrong method) gets the provider's normal
 * answer for an unknown method, `null`, so the channel is no oracle.
 */
public object StatusRelay {
    private const val ALGORITHM = "HmacSHA256"
    private const val LABEL = "hyperprivacy/status-relay/v1"
    private const val HEX = "0123456789abcdef"
    private const val METHOD_BYTES = 16
    private const val NIBBLE = 4
    private const val NIBBLE_MASK = 0xF

    /** Upper bound on rows of one table accepted from one call; the reporter sends far fewer. */
    public const val MAX_ROWS_PER_TABLE: Int = 512

    /** The relay method name for [secret]: 32 lowercase hex characters. */
    public fun method(secret: ByteArray): String {
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret, ALGORITHM)) }
        val digest = mac.doFinal(LABEL.toByteArray(Charsets.UTF_8))
        return buildString(METHOD_BYTES * 2) {
            for (i in 0 until METHOD_BYTES) {
                val value = digest[i].toInt()
                append(HEX[(value shr NIBBLE) and NIBBLE_MASK]).append(HEX[value and NIBBLE_MASK])
            }
        }
    }

    /** Rule ids a protected app reports for itself: its SDK rules and [Catalog.protectedAppRules]. */
    public fun isProtectedAppRuleId(ruleId: String): Boolean =
        TrackerProtection.sdkIdOf(ruleId) != null || Catalog.protectedAppRuleIds.contains(ruleId)

    /**
     * What system_server relays from one caller: rows about packages [ownedByCaller] accepts (the
     * protected packages of the calling uid), rule reports only for [isProtectedAppRuleId] rules,
     * each table capped at [MAX_ROWS_PER_TABLE]. Vendor packages never pass: they report directly.
     */
    public fun accept(batch: StatusBatch, ownedByCaller: (String) -> Boolean): StatusBatch {
        fun owned(packageName: String) = packageName !in Catalog.allPackages &&
            packageName != Catalog.SYSTEM_SCOPE && ownedByCaller(packageName)
        return StatusBatch(
            reports = batch.reports.asSequence()
                .filter { owned(it.packageName) && isProtectedAppRuleId(it.ruleId) && it.hits >= 0 }
                .take(MAX_ROWS_PER_TABLE).toList(),
            sinks = batch.sinks.asSequence().filter { owned(it.packageName) }.take(MAX_ROWS_PER_TABLE).toList(),
            observed = batch.observed.asSequence().filter { owned(it.packageName) && it.count >= 0 }
                .take(MAX_ROWS_PER_TABLE).toList(),
            meta = batch.meta.asSequence().filter { owned(it.packageName) }.take(MAX_ROWS_PER_TABLE).toList(),
            // Per sink name, not per package: nothing about the app, so no ownership to check.
            perf = batch.perf.take(PerfCodec.MAX_ROWS),
        )
    }
}
