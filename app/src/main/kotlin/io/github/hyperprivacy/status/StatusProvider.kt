package io.github.hyperprivacy.status

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import io.github.hyperprivacy.core.firewall.UidPolicy
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.RuleKind
import io.github.hyperprivacy.core.status.EndpointRecorder
import io.github.hyperprivacy.core.status.ObservedCodec
import io.github.hyperprivacy.core.status.RuleReport
import io.github.hyperprivacy.core.status.SinkCodec
import io.github.hyperprivacy.core.status.StatusCodec

/**
 * Receives status batches from hooked processes via [call] (see [StatusContract]).
 *
 * The provider is exported, so every call is authenticated by Binder uid: the caller must be
 * system_server (reporting FIREWALL rules) or run as a package in the module scope, and may only
 * report rules, sinks and observed endpoints of its own package. Everything else is dropped.
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
            .filter { it.packageName in callerPackages }
        val observed = ObservedCodec.decode(extras?.getString(StatusContract.KEY_OBSERVED))
            .filter { it.packageName in callerPackages }
            .take(MAX_OBSERVED_PER_CALL)
        store.merge(reports)
        store.mergeSinks(sinks)
        store.mergeObserved(observed)
        return Bundle.EMPTY
    }

    private fun callerScopePackages(uid: Int): Set<String> {
        val packages = context?.packageManager?.getPackagesForUid(uid).orEmpty()
        return packages.filterTo(HashSet()) { it in Catalog.scope }
    }

    private fun RuleReport.isAuthorized(callerPackages: Set<String>, isSystemServer: Boolean): Boolean {
        val rule = Catalog.byId(ruleId) ?: return false
        if (packageName !in rule.packages) return false
        return if (rule.kind == RuleKind.FIREWALL) isSystemServer else packageName in callerPackages
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
