package io.github.hyperprivacy.core.tracker

/**
 * What an app's manifest says about the SDKs in it, beyond the dex scan:
 *
 * @property detected SDKs with a component declared under one of their code prefixes, or found
 *   relocated through an anchor (also seen in packed apps whose dex is encrypted).
 * @property relocated package roots (dot form, ending in `.`) of SDKs copied into another
 *   namespace, mapped to the SDK id: a declared component ends with the part of one of the SDK's
 *   [TrackerSdk.anchors] after its own prefix.
 * @property own the app's own code roots: its package and the packages of its application class
 *   and launcher activities, unless an SDK claims them.
 * @property suspects packages of declared components that look like ad pages or ad services but
 *   belong to no known SDK; listed for the user, never blocked unless enabled.
 */
public data class OriginHints(
    val detected: Set<String> = emptySet(),
    val relocated: Map<String, String> = emptyMap(),
    val own: Set<String> = emptySet(),
    val suspects: List<Suspect> = emptyList(),
) {
    /** A suspected ad package: its root and the simple names of the components that matched. */
    public data class Suspect(val prefix: String, val components: List<String>)

    /** Whether nothing here can change attribution in a protected app (detections aside). */
    val isEmpty: Boolean get() = relocated.isEmpty() && suspects.isEmpty()

    /**
     * Compact form for preferences: comma-separated `r:<sdkId>:<root>`, `o:<root>` and
     * `s:<root>:<name>/<name>` tokens ([detected] is stored separately). Class names contain none
     * of `,` `:` `/`.
     */
    public fun encode(): String = buildList {
        relocated.forEach { (root, sdkId) -> add("$RELOCATED$SEP$sdkId$SEP$root") }
        own.forEach { add("$OWN$SEP$it") }
        suspects.forEach { add("$SUSPECT$SEP${it.prefix}$SEP${it.components.joinToString(NAME_SEP)}") }
    }.joinToString(LIST_SEP)

    public companion object {
        public val EMPTY: OriginHints = OriginHints()

        private const val LIST_SEP = ","
        private const val SEP = ":"
        private const val NAME_SEP = "/"
        private const val RELOCATED = "r"
        private const val OWN = "o"
        private const val SUSPECT = "s"
        private const val SUSPECT_FIELDS = 3

        /** Inverse of [encode]; malformed tokens are skipped, [detected] is empty. */
        public fun decode(text: String?): OriginHints {
            if (text.isNullOrEmpty()) return EMPTY
            val relocated = LinkedHashMap<String, String>()
            val own = LinkedHashSet<String>()
            val suspects = ArrayList<Suspect>()
            for (token in text.split(LIST_SEP)) {
                val fields = token.split(SEP)
                when (fields[0]) {
                    RELOCATED -> if (fields.size == SUSPECT_FIELDS && isRoot(fields[2]) && fields[1].isNotEmpty()) {
                        relocated[fields[2]] = fields[1]
                    }
                    OWN -> if (fields.size == 2 && isRoot(fields[1])) own += fields[1]
                    SUSPECT -> if (fields.size == SUSPECT_FIELDS && isRoot(fields[1])) {
                        suspects += Suspect(fields[1], fields[2].split(NAME_SEP).filter { it.isNotEmpty() })
                    }
                    else -> Unit
                }
            }
            return OriginHints(emptySet(), relocated, own, suspects)
        }

        private fun isRoot(text: String) = text.length > 1 && text.endsWith('.') && !text.startsWith('.')
    }
}

/**
 * Turns a [ManifestInfo] into [OriginHints], using the database's [TrackerSdk.anchors]. Pure and
 * bounded; runs in the module app next to the dex scan, never in a hooked process.
 *
 * A derived root (relocated or suspected) is dropped when it would cover the app's own code: when
 * it is a prefix of (or equal to) the app's package root or any [OriginHints.own] root, or has
 * fewer than [MIN_SEGMENTS] segments. So switching it on can never take the app's launcher or
 * application class for SDK code.
 */
