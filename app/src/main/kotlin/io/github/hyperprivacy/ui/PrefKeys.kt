package io.github.hyperprivacy.ui

/** Remote preference keys the UI writes besides the per-rule switches. */
internal object PrefKeys {
    /** Diagnostic mode switch. The value must match the key the hooks read (`ConfigCodec` in :core). */
    const val DIAG_OBSERVE = "diag.observe"
}
