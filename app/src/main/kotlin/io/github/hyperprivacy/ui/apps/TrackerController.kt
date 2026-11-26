package io.github.hyperprivacy.ui.apps

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hyperprivacy.core.system.AppListConfig
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.core.tracker.Trackers
import io.github.hyperprivacy.status.StatusStore
import io.github.hyperprivacy.trackers.AppScanner
import io.github.hyperprivacy.trackers.TrackerStore
import io.github.hyperprivacy.trackers.UserApp
import io.github.hyperprivacy.ui.PendingWrites
import io.github.hyperprivacy.ui.ScopeResult
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Apps page: user-installed apps, their offline tracker scan and the user's protection choices.
 *
 * Starts on the first `refresh(start = true)` (when the page is first opened), then refreshes on
 * resume and on package changes. Scans run on [scanner], one app at a time, so memory stays bounded
 * however many and however large the apps are; results are cached per app build and signature
 * database version.
 *
 * Turning protection on writes the scan result to the remote preferences
 * ([TrackerProtection.protectKey]) and asks LSPosed to add the app to the module scope
 * ([ScopeRequests], only ever on that tap); turning it off removes both. State is confined to [worker].
 */
internal class TrackerController(
    context: Context,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val prefs: () -> SharedPreferences?,
    service: () -> XposedService?,
) {
    private val store = TrackerStore(context)
    private val status = StatusStore(context)
    private val appScanner = AppScanner(context.packageManager, store)
    private val scanner = Dispatchers.IO.limitedParallelism(1)
    private val mutableState = MutableStateFlow(AppsUi())
    private val pending = PendingWrites()
    private val scopes = ScopeRequests(scope, worker, service, ::publish)

    val state: StateFlow<AppsUi> = mutableState.asStateFlow()

    /** One-shot scope request outcomes, shown on the Apps pages. */
    val messages: Flow<ScopeResult> get() = scopes.messages

    // Worker only.
    private var started = false
    private var apps: List<UserApp> = emptyList()
    private val detected = HashMap<String, Set<String>>()
    private val failed = HashSet<String>()
    private val pendingProtect = HashMap<String, Boolean>()
    private var scanJob: Job? = null

    /**
     * Lists apps and scans the ones without a cached result; [start] is set by the Apps pages, other
     * callers (resume, package changes, service binding) only refresh once those have been opened.
     */
    fun refresh(start: Boolean = false) {
        scope.launch(worker) {
            if (!started && !start) return@launch
            started = true
            reload()
        }
    }

    /** Re-publishes after the remote preferences changed (called from the view model's rebuild). */
    fun onPreferencesChanged() {
        scope.launch(worker) { if (started) publish() }
    }

    fun setProtected(packageName: String, on: Boolean) {
        scope.launch(worker) {
            val target = prefs() ?: return@launch
            pendingProtect[packageName] = on
            publish()
            val value = TrackerProtection.encodeDetected(detected[packageName].orEmpty())
            val key = TrackerProtection.protectKey(packageName)
            if (runCatching { target.edit { if (on) putString(key, value) else remove(key) } }.isFailure) {
                pendingProtect.remove(packageName)
            } else {
                runCatching { store.setProtected(packageName, on) }
                if (on) scopes.request(packageName) else scopes.remove(packageName)
            }
            publish()
        }
    }

    fun setSdkBlocked(packageName: String, sdkId: String, blocked: Boolean) =
        writeBoolean(TrackerProtection.blockKey(packageName, sdkId), blocked)

    fun setHideApps(packageName: String, on: Boolean) = writeBoolean(AppListConfig.hideKey(packageName), on)

    fun setStrict(packageName: String, on: Boolean) = writeBoolean(AppListConfig.strictKey(packageName), on)

    /** Asks LSPosed again for an app whose protection is on but which is not in the scope yet. */
    fun retryScope(packageName: String) {
        scope.launch(worker) {
            scopes.request(packageName)
            publish()
        }
    }

    private suspend fun reload() {
        val listed = withContext(scanner) { runCatching { appScanner.installedUserApps() }.getOrNull() } ?: return
        apps = listed
        val installed = listed.mapTo(HashSet()) { it.packageName }
        detected.keys.retainAll(installed)
        // Unreadable APKs are retried on every refresh (e.g. after the app finished updating).
        failed.clear()
        listed.forEach { app -> appScanner.cached(app)?.let { detected[app.packageName] = it } }
        scopes.check()
        runCatching { store.retainScans(installed) }
        prefs()?.let { syncProtectedMirror(it, store) }
        publish()
        scanMissing()
    }

    /** Scans every listed app without a result, one at a time; picks up apps listed meanwhile. */
    private fun scanMissing() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch(worker) {
            while (true) {
                val app = apps.firstOrNull { it.packageName !in detected && it.packageName !in failed } ?: break
                val found = withContext(scanner) { appScanner.scan(app) }
                if (found == null) {
                    failed += app.packageName
                } else {
                    detected[app.packageName] = found
                    prefs()?.let { updateStoredResult(it, app.packageName, found) }
                }
                publish()
            }
        }
    }

    private fun writeBoolean(key: String, value: Boolean) {
        scope.launch(worker) {
            val target = prefs() ?: return@launch
            pending.put(key, value)
            publish()
            runCatching { target.edit { putBoolean(key, value) } }.onFailure {
                pending.remove(key)
                publish()
            }
        }
    }

    private fun publish() {
        val raw = prefs()?.let { runCatching { it.all }.getOrNull() }.orEmpty()
        val builder = AppsUiBuilder(raw, pending, status, scopes.granted, scopes.requesting)
        mutableState.value = AppsUi(
            loaded = true,
            apps = apps.map { app ->
                val stored = TrackerProtection.detected(raw, app.packageName) != null
                val protect = pendingProtect[app.packageName]
                    ?.also { if (it == stored) pendingProtect.remove(app.packageName) }
                    ?: stored
                val scan = when (app.packageName) {
                    in detected -> ScanState.DONE
                    in failed -> ScanState.FAILED
                    else -> ScanState.PENDING
                }
                builder.build(app, scan, detected[app.packageName].orEmpty(), protect)
            },
            scanned = apps.count { it.packageName in detected || it.packageName in failed },
            total = apps.size,
        )
    }
}