public object ManifestAnalyzer {
    public const val MIN_SEGMENTS: Int = 2
    public const val MAX_RELOCATED: Int = 32
    public const val MAX_SUSPECTS: Int = 16
    public const val MAX_OWN: Int = 8
    private const val MAX_NAMES = 3

    /** An anchor's tail (the part after its SDK prefix, e.g. `activity.RewardActivity`) and its SDK. */
    private class Anchor(val tail: String, val sdkId: String)

    private val anchors: List<Anchor> by lazy { anchorsOf(Trackers.all) }

    /** Class simple-name tokens of ad pages and ad services: `Ad`/`Ads`/`AD` as a CamelCase word, or a format name. */
    private val AD_WORD = Regex("(?:^|(?<=[a-z0-9_$]))(?:Ads?|AD)(?=[A-Z_$]|$)")
    private val AD_FORMAT = Regex("Reward|Interstitial")

    public fun analyze(
        info: ManifestInfo,
        matcher: CodePrefixMatcher = Trackers.matcher,
        trackers: List<TrackerSdk> = Trackers.all,
    ): OriginHints {
        val anchorList = if (trackers === Trackers.all) anchors else anchorsOf(trackers)
        val detected = sortedSetOf<String>()
        val relocated = LinkedHashMap<String, String>()
        val suspectNames = LinkedHashMap<String, MutableList<String>>()
        val own = ownRoots(info, matcher)
        val appRoot = info.packageName + "."
        for (component in info.components) {
            val name = component.className
            matcher.match(name)?.let {
                detected += it
                continue
            }
            val anchor = anchorList.firstOrNull { name.length > it.tail.length + 1 && name.endsWith(".${it.tail}") }
            if (anchor != null) {
                val root = name.substring(0, name.length - anchor.tail.length)
                if (isSafeRoot(root, appRoot, own) && relocated.size < MAX_RELOCATED) {
                    relocated.putIfAbsent(root, anchor.sdkId)
                    detected += anchor.sdkId
                }
                continue
            }
            val simple = name.substringAfterLast('.')
            if (looksLikeAd(simple)) {
                val root = name.substring(0, name.length - simple.length)
                suspectNames.getOrPut(root) { ArrayList() }.let { if (it.size < MAX_NAMES) it += simple }
            }
        }
        val suspects = suspectNames.filterKeys { root ->
            isSafeRoot(root, appRoot, own) && relocated.keys.none { root.startsWith(it) }
        }.entries.take(MAX_SUSPECTS).map { (root, names) -> OriginHints.Suspect(root, names) }
        return OriginHints(detected, relocated, own, suspects)
    }

    /** Whether a simple class name reads like an ad page or ad service. */
    public fun looksLikeAd(simpleName: String): Boolean =
        AD_WORD.containsMatchIn(simpleName) || AD_FORMAT.containsMatchIn(simpleName)

    private fun ownRoots(info: ManifestInfo, matcher: CodePrefixMatcher): Set<String> {
        val classes = listOfNotNull(info.applicationClass) +
            info.components.filter { it.launcher }.map { it.className }
        val roots = linkedSetOf(info.packageName + ".")
        for (name in classes) {
            if (matcher.match(name) != null) continue
            val root = name.substringBeforeLast('.', "")
            if (root.count { it == '.' } + 1 >= MIN_SEGMENTS && roots.size < MAX_OWN) roots += "$root."
        }
        return roots
    }

    private fun isSafeRoot(root: String, appRoot: String, own: Set<String>): Boolean =
        root.count { it == '.' } >= MIN_SEGMENTS && !appRoot.startsWith(root) && own.none { it.startsWith(root) }

    private fun anchorsOf(trackers: List<TrackerSdk>): List<Anchor> = trackers.flatMap { sdk ->
        sdk.anchors.mapNotNull { anchor ->
            val prefix = sdk.codePrefixes.filter { it.endsWith('.') && anchor.startsWith(it) }.maxByOrNull { it.length }
            prefix?.let { Anchor(anchor.substring(it.length), sdk.id) }?.takeIf { it.tail.isNotEmpty() }
        }
    }.sortedByDescending { it.tail.length }
}
