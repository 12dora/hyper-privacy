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
import io.github.hyperprivacy.core.status.MetaCodec
import io.github.hyperprivacy.core.status.ObservedCodec
import io.github.hyperprivacy.core.status.ProcessMeta
import io.github.hyperprivacy.core.status.RuleReport
import io.github.hyperprivacy.core.status.SinkCodec
import io.github.hyperprivacy.core.status.StatusCodec
import io.github.hyperprivacy.core.system.SettingsObservation

/**
 * Receives status batches from hooked processes via [call] (see [StatusContract]).
 *
 * The provider is exported, so every call is authenticated by Binder uid: the caller must be
 * system_server (reporting FIREWALL rules, and rules, sinks and Settings reads under
 * `Catalog.SYSTEM_SCOPE`) or run as a package in the module scope, and may only report rules,
 * sinks, process facts and observed endpoints of its own package. Everything else is dropped.
 */
class StatusProvider : ContentProvider() {
    private val store by lazy { StatusStore(requireNotNull(context)) }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != StatusContract.METHOD_REPORT) return null
        val uid = Binder.getCallingUid()
        val callerPackages = callerScopePackages(uid)
        val isSystemServer = uid % UidPolicy.PER_USER_RANGE == Process.SYSTEM_UID
        if (callerPackages.isEmpty() && !isSystemServer) {
            Log.w(TAG, "Rejected status report from uid $uid")
            return null
        }
        val reports = StatusCodec.decode(extras?.getString(StatusContract.KEY_PAYLOAD))
            .filter { it.isAuthorized(callerPackages, isSystemServer) }
        val sinks = SinkCodec.decode(extras?.getString(StatusContract.KEY_SINKS))
            .filter { it.packageName in callerPackages || (isSystemServer && it.packageName == Catalog.SYSTEM_SCOPE) }
        // system_server records Settings reads under the reading app's package.
        val observed = ObservedCodec.decode(extras?.getString(StatusContract.KEY_OBSERVED))
            .filter {
                it.packageName in callerPackages || (isSystemServer && SettingsObservation.isSettingsHost(it.host))
            }
            .take(MAX_OBSERVED_PER_CALL)
        val meta = MetaCodec.decode(extras?.getString(StatusContract.KEY_META))
            .filter { it.packageName in callerPackages }
        store.merge(reports, versionsOf(reports, meta)::get)
        store.meta.merge(meta, receivedAt = System.currentTimeMillis())
        store.mergeSinks(sinks)
        store.mergeObserved(observed)
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

    private fun callerScopePackages(uid: Int): Set<String> {
        val packages = context?.packageManager?.getPackagesForUid(uid).orEmpty()
        return packages.filterTo(HashSet()) { it in Catalog.scope }
    }

    private fun RuleReport.isAuthorized(callerPackages: Set<String>, isSystemServer: Boolean): Boolean {
        val rule = Catalog.byId(ruleId) ?: return false
        if (packageName !in rule.packages) return false
        // FIREWALL and system_server-scoped rules are enforced (and reported) by system_server.
        val bySystemServer = rule.kind == RuleKind.FIREWALL || packageName == Catalog.SYSTEM_SCOPE
        return if (bySystemServer) isSystemServer else packageName in callerPackages
    }

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

        /** The per-process recorder bound; anything beyond it is not from our reporter. */
        const val MAX_OBSERVED_PER_CALL = EndpointRecorder.DEFAULT_MAX_ENTRIES
    }
}
