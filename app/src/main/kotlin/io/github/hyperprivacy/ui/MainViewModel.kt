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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the UI state. Everything that touches preferences, the status store or the package manager runs
 * on a single background thread; state is rebuilt on request, at most once per [THROTTLE_MS].
 */
internal class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val rebuildRequests = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private val pendingWrites = PendingWrites()
    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> requestRebuild() }

    // Shared with onCleared(), which runs on the main thread.
    private val remotePrefs = AtomicReference<SharedPreferences?>(null)

    @Volatile
    private var backend: Backend? = null

    // Confined to [worker].
    private var boundService: XposedService? = null
    private var framework: String? = null
    private var settled = false
    private var observed: List<ObservedEndpointUi> = emptyList()

    init {
        viewModelScope.launch(worker) {
            val loaded = Backend.create(application, changeListener)
            backend = loaded
            // Bind the current service before the first rebuild so the module never flashes as unreachable.
            bind(ModuleConnection.service.value)
            launch { ModuleConnection.service.collect(::bind) }
            launch {
                delay(SETTLE_MS)
                settled = true
                requestRebuild()
            }
            requestRebuild()
            rebuildRequests.receiveAsFlow().collect {
                rebuild(loaded)
                delay(THROTTLE_MS)
            }
        }
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) = write(ConfigCodec.key(ruleId), enabled)

    fun setDiagnosticMode(enabled: Boolean) = write(ConfigCodec.OBSERVE_KEY, enabled)

    fun resetDefaults() {
        viewModelScope.launch(worker) {
            val prefs = remotePrefs.get() ?: return@launch
            Catalog.rules.forEach { pendingWrites.put(ConfigCodec.key(it.id), it.defaultOn) }
            pendingWrites.put(ConfigCodec.OBSERVE_KEY, ConfigCodec.OBSERVE_DEFAULT)
            requestRebuild()
            runCatching {
                prefs.edit {
                    Catalog.rules.forEach { remove(ConfigCodec.key(it.id)) }
                    remove(ConfigCodec.OBSERVE_KEY)
                }
            }.onFailure {
                pendingWrites.clear()
                requestRebuild()
            }
        }
    }

    /** Reloads the endpoint list; only the list changes, so no full rebuild is needed. */
    fun refreshObserved() {
        viewModelScope.launch(worker) {
            val current = backend?.let { runCatching { it.endpoints.observed() }.getOrNull() } ?: return@launch
            publishObserved(current)
        }
    }

    fun clearObserved() {
        viewModelScope.launch(worker) {
            backend?.let { runCatching { it.store.clearObserved() } }
            publishObserved(emptyList())
        }
    }

    override fun onCleared() {
        backend?.store?.unregisterListener(changeListener)
        remotePrefs.getAndSet(null)?.unregisterOnSharedPreferenceChangeListener(changeListener)
    }

    private fun publishObserved(list: List<ObservedEndpointUi>) {
        if (list == observed) return
        observed = list
        mutableState.update { it.copy(observed = list) }
    }

    private fun requestRebuild() {
        rebuildRequests.trySend(Unit)
    }

    private fun write(key: String, value: Boolean) {
        viewModelScope.launch(worker) {
            val prefs = remotePrefs.get() ?: return@launch
            pendingWrites.put(key, value)
            requestRebuild()
            runCatching { prefs.edit { putBoolean(key, value) } }.onFailure {
                pendingWrites.remove(key)
                requestRebuild()
            }
        }
    }

    private fun bind(service: XposedService?) {
        if (service === boundService && (service == null || remotePrefs.get() != null)) return
        boundService = service
        val prefs = service?.let { runCatching { it.getRemotePreferences(ConfigCodec.PREFS_GROUP) }.getOrNull() }
        remotePrefs.getAndSet(prefs)?.unregisterOnSharedPreferenceChangeListener(changeListener)
        prefs?.registerOnSharedPreferenceChangeListener(changeListener)
        framework = service?.let { runCatching { "${it.frameworkName} ${it.frameworkVersion}" }.getOrNull() }
        requestRebuild()
    }

    private fun rebuild(backend: Backend) {
        val prefs = remotePrefs.get()
        val stored = prefs?.let { runCatching { it.all }.getOrNull() }.orEmpty()
        val snapshot = backend.view.snapshot { rule ->
            pendingWrites.resolve(stored, ConfigCodec.key(rule.id), rule.defaultOn)
        }
        mutableState.value = UiState(
            loaded = true,
            module = resolveModuleState(
                bound = boundService != null,
                prefsReachable = prefs != null,
                settled = settled,
                snapshot = snapshot,
            ),
            framework = framework,
            versionName = backend.versionName,
            byCategory = snapshot.byCategory,
            byApp = snapshot.byApp,
            rules = snapshot.rules,
            enabledCount = snapshot.enabledCount,
            totalHits = snapshot.totalHits,
            diagnosticMode = pendingWrites.resolve(stored, ConfigCodec.OBSERVE_KEY, ConfigCodec.OBSERVE_DEFAULT),
            observed = observed,
        )
    }

    private companion object {
        const val THROTTLE_MS = 200L
        const val SETTLE_MS = 1_000L
    }
}

/** Objects that must not be created on the main thread. */
private class Backend(val store: StatusStore, val view: RuleCatalogView, val versionName: String) {
    val endpoints: ObservedEndpointsSource = StatusStoreEndpoints(store, view.apps)

    companion object {
        fun create(application: Application, listener: SharedPreferences.OnSharedPreferenceChangeListener): Backend {
            val store = StatusStore(application)
            store.registerListener(listener)
            val apps = InstalledApps.load(application.packageManager)
            val version = runCatching {
                application.packageManager.getPackageInfo(application.packageName, 0).versionName
            }.getOrNull().orEmpty()
            return Backend(store, RuleCatalogView(store, apps), version)
        }
    }
}

internal fun resolveModuleState(
    bound: Boolean,
    prefsReachable: Boolean,
    settled: Boolean,
    snapshot: RuleSnapshot,
): ModuleState = when {
    !bound -> if (settled) ModuleState.INACTIVE else ModuleState.CHECKING
    !prefsReachable -> ModuleState.UNAVAILABLE
    snapshot.enabledCount > 0 && !snapshot.anyReported -> ModuleState.NEEDS_REBOOT
    else -> ModuleState.ACTIVE
}
