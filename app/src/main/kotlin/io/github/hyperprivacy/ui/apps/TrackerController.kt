package io.github.hyperprivacy.ui.apps

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.scope.Protection
import io.github.hyperprivacy.core.system.AppListConfig
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.core.tracker.TrackerRules
import io.github.hyperprivacy.core.tracker.Trackers
import io.github.hyperprivacy.status.StatusStore
import io.github.hyperprivacy.trackers.AppScanner
import io.github.hyperprivacy.trackers.ScanKey
import io.github.hyperprivacy.trackers.TrackerStore
import io.github.hyperprivacy.trackers.UserApp
import io.github.hyperprivacy.ui.PendingWrites
import io.github.hyperprivacy.ui.ScopeResult
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Apps page: user-installed apps, their offline tracker scan and the user's protection choices.
 *
 * Starts on the first `refresh(start = true)` (when an Apps page is first shown; later visits do
 * nothing), then refreshes on resume and on package changes. Listing runs on [Dispatchers.IO]; scans run on
 * their own serial dispatcher, one app at a time, so memory stays bounded however many and however
 * large the apps are. Results are cached per app build and signature database version, and an app
 * whose build changed is rescanned. The published state is rebuilt at most once per
 * [PUBLISH_INTERVAL_MS].
 *
 * Whether an app is protected lives only in the remote preferences ([TrackerProtection.protectKey]);
 * turning protection on writes the scan result there and asks LSPosed to add the app to the module
 * scope ([ScopeRequests], only ever on that tap); turning it off removes both. The scope is
 * reconciled with the preferences on every refresh. State is confined to [worker].
 */
internal class TrackerController(
    context: Context,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val prefs: () -> SharedPreferences?,
    private val status: () -> StatusStore?,
    service: () -> XposedService?,
) {
    private val appContext = context.applicationContext

    // SharedPreferences are opened on first use, on a background thread.
    private val store by lazy { TrackerStore(appContext) }
    private val appScanner by lazy {
        AppScanner(appContext.packageManager, store) { status()?.trust?.signers().orEmpty() }
    }
    private val scanner = Dispatchers.IO.limitedParallelism(1)
    private val mutableState = MutableStateFlow(AppsUi())
    private val pending = PendingWrites()
    private val publishRequests = Channel<Unit>(Channel.CONFLATED)
    private val scopes = ScopeRequests(
        scope = scope,
        worker = worker,
        service = service,
        protection = ::protection,
        onChange = { publishRequests.trySend(Unit) },
    )

    val state: StateFlow<AppsUi> = mutableState.asStateFlow()

    /** One-shot scope request outcomes, shown on the Apps pages. */
    val messages: Flow<ScopeResult> get() = scopes.messages

    /** A scan result for one app build. */
    private class Scan(val key: ScanKey, val ids: Set<String>)

    // Worker only.
    private var started = false
    private var apps: List<UserApp> = emptyList()
    private val detected = HashMap<String, Scan>()
    private val failed = HashMap<String, ScanKey>()
    private val pendingProtect = HashMap<String, Boolean>()
    private var scanJob: Job? = null
    private var reloadJob: Job? = null
    private var reloadAgain = false

    init {
        scope.launch(worker) {
            publishRequests.receiveAsFlow().collect {
                publish()
                delay(PUBLISH_INTERVAL_MS)
            }
        }
    }

    /**
     * Lists apps and scans the ones without a result. [start] is set by the Apps pages: the first
     * one starts the controller and later ones do nothing (navigating never rescans). Other callers
     * (resume, package changes, service binding) list again, once the controller has started.
     */
    fun refresh(start: Boolean = false) {
        scope.launch(worker) {
            when {
                start && started -> Unit

                start || started -> {
                    started = true
                    reload()
                }
            }
        }
    }

    /** Re-publishes after the remote preferences changed (called from the view model's rebuild). */
    fun onPreferencesChanged() {
        scope.launch(worker) { if (started) publishRequests.trySend(Unit) }
    }

    fun setProtected(packageName: String, on: Boolean) {
        scope.launch(worker) {
            val target = prefs() ?: return@launch
            pendingProtect[packageName] = on
            publish()
            val value = TrackerProtection.encodeDetected(detected[packageName]?.ids.orEmpty())
            val key = TrackerProtection.protectKey(packageName)

            // Committed synchronously (on the worker): the scope follows only a write known to have landed.
            // KTX edit(commit = true) drops commit()'s result, which is what decides here.
            @SuppressLint("UseKtx")
            val written = runCatching {
                target.edit().apply { if (on) putString(key, value) else remove(key) }.commit()
            }.getOrDefault(false)
            if (!written) {
                pendingProtect.remove(packageName)
            } else if (on) {
                scopes.request(packageName)
            } else {
                scopes.remove(packageName)
            }
            publish()
        }
    }

    /**
     * Whether protection is on for [packageName]: a write made here and not yet seen in the
     * preferences wins; an unreadable preference is [Protection.UNKNOWN] (never "off").
     */
    private fun protection(packageName: String): Protection {
        val key = TrackerProtection.protectKey(packageName)
        val read = prefs()?.let { target -> { target.getString(key, null) } }
        return Protection.resolve(pendingProtect[packageName], read)
    }

    /** Writes one per-app switch (SDK, app-level rule, app-list hiding). */
    fun set(switch: AppSwitch, on: Boolean) {
        scope.launch(worker) {
            val target = prefs() ?: return@launch
            val key = switch.key
            pending.put(key, on)
            publish()
            runCatching { target.edit { putBoolean(key, on) } }.onFailure {
                pending.remove(key)
                publish()
            }
        }
    }

    /** Asks LSPosed again for an app whose protection is on but which is not in the scope yet. */
    fun retryScope(packageName: String) {
        scope.launch(worker) {
            scopes.request(packageName)
            publish()
        }
    }

    /** One listing at a time; a refresh while one runs makes it list once more when done. */
    private fun reload() {
        if (reloadJob?.isActive == true) {
            reloadAgain = true
            return
        }
        reloadJob = scope.launch(worker) {
            do {
                reloadAgain = false
                val listed = withContext(Dispatchers.IO) { runCatching { appScanner.installedUserApps() }.getOrNull() }
                if (listed != null) applyListing(listed)
            } while (reloadAgain)
        }
    }

    private suspend fun applyListing(listed: List<UserApp>) {
        apps = listed
        val byPackage = listed.associateBy { it.packageName }
        // A result counts only for the build it was made from; an updated app is scanned again.
        detected.keys.retainAll { byPackage[it]?.key == detected[it]?.key }
        // Unreadable APKs are retried once the app changes (e.g. after it finished updating) or on the next start.
        failed.keys.retainAll { byPackage[it]?.key == failed[it] }
        val missing = listed.filter { it.packageName !in detected }
        val cached = withContext(Dispatchers.IO) {
            runCatching { store.retainScans(byPackage.keys) }
            missing.mapNotNull { app -> runCatching { appScanner.cached(app) }.getOrNull()?.let { app to it } }
        }
        cached.forEach { (app, ids) -> detected[app.packageName] = Scan(app.key, ids) }
        scopes.check()
        scopes.reconcile(byPackage.keys)
        publish()
        scanMissing()
    }

    /** Scans every listed app without a result, one at a time; picks up apps listed meanwhile. */
    private fun scanMissing() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch(worker) {
            var app = nextToScan()
            while (app != null) {
                val found = withContext(scanner) { runCatching { appScanner.scan(app) }.getOrNull() }
                // An app updated or removed while it was scanned is taken again (or dropped) by the next pick.
                if (apps.any { it.packageName == app.packageName && it.key == app.key }) {
                    if (found == null) {
                        failed[app.packageName] = app.key
                    } else {
                        detected[app.packageName] = Scan(app.key, found)
                        prefs()?.let { updateStoredResult(it, app.packageName, found) }
                    }
                    publishRequests.trySend(Unit)
                }
                app = nextToScan()
            }
            publish()
        }
    }

    private fun nextToScan(): UserApp? = apps.firstOrNull { it.packageName !in detected && it.packageName !in failed }

    private fun publish() {
        val raw = prefs()?.let { runCatching { it.all }.getOrNull() }.orEmpty()
        val builder = AppsUiBuilder(raw, pending, status(), scopes.granted, scopes.requesting)
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
                builder.build(app, scan, detected[app.packageName]?.ids.orEmpty(), protect)
            },
            scanned = apps.count { it.packageName in detected || it.packageName in failed },
            total = apps.size,
        )
    }

    private companion object {
        const val PUBLISH_INTERVAL_MS = 200L
    }
}

