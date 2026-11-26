package io.github.hyperprivacy.ui

import android.app.Application
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hyperprivacy.BuildConfig
import io.github.hyperprivacy.ModuleConnection
import io.github.hyperprivacy.core.config.ConfigBackup
import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.health.RebootAdvice
import io.github.hyperprivacy.core.health.RebootReason
import io.github.hyperprivacy.core.health.SystemHalf
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.system.AppListConfig
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.status.StatusStore
import io.github.hyperprivacy.status.systemReport
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

    /** User-installed apps and tracker protection; starts when an Apps page is first shown. */
    val trackers = TrackerController(
        context = application,
        scope = viewModelScope,
        worker = worker,
        prefs = { remotePrefs.get() },
        status = { backend?.store },
        service = { boundService },
    )

    /** The global pause switch. */
    val pause = PauseController(
        context = application,
        scope = viewModelScope,
        worker = worker,
        prefs = { remotePrefs.get() },
        onChange = { rebuildRequests.trySend(Unit) },
    )

    /** Other Xposed modules that overlap with this one. */
    val conflicts = ConflictController(
        context = application,
        scope = viewModelScope,
        worker = worker,
        prefs = { remotePrefs.get() },
        pendingWrites = pendingWrites,
        onChange = { rebuildRequests.trySend(Unit) },
    )

    /** Configuration export and import. */
    val config = ConfigTransfer(
        context = application,
        scope = viewModelScope,
        worker = worker,
        prefs = { remotePrefs.get() },
        pendingWrites = pendingWrites,
        status = { backend?.store },
        clearAppsPending = { trackers.onPreferencesChanged(overwritten = true) },
        moduleVersion = { backend?.versionName.orEmpty() },
        onChange = { rebuildRequests.trySend(Unit) },
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

    /**
     * The Markdown diagnostic report; built on the worker, which reads preferences and the package
     * manager. Rows about user-installed apps are only included when the user chose so at export:
     * with [includeUserApps] `null` and such rows present, the answer is [ReportOutcome.AskUserApps].
     */
    suspend fun diagnosticReport(includeUserApps: Boolean?): ReportOutcome = withContext(worker) {
        val loaded = backend ?: return@withContext ReportOutcome.Failed
        val health = loaded.health
        if (includeUserApps == null && runCatching { health.reportMentionsUserApps() }.getOrDefault(false)) {
            return@withContext ReportOutcome.AskUserApps
        }
        val stored = remotePrefs.get()?.let { runCatching { it.all }.getOrNull() }.orEmpty()
        runCatching {
            health.report(loaded.versionName, includeUserApps == true) { rule ->
                pendingWrites.resolve(stored, ConfigCodec.key(rule.id), rule.defaultOn)
            }
        }.map(ReportOutcome::Ready).getOrDefault(ReportOutcome.Failed)
    }

    fun setSwitch(switch: ModuleSwitch, enabled: Boolean) = write(listOf(switch.key), enabled)

    fun resetDefaults() {
        viewModelScope.launch(worker) {
            val prefs = remotePrefs.get() ?: return@launch
            pause.resume()
            val rules = backend?.view?.rules ?: Catalog.rules
            // Everything an import can write: rule switches, per-app options, app-list settings.
            val stored = runCatching { prefs.all }.getOrNull().orEmpty()
            val keys = ConfigBackup.resetKeys(stored) + rules.map { ConfigCodec.key(it.id) } + ConfigCodec.OBSERVE_KEY
            rules.forEach { pendingWrites.put(ConfigCodec.key(it.id), it.defaultOn) }
            pendingWrites.put(ConfigCodec.OBSERVE_KEY, ConfigCodec.OBSERVE_DEFAULT)
            pendingWrites.put(AppListConfig.SELF_HIDE_KEY, AppListConfig.EMPTY.hideSelf)
            trackers.onPreferencesChanged(overwritten = true)
            rebuildRequests.trySend(Unit)
            runCatching { prefs.edit { keys.forEach(::remove) } }.onFailure {
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
        val rebootReason = RebootAdvice.reason(
            runCatching { backend.store.systemReport() }.getOrNull().let { report ->
                SystemHalf(
                    scopeRebootPending = device.rebootPending,
                    installedVersion = BuildConfig.VERSION_CODE.toLong(),
                    systemReportedThisBoot = report?.reportedThisBoot ?: false,
                    systemModuleVersion = report?.moduleVersion,
                    systemInScope = device.systemInScope,
                    uptimeMs = SystemClock.elapsedRealtime(),
                )
            },
        )
        val until = pause.resolve(stored)
        pause.track(until)
        val selfHidden = pendingWrites.resolve(stored, AppListConfig.SELF_HIDE_KEY, AppListConfig.EMPTY.hideSelf)
        val hidingApps = selfHidden || AppListConfig.decode(stored).restricted.isNotEmpty()
        val otherProtectionOn = hidingApps || TrackerProtection.protectedPackages(stored).isNotEmpty()
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
                rebootReason = rebootReason,
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
            selfHidden = selfHidden,
            observed = observed,
            scope = device.ui,
            rebootReason = rebootReason,
            pausedUntil = until,
            otherProtectionOn = otherProtectionOn,
            conflict = conflicts.ui(appListHidingOn = hidingApps),
        )
    }

    private companion object {
        const val THROTTLE_MS = 200L
        const val SETTLE_MS = 1_000L
    }
}

/** What [MainViewModel.diagnosticReport] produced. */
internal sealed interface ReportOutcome {
    data class Ready(val text: String) : ReportOutcome

    /** The report would list user-installed apps: ask whether to include them, then build again. */
    data object AskUserApps : ReportOutcome

    data object Failed : ReportOutcome
}

/** Module-wide switches besides the rule switches; hooks and system_server apply them live. */
internal enum class ModuleSwitch(val key: String) {
    /** Diagnostics mode ([ConfigCodec.OBSERVE_KEY]). */
    DIAGNOSTICS(ConfigCodec.OBSERVE_KEY),

    /** Hide the module from third-party apps ([AppListConfig.SELF_HIDE_KEY]). */
    SELF_HIDE(AppListConfig.SELF_HIDE_KEY),
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
    rebootReason: RebootReason = RebootReason.NONE,
): ModuleState = when {
    !bound -> if (settled) ModuleState.INACTIVE else ModuleState.CHECKING
    !prefsReachable -> ModuleState.UNAVAILABLE
    rebootReason != RebootReason.NONE -> ModuleState.NEEDS_REBOOT
    snapshot.enabledCount > 0 && !snapshot.anyReported -> ModuleState.NEEDS_REBOOT
    else -> ModuleState.ACTIVE
}
