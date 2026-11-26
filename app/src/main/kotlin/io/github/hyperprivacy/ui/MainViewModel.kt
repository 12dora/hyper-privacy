package io.github.hyperprivacy.ui

import android.app.Application
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hyperprivacy.ModuleConnection
import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.status.DisplayStatus
import io.github.hyperprivacy.status.StatusStore
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val statusStore = StatusStore(application)
    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private var labels: Map<String, String> = emptyMap()
    private var remotePrefs: SharedPreferences? = null

    private val refreshListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    init {
        statusStore.registerListener(refreshListener)
        viewModelScope.launch {
            labels = withContext(Dispatchers.Default) { loadLabels(application.packageManager) }
            refresh()
        }
        viewModelScope.launch {
            ModuleConnection.service.collect { service -> bind(service) }
        }
    }

    fun setEnabled(ruleId: String, enabled: Boolean) {
        val prefs = remotePrefs ?: return
        prefs.edit { putBoolean(ConfigCodec.key(ruleId), enabled) }
    }

    override fun onCleared() {
        statusStore.unregisterListener(refreshListener)
        remotePrefs?.unregisterOnSharedPreferenceChangeListener(refreshListener)
    }

    private fun bind(service: XposedService?) {
        remotePrefs?.unregisterOnSharedPreferenceChangeListener(refreshListener)
        remotePrefs = service?.let { runCatching { it.getRemotePreferences(ConfigCodec.PREFS_GROUP) }.getOrNull() }
        remotePrefs?.registerOnSharedPreferenceChangeListener(refreshListener)
        refresh(service)
    }

    private fun refresh(service: XposedService? = ModuleConnection.service.value) {
        val switches = ConfigCodec.decode(remotePrefs?.all.orEmpty())
        mutableState.value = UiState(
            module = service?.toModuleState() ?: ModuleState.Inactive,
            groups = groupRules(switches),
        )
    }

    private fun groupRules(switches: Map<String, Boolean>): List<RuleGroup> = Catalog.rules
        .groupBy { it.packages.singleOrNull() ?: MULTI_APP_KEY }
        .map { (key, rules) ->
            RuleGroup(key, if (key == MULTI_APP_KEY) null else labels[key] ?: key, rules.map { it.toItem(switches) })
        }

    private fun Rule.toItem(switches: Map<String, Boolean>): RuleItem {
        val statuses = packages.mapNotNull { statusStore.get(it, id) }.filter { it.isFromCurrentBoot }
        return RuleItem(
            id = id,
            title = title,
            summary = summary,
            enabled = switches[id] ?: defaultOn,
            status = DisplayStatus.of(statuses.map { it.state }),
            hits = packages.sumOf { statusStore.get(it, id)?.totalHits ?: 0 },
        )
    }

    private fun XposedService.toModuleState(): ModuleState = runCatching {
        ModuleState.Active(frameworkName, frameworkVersion, apiVersion)
    }.getOrDefault(ModuleState.Inactive)

    private companion object {
        const val MULTI_APP_KEY = "*"

        fun loadLabels(pm: PackageManager): Map<String, String> = Catalog.allPackages.associateWith { pkg ->
            runCatching {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString()
            }.getOrDefault(pkg)
        }
    }
}
