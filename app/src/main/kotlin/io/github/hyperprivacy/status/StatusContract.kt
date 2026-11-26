package io.github.hyperprivacy.status

/** Wire contract between hooked processes and [StatusProvider]. */
internal object StatusContract {
    const val AUTHORITY = "io.github.hyperprivacy.status"
    const val METHOD_REPORT = "report"

    /** Bundle key holding a [io.github.hyperprivacy.core.status.StatusCodec] payload. */
    const val KEY_PAYLOAD = "payload"
}
