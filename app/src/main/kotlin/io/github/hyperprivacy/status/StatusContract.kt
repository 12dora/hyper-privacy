package io.github.hyperprivacy.status

/**
 * Wire contract between hooked processes and [StatusProvider].
 *
 * One `call(AUTHORITY, METHOD_REPORT, null, extras)` carries up to three tables, each optional:
 * - [KEY_PAYLOAD]: rule states and hit deltas ([io.github.hyperprivacy.core.status.StatusCodec]);
 * - [KEY_SINKS]: per-sink install states ([io.github.hyperprivacy.core.status.SinkCodec]);
 * - [KEY_OBSERVED]: diagnostics endpoints with count deltas
 *   ([io.github.hyperprivacy.core.status.ObservedCodec]).
 *
 * A non-null reply means accepted; `null` means the caller was rejected (retrying cannot help).
 */
internal object StatusContract {
    const val AUTHORITY = "io.github.hyperprivacy.status"
    const val METHOD_REPORT = "report"

    /** Bundle key holding a [io.github.hyperprivacy.core.status.StatusCodec] payload. */
    const val KEY_PAYLOAD = "payload"

    /** Bundle key holding a [io.github.hyperprivacy.core.status.SinkCodec] payload. */
    const val KEY_SINKS = "sinks"

    /** Bundle key holding an [io.github.hyperprivacy.core.status.ObservedCodec] payload. */
    const val KEY_OBSERVED = "observed"
}
