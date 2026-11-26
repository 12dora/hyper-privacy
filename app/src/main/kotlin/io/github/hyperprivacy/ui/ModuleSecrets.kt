package io.github.hyperprivacy.ui

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hyperprivacy.core.config.ModuleSecret
import java.security.SecureRandom

/**
 * Writes the module secret ([ModuleSecret]) into the remote preferences once, the first time the
 * framework service is bound. Hooked processes derive per-app identifiers from it and leave them
 * untouched until it exists. An existing valid secret is never replaced. Blocking; call off the
 * main thread.
 */
internal fun ensureModuleSecret(prefs: SharedPreferences) {
    runCatching {
        if (ModuleSecret.parse(prefs.getString(ModuleSecret.KEY, null)) != null) return
        val bytes = ByteArray(ModuleSecret.BYTES).also(SecureRandom()::nextBytes)
        prefs.edit { putString(ModuleSecret.KEY, ModuleSecret.encode(bytes)) }
    }
}
