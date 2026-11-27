package io.github.hyperprivacy.core.tracker

/**
 * What an app's manifest says about the SDKs in it, beyond the dex scan:
 *
 * @property detected SDKs with a component declared under one of their code prefixes, or found
 *   relocated through an anchor (also seen in packed apps whose dex is encrypted).
 * @property relocated package roots (dot form, ending in `.`) of SDKs copied into another
 *   namespace, mapped to the SDK id: a declared component ends with the part of one of the SDK's
 *   [TrackerSdk.anchors] after its own prefix.
 * @property own the app's own code roots: the [appRoots] plus the package of every other declared
 *   activity and service that no SDK claims and that does not look like an ad page.
 * @property suspects packages of declared components that look like ad pages or ad services but
 *   belong to no known SDK; listed for the user, never blocked unless enabled.
 * @property appRoots the app's strongest roots: its package and the packages of its application
 *   class and launcher activities. No derived root (relocated or suspected) is ever taken inside
 *   one of them: only a database prefix can claim code there.
 */
public data class OriginHints(
    val detected: Set<String> = emptySet(),
    val relocated: Map<String, String> = emptyMap(),
    val own: Set<String> = emptySet(),
    val suspects: List<Suspect> = emptyList(),
    val appRoots: Set<String> = emptySet(),
) {
    /** A suspected ad package: its root and the simple names of the components that matched. */
    public data class Suspect(val prefix: String, val components: List<String>)

    /** Whether nothing here can change attribution in a protected app (detections aside). */
    val isEmpty: Boolean get() = relocated.isEmpty() && suspects.isEmpty()

    /**
     * Compact form for preferences: comma-separated `r:<sdkId>:<root>`, `o:<root>`, `a:<root>` and
     * `s:<root>:<name>/<name>` tokens ([detected] is stored separately). Class names contain none
     * of `, ` `:` `/`.
     */
    public fun encode(): String = buildList {
        relocated.forEach { (root, sdkId) -> add("$RELOCATED$SEP$sdkId$SEP$root") }
        own.forEach { add("$OWN$SEP$it") }
        appRoots.forEach { add("$APP$SEP$it") }
        suspects.forEach { add("$SUSPECT$SEP${it.prefix}$SEP${it.components.joinToString(NAME_SEP)}") }
    }.joinToString(LIST_SEP)

    public companion object {
        public val EMPTY: OriginHints = OriginHints()

        private const val LIST_SEP = ","
        private const val SEP = ":"
        private const val NAME_SEP = "/"
        private const val RELOCATED = "r"
        private const val OWN = "o"
        private const val APP = "a"
        private const val SUSPECT = "s"
        private const val SUSPECT_FIELDS = 3

        /** Inverse of [encode]; malformed tokens are skipped, [detected] is empty. */
        public fun decode(text: String?): OriginHints {
            if (text.isNullOrEmpty()) return EMPTY
            val relocated = LinkedHashMap<String, String>()
            val own = LinkedHashSet<String>()
            val apps = LinkedHashSet<String>()
            val suspects = ArrayList<Suspect>()
            for (token in text.split(LIST_SEP)) {
                val fields = token.split(SEP)
                when (fields[0]) {
                    RELOCATED -> if (fields.size == SUSPECT_FIELDS && isRoot(fields[2]) && fields[1].isNotEmpty()) {
                        relocated[fields[2]] = fields[1]
                    }

                    OWN -> rootOf(fields)?.let(own::add)

                    APP -> rootOf(fields)?.let(apps::add)

                    SUSPECT -> if (fields.size == SUSPECT_FIELDS && isRoot(fields[1])) {
                        suspects += Suspect(fields[1], fields[2].split(NAME_SEP).filter { it.isNotEmpty() })
                    }

                    else -> Unit
                }
            }
            return OriginHints(emptySet(), relocated, own, suspects, apps)
        }

        /** The root of a two-field `<tag>:<root>` token, if well-formed. */
        private fun rootOf(fields: List<String>): String? = fields.takeIf { it.size == 2 && isRoot(it[1]) }?.get(1)

        /**
         * Whether derived root [root] lies inside (or equals) one of [appRoots] or `<packageName>.`:
         * such a root is never attributed to an SDK, whatever the stored hints say.
         */
        public fun isInsideApp(root: String, packageName: String, appRoots: Collection<String>): Boolean =
            root.startsWith("$packageName.") || appRoots.any { root.startsWith(it) }

        private fun isRoot(text: String) = text.length > 1 && text.endsWith('.') && !text.startsWith('.')
    }
}

