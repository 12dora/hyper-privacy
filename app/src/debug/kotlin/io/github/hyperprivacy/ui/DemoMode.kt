package io.github.hyperprivacy.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.hyperprivacy.core.config.ConfigBackup
import io.github.hyperprivacy.core.config.ProtectionPause
import io.github.hyperprivacy.core.system.AppListConfig
import io.github.hyperprivacy.ui.apps.AppSwitch
import io.github.hyperprivacy.ui.apps.AppsUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.util.Locale

/*
 * Demo mode, debug builds only (this source set): realistic sample data for screenshots on a device
 * or emulator without LSPosed. Started by launching MainActivity with the boolean extra `demo`
 * (`demo_paused` as well shows the paused banner). The demo model never binds the framework, never
 * opens the module preferences and never scans apps; switches change only its in-memory state.
 */

private const val EXTRA_DEMO = "demo"
private const val EXTRA_PAUSED = "demo_paused"
private const val PAUSE_DEMO_MS = 45 * 60_000L

/** A boolean extra, also accepted as the string "true" (`am start -e` instead of `--ez`). */
private fun Intent.flag(name: String): Boolean = getBooleanExtra(name, false) || getStringExtra(name) == "true"

internal val demoFactory: DemoFactory? = DemoFactory { activity ->
    val intent = activity.intent
    if (!intent.flag(EXTRA_DEMO)) return@DemoFactory null
    val paused = intent.flag(EXTRA_PAUSED)
    val factory = viewModelFactory { initializer { DemoModel(paused) } }
    ViewModelProvider(activity, factory)[DemoModel::class.java]
}

/** Sample-data [UiModel]; kept in a view model so rotation keeps the demo's switches. */
internal class DemoModel(paused: Boolean) :
    ViewModel(),
    UiModel {
    private val data = DemoData(Locale.getDefault().toLanguageTag())
    private val switches = DemoSwitches(
        pausedUntil = if (paused) System.currentTimeMillis() + PAUSE_DEMO_MS else 0L,
    )
    private val mutableState = MutableStateFlow(data.state(switches))
    private val mutableApps = MutableStateFlow(data.apps(switches))

    override val state: StateFlow<UiState> = mutableState.asStateFlow()

    override val device: ScopeModel = object : ScopeModel {
        override val messages: Flow<ScopeResult> = emptyFlow()

        override fun request() = Unit
    }

    override val pause: PauseModel = object : PauseModel {
        override fun pause(option: ProtectionPause.Option) = update {
            pausedUntil = ProtectionPause.deadline(option, System.currentTimeMillis())
        }

        override fun resume() = update { pausedUntil = 0L }
    }

    override val conflicts: ConflictModel = object : ConflictModel {
        override suspend fun disableAppListHiding(): AppListRestore {
            update { selfHidden = false }
            return AppListRestore(emptySet(), selfHide = true)
        }

        override fun restoreAppListHiding(restore: AppListRestore) = update { selfHidden = restore.selfHide ?: true }

        override fun dismiss() = update { conflictDismissed = true }

        override fun restoreHint() = update { conflictDismissed = false }

        override fun loadScopes() = Unit
    }

    override val trackers: AppsModel = object : AppsModel {
        override val state: StateFlow<AppsUi> = mutableApps.asStateFlow()
        override val messages: Flow<ScopeResult> = emptyFlow()

        override fun start() = Unit

        override fun setProtected(packageName: String, on: Boolean) = update { data.setProtected(this, packageName, on) }

        override fun retryScope(packageName: String) = Unit

        override fun set(switch: AppSwitch, on: Boolean) = update { appSwitches[switch.key] = on }

        override val allowlist: AllowlistModel = object : AllowlistModel {
            override fun setAllowed(packageName: String, on: Boolean) = update {
                val current = allowlist ?: AppListConfig.DEFAULT_ALLOWLIST
                allowlist = if (on) current + packageName else current - packageName
            }

            override fun reset() = update { allowlist = null }
        }
    }

    override val config: BackupModel = object : BackupModel {
        override val pendingImport: StateFlow<ConfigBackup?> = MutableStateFlow(null)
        override val importing: StateFlow<Boolean> = MutableStateFlow(false)

        override suspend fun export(): String? = null

        override fun read(uri: Uri) = Unit

        override fun confirm() = Unit

        override fun dismiss() = Unit
    }

    override fun setRulesEnabled(ruleIds: Collection<String>, enabled: Boolean) = update {
        ruleIds.forEach { rules[it] = enabled }
    }

    override fun setSwitch(switch: ModuleSwitch, enabled: Boolean) = update {
        when (switch) {
            ModuleSwitch.DIAGNOSTICS -> diagnosticMode = enabled
            ModuleSwitch.SELF_HIDE -> selfHidden = enabled
        }
    }

    override fun resetDefaults() = update {
        rules.clear()
        appSwitches.clear()
        allowlist = null
        pausedUntil = 0L
        diagnosticMode = false
        selfHidden = true
    }

    override fun refreshObserved() = Unit

    override fun clearObserved() = update { observedCleared = true }

    override suspend fun diagnosticReport(includeUserApps: Boolean?): ReportOutcome = ReportOutcome.Failed

    /** Switches change on the main thread; the sample data is small enough to rebuild at once. */
    private fun update(change: DemoSwitches.() -> Unit) {
        switches.change()
        mutableState.value = data.state(switches)
        mutableApps.value = data.apps(switches)
    }
}
