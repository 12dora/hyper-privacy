package io.github.hyperprivacy.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hyperprivacy.core.config.AppearanceBackup
import io.github.hyperprivacy.core.config.ConfigBackup
import io.github.hyperprivacy.core.tracker.TrackerOrigin
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.trackers.AppScan
import io.github.hyperprivacy.trackers.AppScanner
import io.github.hyperprivacy.trackers.TrackerStore
import io.github.hyperprivacy.ui.theme.Appearance
import io.github.hyperprivacy.ui.theme.AppearanceStore
import io.github.hyperprivacy.ui.theme.DarkMode
import io.github.hyperprivacy.ui.theme.ThemePalette
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What an import did; [skippedProtection] apps could not have protection restored. */
internal sealed interface ImportOutcome {
    data class Done(val skippedProtection: Int) : ImportOutcome

    data object Failed : ImportOutcome
}

/**
 * Export and import of the user's configuration ([ConfigBackup]): the remote preferences' rule
 * switches, per-app options and app-list settings, and the local appearance. Nothing else is read
 * or written; see [ConfigBackup] for what is deliberately left out.
 *
 * Preferences are read and written on [worker]; scanning apps for an import's protection runs on
 * [Dispatchers.IO].
 */
internal class ConfigTransfer(
    context: Context,
    private val worker: CoroutineDispatcher,
    private val prefs: () -> SharedPreferences?,
    private val pendingWrites: PendingWrites,
    private val moduleVersion: () -> String,
    private val onChange: () -> Unit,
) {
    private val appContext = context.applicationContext

    /** The file text of the current configuration, or `null` when the preferences cannot be read. */
    suspend fun export(): String? = withContext(worker) {
        val raw = prefs()?.let { runCatching { it.all }.getOrNull() } ?: return@withContext null
        val look = AppearanceStore.appearance.value ?: Appearance()
        val appearance = AppearanceBackup(look.mode.name, look.palette.name, look.pureBlack)
        ConfigBackup.encode(ConfigBackup.of(raw, appearance), moduleVersion())
    }

    /** Replaces what [backup] covers; see [ConfigBackup.plan]. */
    suspend fun import(backup: ConfigBackup): ImportOutcome = withContext(worker) {
        val target = prefs() ?: return@withContext ImportOutcome.Failed
        val raw = runCatching { target.all }.getOrNull() ?: return@withContext ImportOutcome.Failed
        val scans = withContext(Dispatchers.IO) { scansFor(backup, raw) }
        val plan = backup.plan(raw) { scans[it]?.ids }
        val written = runCatching {
            target.edit {
                plan.removes.forEach(::remove)
                plan.puts.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                    }
                }
                // A restored protection also needs this device's origin hints (relocated ad SDK roots).
                scans.forEach { (pkg, scan) ->
                    val origin = scan?.hints?.encode().orEmpty()
                    if (origin.isNotEmpty() && TrackerProtection.protectKey(pkg) in plan.puts) {
                        putString(TrackerOrigin.originKey(pkg), origin)
                    }
                }
            }
        }
        if (written.isFailure) return@withContext ImportOutcome.Failed
        pendingWrites.clear()
        onChange()
        applyAppearance(backup.appearance)
        ImportOutcome.Done(plan.skippedProtection)
    }

    /** The SDK ids a restored protection starts with: this device's own scan of each installed app. */
    private fun scansFor(backup: ConfigBackup, current: Map<String, *>): Map<String, AppScan?> {
        val wanted = backup.apps.filter { (pkg, app) ->
            app.protect && current[TrackerProtection.protectKey(pkg)] == null
        }.keys
        if (wanted.isEmpty()) return emptyMap()
        val scanner = AppScanner(appContext.packageManager, TrackerStore(appContext))
        val listed = runCatching { scanner.installedUserApps() }.getOrDefault(emptyList())
        val installed = listed.associateBy { it.packageName }
        return wanted.associateWith { pkg -> installed[pkg]?.let { scanner.cached(it) ?: scanner.scan(it) } }
    }

    private fun applyAppearance(backup: AppearanceBackup) {
        if (backup.isEmpty) return
        AppearanceStore.update { now ->
            now.copy(
                mode = DarkMode.entries.firstOrNull { it.name == backup.mode } ?: now.mode,
                palette = ThemePalette.entries.firstOrNull { it.name == backup.palette } ?: now.palette,
                pureBlack = backup.pureBlack ?: now.pureBlack,
            )
        }
    }
}
