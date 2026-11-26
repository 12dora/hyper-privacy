package io.github.hyperprivacy.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.core.content.edit
import io.github.hyperprivacy.BuildConfig
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.config.AppearanceBackup
import io.github.hyperprivacy.core.config.BackupProblem
import io.github.hyperprivacy.core.config.ConfigBackup
import io.github.hyperprivacy.core.config.ParsedBackup
import io.github.hyperprivacy.core.tracker.TrackerOrigin
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.trackers.AppScan
import io.github.hyperprivacy.trackers.AppScanner
import io.github.hyperprivacy.trackers.TrackerStore
import io.github.hyperprivacy.trackers.UserApp
import io.github.hyperprivacy.ui.theme.Appearance
import io.github.hyperprivacy.ui.theme.AppearanceStore
import io.github.hyperprivacy.ui.theme.DarkMode
import io.github.hyperprivacy.ui.theme.ThemePalette
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What an import did; [skipped] apps in the file got no options here (not installed, or not scannable). */
internal sealed interface ImportOutcome {
    data class Done(val skipped: Int) : ImportOutcome

    data object Failed : ImportOutcome
}

/**
 * Export and import of the user's configuration ([ConfigBackup]): the remote preferences' rule
 * switches, per-app options and app-list settings, and the local appearance. Nothing else is read
 * or written; see [ConfigBackup] for what is deliberately left out.
 *
 * An import runs in the view model's [scope], not the screen's, so rotating or leaving Settings
 * neither cancels nor repeats it. The file waiting for confirmation ([pendingImport]) and the
 * running import ([importing]) survive the same way, and the outcome is a toast wherever the user
 * is. One import at a time.
 *
 * Per-app options are only written for third-party apps installed on this device as the Apps page
 * lists them ([AppScanner.installedUserApps]), minus the default home app; see [ConfigBackup.plan].
 *
 * Preferences are read and written on [worker]; reading the file and scanning apps run on
 * [Dispatchers.IO].
 */
internal class ConfigTransfer(
    context: Context,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val prefs: () -> SharedPreferences?,
    private val pendingWrites: PendingWrites,
    private val clearAppsPending: () -> Unit,
    private val moduleVersion: () -> String,
    private val onChange: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val mutablePending = MutableStateFlow<ConfigBackup?>(null)
    private val mutableImporting = MutableStateFlow(false)

    /** A file read and checked, waiting for the user to confirm. */
    val pendingImport: StateFlow<ConfigBackup?> = mutablePending.asStateFlow()

    /** An import is being applied (apps may be scanned first, which takes a moment). */
    val importing: StateFlow<Boolean> = mutableImporting.asStateFlow()

    /** The file text of the current configuration, or `null` when the preferences cannot be read. */
    suspend fun export(): String? = withContext(worker) {
        val raw = prefs()?.let { runCatching { it.all }.getOrNull() } ?: return@withContext null
        val look = AppearanceStore.appearance.value ?: Appearance()
        val appearance = AppearanceBackup(look.mode.name, look.palette.name, look.pureBlack)
        ConfigBackup.encode(ConfigBackup.of(raw, appearance), moduleVersion())
    }

    /** Reads the file the user picked: a valid one waits in [pendingImport], anything else is reported. */
    fun read(uri: Uri) {
        scope.launch {
            when (val parsed = withContext(Dispatchers.IO) { readConfig(uri) }) {
                is ParsedBackup.Ok -> mutablePending.value = parsed.backup
                is ParsedBackup.Rejected -> toast(parsed.problem.messageRes())
                null -> toast(R.string.backup_unreadable)
            }
        }
    }

    fun dismiss() {
        mutablePending.value = null
    }

    /** Applies [pendingImport]; ignored while another import runs. */
    fun confirm() {
        val backup = mutablePending.value ?: return
        if (!mutableImporting.compareAndSet(expect = false, update = true)) return
        mutablePending.value = null
        scope.launch {
            val outcome = try {
                import(backup)
            } finally {
                mutableImporting.value = false
            }
            toast(outcome.messageRes())
        }
    }

    /** Replaces what [backup] covers; see [ConfigBackup.plan]. */
    private suspend fun import(backup: ConfigBackup): ImportOutcome = withContext(worker) {
        val target = prefs() ?: return@withContext ImportOutcome.Failed
        val raw = runCatching { target.all }.getOrNull() ?: return@withContext ImportOutcome.Failed
        val device = withContext(Dispatchers.IO) { DeviceApps.load(appContext) }
        val scans = withContext(Dispatchers.IO) { scansFor(backup, raw, device) }
        val plan = backup.plan(raw, BuildConfig.APPLICATION_ID, { it in device.configurable }) { scans[it]?.ids }
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
        clearAppsPending()
        onChange()
        applyAppearance(backup.appearance)
        ImportOutcome.Done(plan.skippedProtection + plan.skippedApps)
    }

    /** The SDK ids a restored protection starts with: this device's own scan of each installed app. */
    private fun scansFor(backup: ConfigBackup, current: Map<String, *>, device: DeviceApps): Map<String, AppScan?> {
        val wanted = backup.apps.filter { (pkg, app) ->
            app.protect && pkg in device.configurable && current[TrackerProtection.protectKey(pkg)] == null
        }.keys
        if (wanted.isEmpty()) return emptyMap()
        val scanner = device.scanner
        return wanted.associateWith { pkg -> device.apps[pkg]?.let { scanner.cached(it) ?: scanner.scan(it) } }
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

    /** The parsed file, or `null` when it cannot be read; anything over [MAX_FILE_BYTES] is not a backup. */
    private fun readConfig(uri: Uri): ParsedBackup? {
        val bytes = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_FILE_BYTES + 1) }
        }.getOrNull() ?: return null
        if (bytes.size > MAX_FILE_BYTES) return ParsedBackup.Rejected(BackupProblem.NOT_A_BACKUP)
        return ConfigBackup.parse(bytes.toString(Charsets.UTF_8))
    }

    private suspend fun toast(message: Int) = withContext(Dispatchers.Main) {
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MAX_FILE_BYTES = 1 shl 20
    }
}

private fun BackupProblem.messageRes(): Int = when (this) {
    BackupProblem.NOT_A_BACKUP -> R.string.backup_invalid
    BackupProblem.NEWER_VERSION -> R.string.backup_newer
}

private fun ImportOutcome.messageRes(): Int = when (this) {
    is ImportOutcome.Done -> if (skipped > 0) R.string.backup_imported_partial else R.string.backup_imported
    ImportOutcome.Failed -> R.string.backup_import_failed
}

/**
 * The apps of this device an import may configure: the third-party apps the Apps page lists,
 * without the current default home app (a third-party launcher is listed there too).
 */
private class DeviceApps(val scanner: AppScanner, val apps: Map<String, UserApp>, val configurable: Set<String>) {
    companion object {
        // The home intent resolves through the launcher <queries>; no package visibility is widened.
        @SuppressLint("QueryPermissionsNeeded")
        fun load(context: Context): DeviceApps {
            val pm = context.packageManager
            val scanner = AppScanner(pm, TrackerStore(context))
            val listed = runCatching { scanner.installedUserApps() }.getOrDefault(emptyList())
                .associateBy { it.packageName }
            val home = runCatching {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                pm.resolveActivity(intent, flags)?.activityInfo?.packageName
            }.getOrNull()
            return DeviceApps(scanner, listed, listed.keys - setOfNotNull(home))
        }
    }
}
