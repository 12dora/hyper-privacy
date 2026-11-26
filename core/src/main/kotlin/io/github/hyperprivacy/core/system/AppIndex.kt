package io.github.hyperprivacy.core.system

import io.github.hyperprivacy.core.firewall.UidPolicy

/**
 * One installed package as seen by system_server: its uid, whether it is a (possibly updated) system
 * app, and the digests of its current signing certificates (see [VendorTrust]).
 */
public data class InstalledApp(
    val packageName: String,
    val uid: Int,
    val isSystem: Boolean,
    val signers: Set<String> = emptySet(),
)

/**
 * Snapshot of installed apps keyed by app id (uid modulo [UidPolicy.PER_USER_RANGE]), built off the
 * hot path so system_server hooks can classify callers and targets without IPC or locks.
 *
 * Classification matches `CallerClassifier`: an app id is third-party when no package sharing it
 * is vendor-trusted ([VendorTrust]: a system app, or signed like a vendor system package).
 *
 * - Before the first snapshot ([isEmpty]) nothing is third-party: nothing is hidden (fail-open).
 * - Once a snapshot exists, an application or SDK-sandbox app id missing from it is third-party
 *   (fail closed for hiding): every system and vendor uid exists at boot, so an unknown one is an
 *   app installed after the snapshot, or one installed in another user only. Callers should ask
 *   for a rebuild when they meet one ([isKnown]).
 * - An SDK-sandbox app id is always third-party: it runs SDK code, whoever its owner is.
 * - Other uids (system, shared system ids, isolated processes) are never third-party.
 */
public class AppIndex private constructor(
    private val appIdByPackage: Map<String, Int>,
    private val packagesByAppId: Map<Int, List<String>>,
    /** Known app ids none of whose packages is vendor-trusted. */
    private val thirdPartyKnown: IntSet,
    /** Every application app id in the snapshot. */
    private val known: IntSet,
) {
    /** No snapshot yet (before boot completes): nothing counts as third-party. */
    public val isEmpty: Boolean get() = known.isEmpty()

    public fun isThirdParty(appId: Int): Boolean = when {
        appId in known -> appId in thirdPartyKnown
        isEmpty -> false
        else -> isApplicationAppId(appId) || isSdkSandboxAppId(appId)
    }

    /** Whether [appId] (or, for an SDK sandbox, its owning app) is in the snapshot. */
    public fun isKnown(appId: Int): Boolean = ownerAppId(appId) in known

    public fun appIdOf(packageName: String): Int? = appIdByPackage[packageName]

    /**
     * A stable package name for [appId] (the alphabetically first one sharing it; the owning app for
     * an SDK sandbox), or `null`.
     */
    public fun packageOf(appId: Int): String? = packagesByAppId[ownerAppId(appId)]?.firstOrNull()

    public companion object {
        public val EMPTY: AppIndex = build(emptyList())

        /** Mirrors `android.os.Process.FIRST_SDK_SANDBOX_UID` / `LAST_SDK_SANDBOX_UID`. */
        public const val FIRST_SDK_SANDBOX_UID: Int = 20_000
        public const val LAST_SDK_SANDBOX_UID: Int = 29_999
        private const val SANDBOX_OFFSET = FIRST_SDK_SANDBOX_UID - UidPolicy.FIRST_APPLICATION_UID

        /** App id of [uid], or `-1` for anything that is not a regular application uid. */
        public fun appIdOf(uid: Int): Int {
            if (uid < 0) return -1
            val appId = uid % UidPolicy.PER_USER_RANGE
            return if (isApplicationAppId(appId)) appId else -1
        }

        /**
         * App id of a Binder caller to classify: a regular application app id, or an SDK-sandbox app id
         * (`20000..29999`, classified as third-party); `-1` for everything else.
         */
        public fun callerAppId(uid: Int): Int {
            if (uid < 0) return -1
            val appId = uid % UidPolicy.PER_USER_RANGE
            return if (isApplicationAppId(appId) || isSdkSandboxAppId(appId)) appId else -1
        }

        public fun isSdkSandboxAppId(appId: Int): Boolean = appId in FIRST_SDK_SANDBOX_UID..LAST_SDK_SANDBOX_UID

        private fun isApplicationAppId(appId: Int): Boolean =
            appId in UidPolicy.FIRST_APPLICATION_UID..UidPolicy.LAST_APPLICATION_UID

        private fun ownerAppId(appId: Int): Int = if (isSdkSandboxAppId(appId)) appId - SANDBOX_OFFSET else appId

        public fun build(apps: Iterable<InstalledApp>): AppIndex {
            val all = apps.toList()
            val trusted = VendorTrust.trustedSigners(all)
            val byAppId = HashMap<Int, MutableList<InstalledApp>>()
            all.forEach { app ->
                val appId = appIdOf(app.uid)
                if (appId >= 0) byAppId.getOrPut(appId) { ArrayList() } += app
            }
            val byPackage = HashMap<String, Int>()
            byAppId.forEach { (appId, members) -> members.forEach { byPackage[it.packageName] = appId } }
            val thirdParty = byAppId.filterValues { members -> members.none { VendorTrust.isVendorTrusted(it, trusted) } }
            return AppIndex(
                appIdByPackage = byPackage,
                packagesByAppId = byAppId.mapValues { (_, members) -> members.map { it.packageName }.distinct().sorted() },
                thirdPartyKnown = IntSet.of(thirdParty.keys),
                known = IntSet.of(byAppId.keys),
            )
        }
    }
}
