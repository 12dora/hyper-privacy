package io.github.hyperprivacy.core.tracker

/**
 * Preference keys of origin attribution in a protected app (same group as [TrackerProtection]):
 *
 * - [ORIGIN_PREFIX]`<pkg>` → String: what the manifest scan found ([OriginHints.encode]: relocated
 *   SDK roots, the app's own roots, suspected ad packages). Written and removed together with
 *   [TrackerProtection.protectKey].
 * - [suspectKey]`(<pkg>, <root>)` → Boolean: the user's choice for one suspected ad package; absent
 *   means off. Lives under the per-app block keys with an id `suspect:<root>` (a colon, so it
 *   collides with neither SDK nor rule ids), so it is watched like every other per-app switch.
 *
 * Which SDKs are blocked by origin follows their regular per-app switch
 * ([TrackerProtection.isBlocked]) for [TrackerSdk.originBlockable] SDKs.
 */
public object TrackerOrigin {
    public const val ORIGIN_PREFIX: String = "tracker.origin."

    /** Rule id hits on enabled suspected ad packages are counted under (one per app). */
    public const val SUSPECT_RULE_ID: String = "tracker.suspect"

    private const val SUSPECT_ID_PREFIX = "suspect:"
    private const val SUSPECT_MARK = ".suspect"

    public fun originKey(packageName: String): String = ORIGIN_PREFIX + packageName

    public fun suspectKey(packageName: String, root: String): String =
        TrackerProtection.blockKey(packageName, SUSPECT_ID_PREFIX + root)

    /**
     * Whether preference [key] is a suspected-package switch ([suspectKey]). These stay on the
     * device: the suspected packages come from this device's scan, so backups neither carry nor
     * replace them.
     */
    public fun isSuspectKey(key: String): Boolean {
        if (!key.startsWith(TrackerProtection.BLOCK_PREFIX)) return false
        // Package names have no colon: the first one ends `<pkg>.suspect`.
        val colon = key.indexOf(':')
        return colon > 0 && key.regionMatches(colon - SUSPECT_MARK.length, SUSPECT_MARK, 0, SUSPECT_MARK.length)
    }

    /** The stored [OriginHints] of [packageName] (empty when none). */
    public fun origin(raw: Map<String, *>, packageName: String): OriginHints =
        OriginHints.decode(raw[originKey(packageName)] as? String)

    /** Whether the user switched on suspected ad package [root] in [packageName]. */
    public fun isSuspectBlocked(raw: Map<String, *>, packageName: String, root: String): Boolean =
        raw[suspectKey(packageName, root)] as? Boolean ?: false
}
