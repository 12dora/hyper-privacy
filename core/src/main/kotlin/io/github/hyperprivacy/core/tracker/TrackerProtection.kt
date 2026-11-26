package io.github.hyperprivacy.core.tracker

import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.rule.Catalog

/**
 * Tracker protection for apps outside the built-in catalog, as stored in the remote preferences
 * group [ConfigCodec.PREFS_GROUP] (written by the module app, read live by the hooked app):
 *
 * - [PROTECT_PREFIX]`<pkg>` → String: comma-separated ids of the SDKs the offline scan found in
 *   `<pkg>`. Present (even empty) means protection is on for `<pkg>`; absent means off.
 * - [BLOCK_PREFIX]`<pkg>.<sdkId>` → Boolean: the user's choice for one SDK in one app. Absent:
 *   [TrackerSdk.defaultBlock].
 * - [BLOCK_PREFIX]`<pkg>.<ruleId>` → Boolean: the user's choice for one app-level rule
 *   ([io.github.hyperprivacy.core.rule.Catalog.protectedAppRules]) in one app; see
 *   [TrackerRules.isAppRuleOn]. SDK ids have no dot and rule ids do, so the two never collide.
 * - [ORIGIN_PREFIX]`<pkg>` → String: what the manifest scan found for origin attribution
 *   ([OriginHints.encode]: relocated SDK roots, the app's own roots, suspected ad packages).
 *   Written and removed together with the protect key.
 * - [suspectKey]`(<pkg>, <root>)` → Boolean: the user's choice for one suspected ad package; absent
 *   means off. Lives under the per-app block keys with an id `suspect:<root>` (a colon, so it
 *   collides with neither SDK nor rule ids).
 *
 * [TrackerRules] turns this into the rules enforced inside a protected app.
 */
public object TrackerProtection {
    public const val PROTECT_PREFIX: String = "trackers."
    public const val BLOCK_PREFIX: String = "tracker.block."
    public const val RULE_PREFIX: String = "tracker."
    public const val ORIGIN_PREFIX: String = "tracker.origin."

    /** Rule id hits on enabled suspected ad packages are counted under (one per app). */
    public const val SUSPECT_RULE_ID: String = "tracker.suspect"

    private const val SUSPECT_ID_PREFIX = "suspect:"

    private const val SEPARATOR = ","

    public fun protectKey(packageName: String): String = PROTECT_PREFIX + packageName

    public fun blockKey(packageName: String, sdkId: String): String = "$BLOCK_PREFIX$packageName.$sdkId"

    public fun originKey(packageName: String): String = ORIGIN_PREFIX + packageName

    public fun suspectKey(packageName: String, root: String): String =
        blockKey(packageName, SUSPECT_ID_PREFIX + root)

    /** The stored [OriginHints] of [packageName] (empty when none). */
    public fun origin(raw: Map<String, *>, packageName: String): OriginHints =
        OriginHints.decode(raw[originKey(packageName)] as? String)

    /** Whether the user switched on suspected ad package [root] in [packageName]. */
    public fun isSuspectBlocked(raw: Map<String, *>, packageName: String, root: String): Boolean =
        raw[suspectKey(packageName, root)] as? Boolean ?: false

    /** Rule id of [sdkId]'s synthetic rules. */
    public fun ruleId(sdkId: String): String = RULE_PREFIX + sdkId

    /** Inverse of [ruleId]; `null` when [ruleId] is not a tracker rule. */
    public fun sdkIdOf(ruleId: String): String? =
        ruleId.takeIf { it.startsWith(RULE_PREFIX) }?.removePrefix(RULE_PREFIX)?.takeIf { Trackers.byId(it) != null }

    /** Whether a change of preference [key] can affect tracker protection (`null`: everything changed). */
    public fun affects(key: String?): Boolean =
        key == null || key.startsWith(PROTECT_PREFIX) || key.startsWith(BLOCK_PREFIX) || key.startsWith(ORIGIN_PREFIX)

    /**
     * Whether a change of preference [key] can affect tracker protection inside [packageName]
     * (`null`: everything changed): its own protect and block keys, and the global switches of
     * [io.github.hyperprivacy.core.rule.Catalog.protectedAppRules]. Edits for other apps are ignored.
     */
    public fun affects(key: String?, packageName: String): Boolean = key == null ||
        key == protectKey(packageName) || key == originKey(packageName) ||
        key.startsWith("$BLOCK_PREFIX$packageName.") ||
        ConfigCodec.ruleIdOf(key)?.let { it in Catalog.protectedAppRuleIds } == true

    /** Encodes detected SDK ids for [protectKey]. */
    public fun encodeDetected(ids: Collection<String>): String = ids.sorted().joinToString(SEPARATOR)

    /** Detected SDK ids stored for [packageName], or `null` when protection is off there. */
    public fun detected(raw: Map<String, *>, packageName: String): List<String>? {
        val value = raw[protectKey(packageName)] as? String ?: return null
        return value.split(SEPARATOR).filter { it.isNotEmpty() }
    }

    /** Every package with protection on. */
    public fun protectedPackages(raw: Map<String, *>): Set<String> = raw.entries
        .filter { (key, value) ->
            value is String && key.startsWith(PROTECT_PREFIX) && key.length > PROTECT_PREFIX.length
        }
        .mapTo(HashSet()) { (key, _) -> key.removePrefix(PROTECT_PREFIX) }

    /** Whether [sdk] is blocked in [packageName] under [raw] (protection itself not considered). */
    public fun isBlocked(raw: Map<String, *>, packageName: String, sdk: TrackerSdk): Boolean =
        raw[blockKey(packageName, sdk.id)] as? Boolean ?: sdk.defaultBlock
}