/**
 * Turns a [ManifestInfo] into [OriginHints], using the database's [TrackerSdk.anchors]. Pure and
 * bounded; runs in the module app next to the dex scan, never in a hooked process.
 *
 * Roots are decided so that the app's own code is never taken for SDK code:
 * - the app roots (package, application class, launcher activities) are own, and no relocated or
 *   suspected root is accepted inside them;
 * - every other declared activity or service package that no SDK claims (by prefix or relocated
 *   root) and whose components do not look like ad pages is own as well;
 * - a derived root is dropped when it equals or covers an own root, or has fewer than
 *   [MIN_SEGMENTS] segments; a suspect in a package that also holds own components is dropped.
 */
public object ManifestAnalyzer {
    public const val MIN_SEGMENTS: Int = 2
    private const val MIN_NAMESPACE_SEGMENTS = 4
    public const val MAX_RELOCATED: Int = 32
    public const val MAX_SUSPECTS: Int = 16
    public const val MAX_OWN: Int = 64
    private const val MAX_NAMES = 3

    /** An anchor's tail (the part after its SDK prefix, e.g. `activity.RewardActivity`) and its SDK. */
    private class Anchor(val tail: String, val sdkId: String)

    private val anchors: List<Anchor> by lazy { anchorsOf(Trackers.all) }

    /** A package an SDK owns through its class-name prefixes (see [namespacesOf]). */
    private class Namespace(val root: String, val sdkId: String)

    private val namespaces: List<Namespace> by lazy { namespacesOf(Trackers.all) }

    /**
     * Class simple-name tokens of ad pages and ad services: `Ad`/`Ads`/`AD` as a CamelCase word
     * (not the directory / sign-in sense: `ADLogin`, `AzureAdAuth`), or an ad format name
     * (`Interstitial`, rewarded video: a bare `Reward` is a loyalty page as often as an ad).
     */
    private val AD_WORD = Regex(
        "(?:^|(?<=[a-z0-9_$]))(?:Ads?|AD)(?=[A-Z_$]|$)" +
            "(?!Login|Logon|Logout|Auth|Account|Sign|Sso|SSO|Directory|User|Tenant|Group|Domain|Ldap|LDAP|FS|Fs)",
    )
    private val AD_FORMAT = Regex("Interstitial|Reward(?:ed)?(?:Video|Ad|Express|FullScreen|Fullscreen|Splash)")

    private val OWN_KINDS = setOf(ManifestInfo.Kind.ACTIVITY, ManifestInfo.Kind.SERVICE)

    public fun analyze(
        info: ManifestInfo,
        matcher: CodePrefixMatcher = Trackers.matcher,
        trackers: List<TrackerSdk> = Trackers.all,
    ): OriginHints {
        val default = trackers === Trackers.all
        val anchorList = if (default) anchors else anchorsOf(trackers)
        val spaces = if (default) namespaces else namespacesOf(trackers)
        val apps = appRoots(info, matcher)
        val found = Found(apps)
        for (component in info.components) {
            val name = component.className
            val known = matcher.match(name)
            val anchor = if (known == null) anchorList.firstOrNull { name.endsWith(".${it.tail}") } else null
            val space = if (known == null && anchor == null) spaces.firstOrNull { name.startsWith(it.root) } else null
            when {
                known != null -> found.detected += known
                space != null -> found.detected += space.sdkId
                anchor != null -> found.relocate(name.substring(0, name.length - anchor.tail.length), anchor.sdkId)
                else -> found.candidates += component
            }
        }
        found.candidates.forEach(found::place)
        val suspects = found.suspectNames.filterKeys { root ->
            isSafeRoot(root, apps, found.own) && found.relocated.keys.none { root.startsWith(it) }
        }.entries.take(MAX_SUSPECTS).map { (root, names) -> OriginHints.Suspect(root, names) }
        return OriginHints(found.detected, found.relocated, found.own, suspects, apps)
    }

