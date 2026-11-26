package io.github.hyperprivacy.ui

import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hyperprivacy.ModuleConnection
import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.status.StatusStore
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the UI state. All work that touches preferences, the status store or the package manager runs
 * on a single background thread; state is rebuilt on request, at most once per [THROTTLE_MS].
 */
internal class MainViewModel(application: Application, private val endpoints: ObservedEndpointsSource) :
    AndroidViewModel(application) {
    /** Production constructor used by the default ViewModel factory. */
    constructor(application: Application) : this(application, EmptyObservedEndpointsSource)

    private val statusStore = StatusStore(application)
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val rebuildRequests = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private val versionName: String = runCatching {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName
    }.getOrNull().orEmpty()

    // Confined to [worker].
    private var catalogView: RuleCatalogView? = null
    private var remotePrefs: SharedPreferences? = null
    private var framework: String? = null
    private var settled = false
    private var observed: List<ObservedEndpointUi> = emptyList()

    /** Writes not yet visible in the preferences, so a toggled switch reflects the tap immediately. */
    private val pendingWrites = ConcurrentHashMap<String, Boolean>()

    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> requestRebuild() }

    init {
        statusStore.registerListener(changeListener)
        viewModelScope.launch(worker) {
            catalogView = RuleCatalogView(statusStore, InstalledApps.load(application.packageManager))
            launch { ModuleConnection.service.collect(::bind) }
            launch {
                delay(SETTLE_MS)
                settled = true
                requestRebuild()
            }
            requestRebuild()
            rebuildRequests.receiveAsFlow().collect {
                rebuild()
                delay(THROTTLE_MS)
            }
        }
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) = write(ConfigCodec.key(ruleId), enabled)

    fun setDiagnosticMode(enabled: Boolean) = write(PrefKeys.DIAG_OBSERVE, enabled)

    fun resetDefaults() {
        viewModelScope.launch(worker) {
            val prefs = remotePrefs ?: return@launch
            Catalog.rules.forEach { pendingWrites[ConfigCodec.key(it.id)] = it.defaultOn }
            pendingWrites[PrefKeys.DIAG_OBSERVE] = false
            requestRebuild()
            runCatching {
                prefs.edit {
                    Catalog.rules.forEach { remove(ConfigCodec.key(it.id)) }
                    remove(PrefKeys.DIAG_OBSERVE)
                }
            }.onFailure { pendingWrites.clear() }
        }
    }

    fun refreshObserved() {
        viewModelScope.launch(worker) {
            observed = runCatching { endpoints.observed() }.getOrDefault(emptyList())
            requestRebuild()
        }
    }

    override fun onCleared() {
        statusStore.unregisterListener(changeListener)
        remotePrefs?.unregisterOnSharedPreferenceChangeListener(changeListener)
    }

    private fun requestRebuild() {
        rebuildRequests.trySend(Unit)
    }

    private fun write(key: String, value: Boolean) {
        viewModelScope.launch(worker) {
            val prefs = remotePrefs ?: return@launch
            pendingWrites[key] = value
            requestRebuild()
            runCatching { prefs.edit { putBoolean(key, value) } }.onFailure {
                pendingWrites.remove(key)
                requestRebuild()
            }
        }
    }

    private fun bind(service: XposedService?) {
        remotePrefs?.unregisterOnSharedPreferenceChangeListener(changeListener)
        remotePrefs = service?.let { runCatching { it.getRemotePreferences(ConfigCodec.PREFS_GROUP) }.getOrNull() }
        remotePrefs?.registerOnSharedPreferenceChangeListener(changeListener)
        framework = service?.let { runCatching { "${it.frameworkName} ${it.frameworkVersion}" }.getOrNull() }
        requestRebuild()
    }

    private fun rebuild() {
        val view = catalogView ?: return
        val prefs = remotePrefs
        val stored = prefs?.let { runCatching { it.all }.getOrNull() }.orEmpty()
        val snapshot = view.snapshot { rule -> flag(stored, ConfigCodec.key(rule.id), rule.defaultOn) }
        mutableState.value = UiState(
            module = moduleState(prefs != null, snapshot),
            framework = framework,
            versionName = versionName,
            byCategory = snapshot.byCategory,
            byApp = snapshot.byApp,
            rules = snapshot.rules,
            enabledCount = snapshot.enabledCount,
            totalHits = snapshot.totalHits,
            diagnosticMode = flag(stored, PrefKeys.DIAG_OBSERVE, false),
            observed = observed,
        )
    }

    /** The stored value, unless a write of ours is still on its way. */
    private fun flag(stored: Map<String, *>, key: String, default: Boolean): Boolean {
        val current = stored[key] as? Boolean ?: default
        val pending = pendingWrites[key] ?: return current
        if (pending == current) pendingWrites.remove(key)
        return pending
    }

    private fun moduleState(prefsReachable: Boolean, snapshot: RuleSnapshot): ModuleState = when {
        ModuleConnection.service.value == null -> if (settled) ModuleState.INACTIVE else ModuleState.CHECKING
        !prefsReachable -> ModuleState.UNAVAILABLE
        snapshot.enabledCount > 0 && !snapshot.anyReported -> ModuleState.NEEDS_REBOOT
        else -> ModuleState.ACTIVE
    }

    private companion object {
        const val THROTTLE_MS = 200L
        const val SETTLE_MS = 1_000L
    }
}