/** A protected app was updated and rescanned: hooks pick up the new SDK set on its next start. */
private fun updateStoredResult(prefs: SharedPreferences, packageName: String, found: Set<String>) {
    val raw = runCatching { prefs.all }.getOrNull() ?: return
    val stored = TrackerProtection.detected(raw, packageName) ?: return
    if (stored.toSet() == found) return
    val value = TrackerProtection.encodeDetected(found)
    runCatching { prefs.edit { putString(TrackerProtection.protectKey(packageName), value) } }
}

/** Keeps the provider's mirror of protected apps in step with the remote preferences. */
private fun syncProtectedMirror(prefs: SharedPreferences, store: TrackerStore) {
    val raw = runCatching { prefs.all }.getOrNull() ?: return
    runCatching { store.syncProtected(TrackerProtection.protectedPackages(raw)) }
}

/** Builds [UserAppUi] rows from one preferences snapshot. */
private class AppsUiBuilder(
    private val raw: Map<String, *>,
    private val pending: PendingWrites,
    private val status: StatusStore,
    private val granted: Set<String>,
    private val requesting: Set<String>,
) {
    fun build(app: UserApp, scan: ScanState, ids: Set<String>, protect: Boolean): UserAppUi {
        val pkg = app.packageName
        val sdks = ids.mapNotNull(Trackers::byId).map { sdk ->
            val key = TrackerProtection.blockKey(pkg, sdk.id)
            SdkUi(
                id = sdk.id,
                name = sdk.displayName,
                vendor = sdk.vendor,
                category = sdk.category,
                blockable = sdk.blockable,
                defaultBlock = sdk.defaultBlock,
                blocked = sdk.blockable && pending.resolve(raw, key, sdk.defaultBlock),
                hits = runCatching { status.get(pkg, TrackerProtection.ruleId(sdk.id))?.totalHits }.getOrNull() ?: 0,
            )
        }
        return UserAppUi(
            packageName = pkg,
            label = app.label,
            scan = scan,
            sdks = sdks,
            protected = protect,
            scoped = pkg in granted,
            requesting = pkg in requesting,
            hideApps = pending.resolve(raw, AppListConfig.hideKey(pkg), false),
            strict = pending.resolve(raw, AppListConfig.strictKey(pkg), false),
        )
    }
}
