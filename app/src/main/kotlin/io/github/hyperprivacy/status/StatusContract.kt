package io.github.hyperprivacy.status

import android.os.Bundle
import io.github.hyperprivacy.BuildConfig
import io.github.hyperprivacy.core.status.MetaCodec
import io.github.hyperprivacy.core.status.ObservedCodec
import io.github.hyperprivacy.core.status.PerfCodec
import io.github.hyperprivacy.core.status.SinkCodec
import io.github.hyperprivacy.core.status.StatusBatch
import io.github.hyperprivacy.core.status.StatusCodec

/**
 * Wire contract between hooked processes and [StatusProvider].
 *
 * One `call(AUTHORITY, METHOD_REPORT, null, extras)` carries up to six tables, each optional:
 * - [KEY_PAYLOAD]: rule states and hit deltas ([io.github.hyperprivacy.core.status.StatusCodec]);
 * - [KEY_SINKS]: per-sink install states ([io.github.hyperprivacy.core.status.SinkCodec]);
 * - [KEY_OBSERVED]: diagnostics endpoints with count deltas
 *   ([io.github.hyperprivacy.core.status.ObservedCodec]);
 * - [KEY_META]: facts about the reporting process: installed versionCode and start time
 *   ([io.github.hyperprivacy.core.status.MetaCodec]). Added later; receivers must not require it;
 * - [KEY_PERF]: per-sink cost of the module's own hooks, sent in diagnostics mode only
 *   ([io.github.hyperprivacy.core.status.PerfCodec]). Added later; receivers must not require it;
 * - [KEY_TRUST]: system_server only, the vendor-trusted signer digests of its package snapshot
 *   ([io.github.hyperprivacy.core.system.AppIndex.trustedSigners]), so the module app's own app
 *   listing classifies vendor apps exactly as system_server does although it sees fewer packages.
 *
 * Each table is decoded only up to [MAX_TABLE_CHARS] characters and a bounded number of rows.
 *
 * A non-null reply means accepted; `null` means the caller was rejected (retrying cannot help).
 *
 * Protected third-party apps hand their rows to system_server instead, in the relay's own sealed
 * format ([io.github.hyperprivacy.core.status.StatusRelay]); system_server forwards them here with
 * its own batches.
 */
internal object StatusContract {
    /** Matches `${applicationId}.status` in the manifest; derived so the two can never drift. */
    const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.status"
    const val METHOD_REPORT = "report"

    /** Bundle key holding a [io.github.hyperprivacy.core.status.StatusCodec] payload. */
    const val KEY_PAYLOAD = "payload"

    /** Bundle key holding a [io.github.hyperprivacy.core.status.SinkCodec] payload. */
    const val KEY_SINKS = "sinks"

    /** Bundle key holding an [io.github.hyperprivacy.core.status.ObservedCodec] payload. */
    const val KEY_OBSERVED = "observed"

    /** Bundle key holding a [io.github.hyperprivacy.core.status.MetaCodec] payload. */
    const val KEY_META = "meta"

    /** Bundle key holding a [io.github.hyperprivacy.core.status.PerfCodec] payload. */
    const val KEY_PERF = "perf"

    /** Bundle key holding system_server's vendor-trusted signer digests, one per line. */
    const val KEY_TRUST = "trust"

    /** Longest table decoded; longer ones are dropped whole. Reporters stay well below it. */
    const val MAX_TABLE_CHARS = 256 * 1024

    private const val MAX_TRUSTED_SIGNERS = 512
    private val SIGNER_DIGEST = Regex("[0-9a-f]{64}")

    fun encode(batch: StatusBatch, trustedSigners: Set<String> = emptySet()): Bundle = Bundle().apply {
        if (batch.reports.isNotEmpty()) putString(KEY_PAYLOAD, StatusCodec.encode(batch.reports))
        if (batch.sinks.isNotEmpty()) putString(KEY_SINKS, SinkCodec.encode(batch.sinks))
        if (batch.observed.isNotEmpty()) putString(KEY_OBSERVED, ObservedCodec.encode(batch.observed))
        if (batch.meta.isNotEmpty()) putString(KEY_META, MetaCodec.encode(batch.meta))
        if (batch.perf.isNotEmpty()) putString(KEY_PERF, PerfCodec.encode(batch.perf))
        if (trustedSigners.isNotEmpty()) putString(KEY_TRUST, trustedSigners.sorted().joinToString("\n"))
    }

    /** Decodes [extras]; malformed rows and tables are dropped, never thrown. */
    fun decode(extras: Bundle?): StatusBatch = StatusBatch(
        reports = StatusCodec.decode(extras.table(KEY_PAYLOAD)),
        sinks = SinkCodec.decode(extras.table(KEY_SINKS)),
        observed = ObservedCodec.decode(extras.table(KEY_OBSERVED)),
        meta = MetaCodec.decode(extras.table(KEY_META)),
        perf = PerfCodec.decode(extras.table(KEY_PERF)),
    )

    /** The trusted signer digests in [extras], `null` when it carries none. */
    fun decodeTrust(extras: Bundle?): Set<String>? = extras.table(KEY_TRUST)
        ?.lineSequence()?.filter(SIGNER_DIGEST::matches)?.take(MAX_TRUSTED_SIGNERS)?.toSet()

    private fun Bundle?.table(key: String): String? = this?.getString(key)?.takeIf { it.length <= MAX_TABLE_CHARS }
}
