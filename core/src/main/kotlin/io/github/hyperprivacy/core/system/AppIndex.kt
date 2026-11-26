package io.github.hyperprivacy.core.system

import io.github.hyperprivacy.core.firewall.UidPolicy

/** One installed package as seen by system_server: its uid and whether it is a (possibly updated) system app. */
public data class InstalledApp(val packageName: String, val uid: Int, val isSystem: Boolean)

/**
 * Snapshot of installed apps keyed by app id (uid modulo [UidPolicy.PER_USER_RANGE]), built off the
 * hot path so system_server hooks can classify callers and targets without IPC or locks.
 *
 * Classification matches `CallerClassifier`: an app id is third-party when every package sharing
 * it is non-system. Unknown app ids and non-application uids are never third-party (fail-open:
 * nothing is hidden from or about them).
 */
public class AppIndex private constructor(
    private val appIdByPackage: Map<String, Int>,
    private val packagesByAppId: Map<Int, List<String>>,
    /** App ids whose packages are all non-system. */
    public val thirdParty: IntSet,
    /** Every application app id in the snapshot. */
    public val known: IntSet,
) {
    public fun isThirdParty(appId: Int): Boolean = appId in thirdParty

    public fun isKnown(appId: Int): Boolean = appId in known

    public fun appIdOf(packageName: String): Int? = appIdByPackage[packageName]

    /** A stable package name for [appId] (the alphabetically first one sharing it), or `null`. */
    public fun packageOf(appId: Int): String? = packagesByAppId[appId]?.firstOrNull()

    public companion object {
        public val EMPTY: AppIndex = build(emptyList())

        /** App id of [uid], or `-1` for anything that is not a regular application uid. */
        public fun appIdOf(uid: Int): Int {
            if (uid < 0) return -1
            val appId = uid % UidPolicy.PER_USER_RANGE
            return if (appId in UidPolicy.FIRST_APPLICATION_UID..UidPolicy.LAST_APPLICATION_UID) appId else -1
        }

        public fun build(apps: Iterable<InstalledApp>): AppIndex {
            val byAppId = HashMap<Int, MutableList<InstalledApp>>()
            apps.forEach { app ->
                val appId = appIdOf(app.uid)
                if (appId >= 0) byAppId.getOrPut(appId) { ArrayList() } += app
            }
            val byPackage = HashMap<String, Int>()
            byAppId.forEach { (appId, members) -> members.forEach { byPackage[it.packageName] = appId } }
            return AppIndex(
                appIdByPackage = byPackage,
                packagesByAppId = byAppId.mapValues { (_, members) -> members.map { it.packageName }.sorted() },
                thirdParty = IntSet.of(byAppId.filterValues { members -> members.none { it.isSystem } }.keys),
                known = IntSet.of(byAppId.keys),
            )
        }
    }
}
