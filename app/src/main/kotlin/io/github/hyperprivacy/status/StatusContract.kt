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
 * One `call(AUTHORITY, METHOD_REPORT, null, extras)` carries up to five tables, each optional:
 * - [KEY_PAYLOAD]: rule states and hit deltas ([io.github.hyperprivacy.core.status.StatusCodec]);
 * - [KEY_SINKS]: per-sink install states ([io.github.hyperprivacy.core.status.SinkCodec]);
 * - [KEY_OBSERVED]: diagnostics endpoints with count deltas
 *   ([io.github.hyperprivacy.core.status.ObservedCodec]);
 * - [KEY_META]: facts about the reporting process: installed versionCode and start time
 *   ([io.github.hyperprivacy.core.status.MetaCodec]). Added later; receivers must not require it;
 * - [KEY_PERF]: per-sink cost of the module's own hooks, sent in diagnostics mode only
 *   ([io.github.hyperprivacy.core.status.PerfCodec]). Added later; receivers must not require it.
 *
 * A non-null reply means accepted; `null` means the caller was rejected (retrying cannot help).
 *
 * Protected third-party apps send the same extras to system_server instead
 * ([io.github.hyperprivacy.core.status.StatusRelay]), which forwards them with its own batches.
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

    fun encode(batch: StatusBatch): Bundle = Bundle().apply {
        if (batch.reports.isNotEmpty()) putString(KEY_PAYLOAD, StatusCodec.encode(batch.reports))
        if (batch.sinks.isNotEmpty()) putString(KEY_SINKS, SinkCodec.encode(batch.sinks))
        if (batch.observed.isNotEmpty()) putString(KEY_OBSERVED, ObservedCodec.encode(batch.observed))
        if (batch.meta.isNotEmpty()) putString(KEY_META, MetaCodec.encode(batch.meta))
        if (batch.perf.isNotEmpty()) putString(KEY_PERF, PerfCodec.encode(batch.perf))
    }

    /** Decodes [extras]; malformed rows and tables are dropped, never thrown. */
    fun decode(extras: Bundle?): StatusBatch = StatusBatch(
        reports = StatusCodec.decode(extras?.getString(KEY_PAYLOAD)),
        sinks = SinkCodec.decode(extras?.getString(KEY_SINKS)),
        observed = ObservedCodec.decode(extras?.getString(KEY_OBSERVED)),
        meta = MetaCodec.decode(extras?.getString(KEY_META)),
        perf = PerfCodec.decode(extras?.getString(KEY_PERF)),
    )
}
