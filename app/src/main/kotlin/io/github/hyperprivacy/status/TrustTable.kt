package io.github.hyperprivacy.status

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Vendor-trusted signer digests as system_server last reported them ([StatusContract.KEY_TRUST]),
 * for the module app's own app listing, which sees fewer packages than system_server.
 */
internal class TrustTable(private val prefs: SharedPreferences) {
    /** The digests; empty until system_server has reported. */
    fun signers(): Set<String> = runCatching { prefs.getStringSet(KEY_SIGNERS, null) }.getOrNull().orEmpty()

    @Synchronized
    fun replace(signers: Set<String>) {
        if (signers != signers()) prefs.edit { putStringSet(KEY_SIGNERS, signers) }
    }

    private companion object {
        const val KEY_SIGNERS = "signers"
    }
}
