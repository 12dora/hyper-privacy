package io.github.hyperprivacy.ui

import android.net.Uri
import io.github.hyperprivacy.core.config.ConfigBackup
import io.github.hyperprivacy.core.config.ProtectionPause
import io.github.hyperprivacy.ui.apps.AppSwitch
import io.github.hyperprivacy.ui.apps.AppsUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * What the screens read and call. [MainViewModel] backs it with the remote preferences, the status
 * store and LSPosed.
 */

/** The LSPosed scope of the catalog's system apps. */
internal interface ScopeModel {
    /** One-shot request outcomes. */
    val messages: Flow<ScopeResult>

    /** Asks LSPosed to add the missing entries; only ever on a user tap. */
    fun request()
}

/** The global pause switch. */
internal interface PauseModel {
    fun pause(option: ProtectionPause.Option)

    fun resume()
}

/** The hint about an overlapping module. */
internal interface ConflictModel {
    /** Turns off this module's app-list hiding; the result undoes it, `null` when nothing changed. */
    suspend fun disableAppListHiding(): AppListRestore?

    fun restoreAppListHiding(restore: AppListRestore)

    fun dismiss()
}

/** User-installed apps and their protection. */
internal interface AppsModel {
    val state: StateFlow<AppsUi>

    /** One-shot scope request outcomes of per-app protection. */
    val messages: Flow<ScopeResult>

    /** Lists and scans apps the first time an Apps page is shown; later calls do nothing. */
    fun start()

    fun setProtected(packageName: String, on: Boolean)

    fun retryScope(packageName: String)

    fun set(switch: AppSwitch, on: Boolean)
}

/** Configuration export and import. */
internal interface BackupModel {
    /** A file read and checked, waiting for the user to confirm. */
    val pendingImport: StateFlow<ConfigBackup?>

    val importing: StateFlow<Boolean>

    /** The file text of the current configuration, or `null` when it cannot be read. */
    suspend fun export(): String?

    fun read(uri: Uri)

    fun confirm()

    fun dismiss()
}

internal interface UiModel {
    val state: StateFlow<UiState>
    val device: ScopeModel
    val trackers: AppsModel
    val pause: PauseModel
    val conflicts: ConflictModel
    val config: BackupModel

    fun setRulesEnabled(ruleIds: Collection<String>, enabled: Boolean)

    fun setSwitch(switch: ModuleSwitch, enabled: Boolean)

    fun resetDefaults()

    fun refreshObserved()

    fun clearObserved()

    suspend fun diagnosticReport(includeUserApps: Boolean?): ReportOutcome
}