/** One per-app switch on the Apps pages, stored under [key] in the remote preferences. */
internal sealed class AppSwitch(val key: String) {
    class Sdk(packageName: String, sdkId: String) : AppSwitch(TrackerProtection.blockKey(packageName, sdkId))

    class AppRule(packageName: String, ruleId: String) : AppSwitch(TrackerRules.appRuleKey(packageName, ruleId))

    class HideApps(packageName: String) : AppSwitch(AppListConfig.hideKey(packageName))

    class Strict(packageName: String) : AppSwitch(AppListConfig.strictKey(packageName))
}

/** A protected app was updated and rescanned: hooks pick up the new SDK set on its next start. */
private fun updateStoredResult(prefs: SharedPreferences, packageName: String, found: Set<String>) {
    val key = TrackerProtection.protectKey(packageName)
    val stored = runCatching { prefs.getString(key, null) }.getOrNull() ?: return
    val value = TrackerProtection.encodeDetected(found)
    if (stored == value) return
    runCatching { prefs.edit { putString(key, value) } }
}

/** Builds [UserAppUi] rows from one preferences snapshot. */
private class AppsUiBuilder(
    private val raw: Map<String, *>,
    private val pending: PendingWrites,
    private val status: StatusStore?,
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
                hits = hits(pkg, TrackerProtection.ruleId(sdk.id)),
            )
        }
        val appRules = Catalog.protectedAppRules.map { rule ->
            val default = TrackerRules.isAppRuleOn(raw - TrackerRules.appRuleKey(pkg, rule.id), pkg, rule)
            AppRuleUi(
                id = rule.id,
                title = rule.title,
                summary = rule.summary,
                enabled = pending.resolve(raw, TrackerRules.appRuleKey(pkg, rule.id), default),
                hits = hits(pkg, rule.id),
            )
        }
        return UserAppUi(
            packageName = pkg,
            label = app.label,
            scan = scan,
            sdks = sdks,
            appRules = appRules,
            protected = protect,
            scoped = pkg in granted,
            requesting = pkg in requesting,
            hideApps = pending.resolve(raw, AppListConfig.hideKey(pkg), false),
            strict = pending.resolve(raw, AppListConfig.strictKey(pkg), false),
        )
    }

    private fun hits(pkg: String, ruleId: String): Long =
        status?.let { runCatching { it.get(pkg, ruleId)?.totalHits }.getOrNull() } ?: 0
}