    /** What [analyze] collects, in two passes: SDK components first, then the rest ([place]). */
    private class Found(val apps: Set<String>) {
        val detected = sortedSetOf<String>()
        val relocated = LinkedHashMap<String, String>()
        val candidates = ArrayList<ManifestInfo.Component>()
        val own = LinkedHashSet(apps)
        val suspectNames = LinkedHashMap<String, MutableList<String>>()

        /** An anchor names [sdkId] relocated to [root]; kept when the root is safe. */
        fun relocate(root: String, sdkId: String) {
            if (!isSafeRoot(root, apps, apps) || relocated.size >= MAX_RELOCATED) return
            relocated.putIfAbsent(root, sdkId)
            detected += sdkId
        }

        /** A component no SDK claims: an ad-looking one makes its package a suspect, others own code. */
        fun place(component: ManifestInfo.Component) {
            val name = component.className
            if (relocated.keys.any { name.startsWith(it) }) return
            val simple = name.substringAfterLast('.')
            val root = name.substring(0, name.length - simple.length)
            when {
                looksLikeAd(simple) -> {
                    val names = suspectNames.getOrPut(root) { ArrayList() }
                    if (names.size < MAX_NAMES) names += simple
                }

                component.kind in OWN_KINDS && segments(root) >= MIN_SEGMENTS && own.size < MAX_OWN -> own += root

                else -> Unit
            }
        }
    }

    /** Whether a simple class name reads like an ad page or ad service. */
    public fun looksLikeAd(simpleName: String): Boolean =
        AD_WORD.containsMatchIn(simpleName) || AD_FORMAT.containsMatchIn(simpleName)

    /** The package, the application class's package and launcher activities' packages, unless an SDK claims them. */
    private fun appRoots(info: ManifestInfo, matcher: CodePrefixMatcher): Set<String> {
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

    /** A derived root is safe when it is deep enough, covers no own root and lies inside no app root. */
    private fun isSafeRoot(root: String, apps: Set<String>, own: Set<String>): Boolean =
        segments(root) >= MIN_SEGMENTS && own.none { it.startsWith(root) } && apps.none { root.startsWith(it) }

    private fun segments(root: String): Int = root.count { it == '.' }

    /**
     * The packages SDKs own through class-name prefixes: a prefix without a trailing dot
     * (`com.google.android.gms.ads.AdView`) names a class, so its package (`com.google.android.gms.ads.`)
     * is the SDK's namespace. A component of that package no prefix matches (the SDK's own
     * `AdActivity`) still belongs to the SDK, never to the suspects. Packages with fewer than
     * [MIN_NAMESPACE_SEGMENTS] segments are ignored (never `com.google.android.gms.`), and the
     * longest root wins; a sibling SDK's longer prefix (`...ads.identifier.`) matches first anyway.
     */
    private fun namespacesOf(trackers: List<TrackerSdk>): List<Namespace> = trackers.flatMap { sdk ->
        sdk.codePrefixes.filterNot { it.endsWith('.') }.mapNotNull { prefix ->
            val root = prefix.substringBeforeLast('.', "").let { if (it.isEmpty()) it else "$it." }
            root.takeIf { segments(it) >= MIN_NAMESPACE_SEGMENTS }?.let { Namespace(it, sdk.id) }
        }
    }.distinctBy { it.root }.sortedByDescending { it.root.length }

    private fun anchorsOf(trackers: List<TrackerSdk>): List<Anchor> = trackers.flatMap { sdk ->
        sdk.anchors.mapNotNull { anchor ->
            val prefix = sdk.codePrefixes.filter { it.endsWith('.') && anchor.startsWith(it) }.maxByOrNull { it.length }
            prefix?.let { Anchor(anchor.substring(it.length), sdk.id) }?.takeIf { it.tail.isNotEmpty() }
        }
    }.sortedByDescending { it.tail.length }
}
