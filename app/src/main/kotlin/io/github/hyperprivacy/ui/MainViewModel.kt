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
import io.github.hyperprivacy.ui.apps.TrackerController
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
import kotlinx.coroutines.withContext
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
    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        rebuildRequests.trySend(
            Unit,
        )
    }

    // Shared with onCleared(), which runs on the main thread.
    private val remotePrefs = AtomicReference<SharedPreferences?>(null)

    @Volatile
    private var backend: Backend? = null

    // Confined to [worker].
    private var boundService: XposedService? = null
    private var framework: String? = null
    private var settled = false
    private var observed: List<ObservedEndpointUi> = emptyList()

    /** Installed apps and LSPosed scope: refreshed on resume, scope requested on a user tap. */
    val device = ScopeSync(
        scope = viewModelScope,
        worker = worker,
        rebootFlag = RebootFlag(application),
        service = { boundService },
        installed = { backend?.view?.apps?.packages },
        refreshApps = { backend?.refreshApps() },
        onChange = { rebuildRequests.trySend(Unit) },
    )

    /** User-installed apps and tracker protection; starts when the Apps page is first opened. */
    val trackers = TrackerController(
        context = application,
        scope = viewModelScope,
        worker = worker,
        prefs = { remotePrefs.get() },
        service = { boundService },
    )

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
                rebuildRequests.trySend(Unit)
            }
            rebuildRequests.trySend(Unit)
            rebuildRequests.receiveAsFlow().collect {
                rebuild(loaded)
                delay(THROTTLE_MS)
            }
        }
    }

    /** Switches one or more rules in a single preferences commit (a row switch, or a category bulk switch). */
    fun setRulesEnabled(ruleIds: Collection<String>, enabled: Boolean) = write(ruleIds.map(ConfigCodec::key), enabled)

    /** The Markdown diagnostic report; built on the worker, which reads preferences and the package manager. */
    suspend fun diagnosticReport(): String? = withContext(worker) {
        val loaded = backend ?: return@withContext null
        val stored = remotePrefs.get()?.let { runCatching { it.all }.getOrNull() }.orEmpty()
        runCatching {
            loaded.health.report(loaded.versionName) { rule ->
                pendingWrites.resolve(stored, ConfigCodec.key(rule.id), rule.defaultOn)
            }
        }.getOrNull()
    }

    fun setDiagnosticMode(enabled: Boolean) = write(listOf(ConfigCodec.OBSERVE_KEY), enabled)

    fun resetDefaults() {
        viewModelScope.launch(worker) {
            val prefs = remotePrefs.get() ?: return@launch
            val rules = backend?.view?.rules ?: Catalog.rules
            rules.forEach { pendingWrites.put(ConfigCodec.key(it.id), it.defaultOn) }
            pendingWrites.put(ConfigCodec.OBSERVE_KEY, ConfigCodec.OBSERVE_DEFAULT)
            rebuildRequests.trySend(Unit)
            runCatching {
                prefs.edit {
                    rules.forEach { remove(ConfigCodec.key(it.id)) }
                    remove(ConfigCodec.OBSERVE_KEY)
                }
            }.onFailure {
                pendingWrites.clear()
                rebuildRequests.trySend(Unit)
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

    private fun write(keys: List<String>, value: Boolean) {
        viewModelScope.launch(worker) {
            val prefs = remotePrefs.get() ?: return@launch
            keys.forEach { pendingWrites.put(it, value) }
            rebuildRequests.trySend(Unit)
            runCatching { prefs.edit { keys.forEach { putBoolean(it, value) } } }.onFailure {
                keys.forEach(pendingWrites::remove)
                rebuildRequests.trySend(Unit)
            }
        }
    }

    private fun bind(service: XposedService?) {
        if (service === boundService && (service == null || remotePrefs.get() != null)) return
        boundService = service
        val prefs = service?.let { runCatching { it.getRemotePreferences(ConfigCodec.PREFS_GROUP) }.getOrNull() }
        remotePrefs.getAndSet(prefs)?.unregisterOnSharedPreferenceChangeListener(changeListener)
        prefs?.registerOnSharedPreferenceChangeListener(changeListener)
        prefs?.let { viewModelScope.launch(worker) { ensureModuleSecret(it) } }
        framework = service?.let { runCatching { "${it.frameworkName} ${it.frameworkVersion}" }.getOrNull() }
        device.check()
        trackers.refresh()
        rebuildRequests.trySend(Unit)
    }

    private fun rebuild(backend: Backend) {
        trackers.onPreferencesChanged()
        val prefs = remotePrefs.get()
        val stored = prefs?.let { runCatching { it.all }.getOrNull() }.orEmpty()
        val snapshot = backend.view.snapshot { rule ->
            pendingWrites.resolve(stored, ConfigCodec.key(rule.id), rule.defaultOn)
        }
        val suspected = runCatching { backend.health.suspected() }.getOrDefault(emptyList())
            .sortedByDescending { it.lastSeen }
            .map { it.toUi(backend.view.apps) }
        mutableState.value = UiState(
            loaded = true,
            module = resolveModuleState(
                bound = boundService != null,
                prefsReachable = prefs != null,
                settled = settled,
                snapshot = snapshot,
                rebootPending = device.rebootPending,
            ),
            framework = framework,
            versionName = backend.versionName,
            byCategory = snapshot.byCategory,
            apps = snapshot.apps,
            rules = snapshot.rules,
            enabledCount = snapshot.enabledCount,
            totalHits = snapshot.totalHits,
            attention = attentionOf(snapshot.rules, suspected.size),
            suspected = suspected,
            diagnosticMode = pendingWrites.resolve(stored, ConfigCodec.OBSERVE_KEY, ConfigCodec.OBSERVE_DEFAULT),
            observed = observed,
            scope = device.ui,
        )
    }

    private companion object {
        const val THROTTLE_MS = 200L
        const val SETTLE_MS = 1_000L
    }
}

/** Objects that must not be created on the main thread. */
private class Backend(
    val store: StatusStore,
    val health: RuleHealthSource,
    view: RuleCatalogView,
    val versionName: String,
) {
    /** Rebuilt by [refreshApps] when apps are installed or removed; written on the worker only. */
    @Volatile
    var view: RuleCatalogView = view
        private set

    val endpoints: ObservedEndpointsSource get() = StatusStoreEndpoints(store, view.apps)

    /** Reloads the installed target apps; cheap (one package manager call) when nothing changed. */
    fun refreshApps() {
        val pm = store.packageManager
        val installed = InstalledApps.installed(pm, Catalog.allPackages)
        if (view.apps.matches(installed)) return
        view = RuleCatalogView(store, health, InstalledApps.load(pm, installed), Catalog.rules)
    }

    companion object {
        fun create(application: Application, listener: SharedPreferences.OnSharedPreferenceChangeListener): Backend {
            val store = StatusStore(application)
            store.registerListener(listener)
            val health: RuleHealthSource = StoreHealthSource(store)
            val pm = application.packageManager
            val apps = InstalledApps.load(pm, InstalledApps.installed(pm, Catalog.allPackages))
            val version = runCatching { pm.getPackageInfo(application.packageName, 0).versionName }
                .getOrNull().orEmpty()
            return Backend(store, health, RuleCatalogView(store, health, apps, Catalog.rules), version)
        }
    }
}

internal fun resolveModuleState(
    bound: Boolean,
    prefsReachable: Boolean,
    settled: Boolean,
    snapshot: RuleSnapshot,
    rebootPending: Boolean = false,
): ModuleState = when {
    !bound -> if (settled) ModuleState.INACTIVE else ModuleState.CHECKING
    !prefsReachable -> ModuleState.UNAVAILABLE
    rebootPending -> ModuleState.NEEDS_REBOOT
    snapshot.enabledCount > 0 && !snapshot.anyReported -> ModuleState.NEEDS_REBOOT
    else -> ModuleState.ACTIVE
}
