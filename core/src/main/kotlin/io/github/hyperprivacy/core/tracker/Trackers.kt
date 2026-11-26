package io.github.hyperprivacy.core.tracker

import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.rule.UrlPrefix

/** What a tracker SDK does, as listed in the signature database. */
public enum class TrackerCategory {
    ADS,
    ANALYTICS,
    CRASH,
    IDENTIFICATION,
    PROFILING,
    LOCATION,
    PUSH,
    ATTRIBUTION,
}

/**
 * One third-party SDK from the signature database (`data/trackers.json`).
 *
 * @property codePrefixes Java package or class-name prefixes (dot form) that identify the SDK in dex.
 * @property blockHosts hosts blocked exactly or as a dot-suffix (same semantics as DOMAIN rules).
 * @property blockUrls reporting paths on hosts that also serve features (same semantics as URL rules).
 * @property keepHosts hosts the SDK needs for user-visible features; never blocked at host level.
 * @property shortName explicit list-row name per language from the database, if any; see [displayName].
 * @property defaultBlock `false` for SDKs whose blocking can affect sign-in, payment or app updates:
 *   protection skips them unless the user opts in for that app.
 */
public data class TrackerSdk(
    val id: String,
    val name: Text,
    val vendor: String,
    val categories: List<TrackerCategory>,
    val codePrefixes: List<String>,
    val blockHosts: List<String>,
    val blockUrls: List<UrlPrefix>,
    val keepHosts: List<String>,
    val defaultBlock: Boolean = true,
    val shortName: ShortName = ShortName(),
) {
    init {
        require(categories.isNotEmpty()) { "Tracker $id has no category" }
        require(codePrefixes.isNotEmpty()) { "Tracker $id has no code prefix" }
    }

    /**
     * The concise product name for list rows: the database's explicit display name per language,
     * else [name] without its parenthesised aliases ("AMap (Gaode) Location SDK" → "AMap Location SDK").
     */
    val displayName: Text get() = Text(shortName.zh ?: shorten(name.zh), shortName.en ?: shorten(name.en))

    /** The category the SDK is grouped under. */
    val category: TrackerCategory get() = categories.first()

    /** Whether protection can do anything about it; detection-only SDKs (push, unverified hosts) cannot. */
    val blockable: Boolean get() = blockHosts.isNotEmpty() || blockUrls.isNotEmpty()

    /** Optional explicit display names (`displayName` / `displayNameEn` in the database). */
    public data class ShortName(val zh: String? = null, val en: String? = null)

    private companion object {
        val PARENTHESISED = Regex("\\s*[(（][^()（）]*[)）]")
        val SPACES = Regex("\\s+")

        fun shorten(text: String): String = text.replace(PARENTHESISED, "").replace(SPACES, " ").trim().ifEmpty { text }
    }
}

/**
 * The tracker SDK signature database, parsed lazily from the generated [TrackerData] table
 * (regenerate with `scripts/gen-trackers.py`). Malformed records are skipped.
 */
public object Trackers {
    /** Database version, from the JSON; part of scan cache keys so a new database triggers rescans. */
    public val version: String get() = TrackerData.VERSION

    /** Every SDK, sorted by id. */
    public val all: List<TrackerSdk> by lazy { parse(TrackerData.PARTS.joinToString("")) }

    private val byId: Map<String, TrackerSdk> by lazy { all.associateBy { it.id } }

    /** Longest-prefix matcher over every SDK's [TrackerSdk.codePrefixes]. */
    public val matcher: CodePrefixMatcher by lazy {
        CodePrefixMatcher(all.flatMap { sdk -> sdk.codePrefixes.map { it to sdk.id } })
    }

    /** Every keep host of every SDK: exempt from host-level blocking in any protected app. */
    public val keepHosts: Set<String> by lazy { all.flatMapTo(sortedSetOf()) { it.keepHosts } }

    public fun byId(id: String): TrackerSdk? = byId[id]

    internal fun parse(table: String): List<TrackerSdk> {
        val out = ArrayList<TrackerSdk>()
        var current: Builder? = null
        for (line in table.lineSequence()) {
            val tag = line.substringBefore(' ')
            val rest = line.substringAfter(' ', "")
            if (tag == RECORD) {
                current?.build()?.let(out::add)
                current = Builder.start(rest)
            } else {
                current?.add(tag, rest)
            }
        }
        current?.build()?.let(out::add)
        return out.distinctBy { it.id }.sortedBy { it.id }
    }

    private class Builder(val id: String, val defaultBlock: Boolean, val categories: List<TrackerCategory>) {
        var zh: String? = null
        var en: String? = null
        var displayZh: String? = null
        var displayEn: String? = null
        var vendor: String? = null
        val prefixes = ArrayList<String>()
        val hosts = ArrayList<String>()
        val urls = ArrayList<UrlPrefix>()
        val keep = ArrayList<String>()

        fun add(tag: String, rest: String) {
            when (tag) {
                "n" -> zh = rest
                "e" -> en = rest
                "dn" -> displayZh = rest.trim().ifEmpty { null }
                "de" -> displayEn = rest.trim().ifEmpty { null }
                "v" -> vendor = rest
                "p" -> prefixes += tokens(rest)
                "b" -> hosts += tokens(rest)
                "u" -> urls += tokens(rest).mapNotNull(::urlOf)
                "k" -> keep += tokens(rest)
                else -> Unit
            }
        }

        fun build(): TrackerSdk? = runCatching {
            TrackerSdk(
                id = id,
                name = Text(checkNotNull(zh), checkNotNull(en)),
                vendor = checkNotNull(vendor),
                categories = categories,
                codePrefixes = prefixes,
                blockHosts = hosts,
                blockUrls = urls,
                keepHosts = keep,
                defaultBlock = defaultBlock,
                shortName = TrackerSdk.ShortName(displayZh, displayEn),
            )
        }.getOrNull()

        companion object {
            private const val HEADER_FIELDS = 3

            fun start(header: String): Builder? {
                val fields = header.split(' ')
                if (fields.size != HEADER_FIELDS || fields[0].isEmpty()) return null
                val categories = fields[2].split(',').map {
                    TrackerCategory.entries.firstOrNull { c -> c.name == it } ?: return null
                }
                val block = when (fields[1]) {
                    "1" -> true
                    "0" -> false
                    else -> return null
                }
                return Builder(fields[0], block, categories)
            }

            private fun tokens(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }

            private fun urlOf(token: String): UrlPrefix? {
                val slash = token.indexOf('/')
                if (slash <= 0) return null
                return runCatching { UrlPrefix(token.substring(0, slash), token.substring(slash)) }.getOrNull()
            }
        }
    }

    private const val RECORD = "sdk"
}
