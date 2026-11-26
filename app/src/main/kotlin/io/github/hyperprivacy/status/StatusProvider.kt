package io.github.hyperprivacy.status

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import io.github.hyperprivacy.core.firewall.UidPolicy
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.RuleKind
import io.github.hyperprivacy.core.scope.DeviceCoverage
import io.github.hyperprivacy.core.status.EndpointRecorder
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.PerfCodec
import io.github.hyperprivacy.core.status.ProcessMeta
import io.github.hyperprivacy.core.status.RuleReport
import io.github.hyperprivacy.core.status.StatusBatch
import io.github.hyperprivacy.core.status.StatusRelay
import io.github.hyperprivacy.core.system.SettingsObservation

/**
 * Receives status batches from hooked processes via [call] (see [StatusContract]).
 *
 * The provider is exported, so every call is authenticated by Binder uid, and only two kinds of
 * caller are answered at all:
 * - a catalog target (a package in `Catalog.scope`): rules, sinks, process facts and observed
 *   endpoints of its own package;
 * - system_server: FIREWALL rules, rules, sinks and Settings reads under `Catalog.SYSTEM_SCOPE`,
 *   and what protected third-party apps relayed through it ([StatusRelay]); system_server checked
 *   their uid against the remote preferences, so their rows are accepted for any non-vendor
 *   package, for their own rule ids only.
 *
 * Every other caller gets `null`, whether or not protection is on for it, so the provider never
 * tells an app anything about itself. Protected apps never call it (they cannot even see it).
 */
class StatusProvider : ContentProvider() {
    private val store by lazy { StatusStore(requireNotNull(context)) }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != StatusContract.METHOD_REPORT) return null
        val uid = Binder.getCallingUid()
        val isSystemServer = uid % UidPolicy.PER_USER_RANGE == Process.SYSTEM_UID
        val callerPackages = if (isSystemServer) emptySet() else callerScopePackages(uid)
        if (callerPackages.isEmpty() && !isSystemServer) {
            Log.w(TAG, "Rejected status report from uid $uid")
            return null
        }
        val accepted = accept(StatusContract.decode(extras), callerPackages, isSystemServer)
        store.merge(accepted.reports, versionsOf(accepted.reports, accepted.meta)::get)
        store.meta.merge(accepted.meta, receivedAt = System.currentTimeMillis())
        store.mergeSinks(accepted.sinks)
        store.mergeObserved(accepted.observed)
        store.perf.merge(accepted.perf)
        return Bundle.EMPTY
    }

    /**
     * The versionCode each package with an ACTIVE report runs as: the reporting process's own
     * [ProcessMeta] when the batch carries it, else the installed version (system_server reports
     * firewall rules on behalf of apps and sends no meta).
     */
    private fun versionsOf(reports: List<RuleReport>, meta: List<ProcessMeta>): Map<String, Long> {
        val reported = meta.associate { it.packageName to it.versionCode }
        return reports.filter { it.state == HookState.ACTIVE }
            .map { it.packageName }
            .distinct()
            .mapNotNull { pkg -> (reported[pkg] ?: installedVersion(pkg))?.let { pkg to it } }
            .toMap()
    }

    private fun installedVersion(pkg: String): Long? = runCatching {
        val flags = PackageManager.PackageInfoFlags.of(0)
        context?.packageManager?.getPackageInfo(DeviceCoverage.versionPackage(pkg), flags)?.longVersionCode
    }.getOrNull()

    private fun callerScopePackages(uid: Int): Set<String> =
        context?.packageManager?.getPackagesForUid(uid).orEmpty().filterTo(HashSet()) { it in Catalog.scope }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        0

    private companion object {
        const val TAG = "StatusProvider"
    }
}

/**
 * The rows of [batch] this caller may report: its own package's ([callerPackages], a catalog
 * target), or for system_server its own rows and those it relayed for protected apps.
 */
private fun accept(batch: StatusBatch, callerPackages: Set<String>, isSystemServer: Boolean): StatusBatch {
    fun mine(packageName: String) = packageName in callerPackages
    fun relayed(packageName: String) = isSystemServer && isRelayed(packageName)
    return StatusBatch(
        reports = batch.reports.filter { it.isAuthorized(callerPackages, isSystemServer) },
        sinks = batch.sinks.filter {
            mine(it.packageName) || relayed(it.packageName) ||
                (isSystemServer && it.packageName == Catalog.SYSTEM_SCOPE)
        },
        // system_server records Settings reads under the reading app's package.
        observed = batch.observed.filter {
            mine(it.packageName) || relayed(it.packageName) ||
                (isSystemServer && SettingsObservation.isSettingsHost(it.host))
        }.take(if (isSystemServer) MAX_OBSERVED_PER_RELAY else MAX_OBSERVED_PER_CALL),
        meta = batch.meta.filter { mine(it.packageName) || relayed(it.packageName) },
        // Per sink name, never per package: nothing about who sent it beyond being authenticated.
        perf = batch.perf.take(PerfCodec.MAX_ROWS),
    )
}

/** A package whose rows only arrive relayed by system_server: a protected third-party app. */
private fun isRelayed(packageName: String): Boolean =
    packageName != Catalog.SYSTEM_SCOPE && packageName !in Catalog.allPackages

private fun RuleReport.isAuthorized(callerPackages: Set<String>, isSystemServer: Boolean): Boolean {
    // Tracker and app-level rules of a protected app: relayed by system_server only.
    if (StatusRelay.isProtectedAppRuleId(ruleId)) return isSystemServer && isRelayed(packageName)
    val rule = Catalog.byId(ruleId) ?: return false
    if (packageName !in rule.packages) return false
    // FIREWALL and system_server-scoped rules are enforced (and reported) by system_server.
    val bySystemServer = rule.kind == RuleKind.FIREWALL || packageName == Catalog.SYSTEM_SCOPE
    return if (bySystemServer) isSystemServer else packageName in callerPackages
}

/** The per-process recorder bound; anything beyond it is not from our reporter. */
private const val MAX_OBSERVED_PER_CALL = EndpointRecorder.DEFAULT_MAX_ENTRIES

/** system_server's own endpoints plus those it holds for relaying apps. */
private const val MAX_OBSERVED_PER_RELAY = EndpointRecorder.DEFAULT_MAX_ENTRIES * 6
