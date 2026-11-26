package io.github.hyperprivacy.core.tracker

import java.io.File
import java.util.zip.ZipFile

/** The parts of an app manifest the tracker scanner uses. */
public data class ManifestInfo(
    val packageName: String,
    /** `<application android:name>`, fully qualified, if declared. */
    val applicationClass: String?,
    val components: List<Component>,
) {
    public enum class Kind { ACTIVITY, SERVICE, PROVIDER, RECEIVER }

    /** One declared component; [launcher] for an activity (or alias target) with a MAIN / LAUNCHER filter. */
    public data class Component(val kind: Kind, val className: String, val launcher: Boolean = false)
}

/**
 * Reads `AndroidManifest.xml` of an APK in its compiled binary XML form (AXML), without loading
 * any code or resources: the string pool, the resource id map and the element tree. Only what
 * [ManifestInfo] needs is kept: the package, the application class and the `android:name` of every
 * activity, activity alias (its target), service, provider and receiver, plus whether an activity
 * is a launcher entry.
 *
 * An attribute is `android:name` when its resource id is `0x01010003` (what the platform itself
 * uses, so it survives manifests whose attribute name strings were stripped) or, failing a
 * resource map, by name and namespace.
 *
 * Bounded: the entry may inflate to at most [MAX_MANIFEST_BYTES], the string pool to
 * [MAX_STRINGS] strings, the result to [MAX_COMPONENTS] components and the element depth to
 * [MAX_DEPTH]. Malformed input ends the parse with what was read before the problem.
 */
public object ManifestScanner {
    public const val MAX_MANIFEST_BYTES: Int = 4 shl 20
    public const val MAX_STRINGS: Int = 1 shl 17
    public const val MAX_COMPONENTS: Int = 8192
    public const val MAX_DEPTH: Int = 64

    private const val ENTRY = "AndroidManifest.xml"

    /** The parsed manifest of [apk], or `null` when the APK or its manifest cannot be read. */
    // ZipFile reports bad archives through unchecked exceptions too (see DexScanner); any failure
    // to read the entry means "no manifest findings", never a crash of the scanner.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    public fun scan(apk: File): ManifestInfo? {
        val bytes = try {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(ENTRY)?.takeIf { it.size <= MAX_MANIFEST_BYTES } ?: return null
                zip.getInputStream(entry).use { input -> input.readNBytes(MAX_MANIFEST_BYTES + 1) }
            }
        } catch (_: Exception) {
            return null
        }
        return if (bytes.size > MAX_MANIFEST_BYTES) null else parse(bytes)
    }

    /** Parses binary manifest [bytes]; `null` when it is not AXML or names no package. */
    public fun parse(bytes: ByteArray): ManifestInfo? {
        val doc = Axml(Bytes(bytes))
        if (!doc.readHeader()) return null
        val walk = Walk()
        doc.walk(walk)
        val pkg = walk.packageName?.takeIf { it.isNotBlank() } ?: return null
        return ManifestInfo(
            packageName = pkg,
            applicationClass = walk.application?.let { qualify(pkg, it) },
            components = walk.components.mapNotNull { (kind, name, launcher) ->
                qualify(pkg, name)?.let { ManifestInfo.Component(kind, it, launcher) }
            },
        )
    }

    /** `.Foo` and `Foo` are relative to [pkg]; `null` for names that cannot be classes. */
    internal fun qualify(pkg: String, name: String): String? {
        val trimmed = name.trim()
        val full = when {
            trimmed.isEmpty() -> return null
            trimmed.startsWith('.') -> pkg + trimmed
            '.' !in trimmed -> "$pkg.$trimmed"
            else -> trimmed
        }
        return full.takeIf { CLASS_NAME.matches(it) }
    }

    private val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")

    /** Collects elements of interest while the tree is walked. */
    private class Walk {
        var packageName: String? = null
        var application: String? = null
        val components = ArrayList<Triple<ManifestInfo.Kind, String, Boolean>>()

        // The open element names, and the innermost open activity (index into components) with
        // the state of the intent filter being read inside it.
        private val stack = ArrayList<String>()
        private var activity = -1
        private var activityDepth = -1
        private var filterMain = false
        private var filterLauncher = false

        fun start(name: String, attrs: Attributes) {
            if (stack.size >= MAX_DEPTH) return
            stack += name
            when (val depth = stack.size) {
                1 -> if (name == "manifest") packageName = attrs.plain("package")
                2 -> if (name == "application") application = attrs.androidName()
                COMPONENT_DEPTH -> component(name, attrs, depth)
                else -> if (activity >= 0) filter(name, attrs)
            }
        }

        private fun filter(name: String, attrs: Attributes) {
            when (name) {
                "intent-filter" -> {
                    filterMain = false
                    filterLauncher = false
                }

                "action" -> filterMain = filterMain || attrs.androidName() == MAIN

                "category" -> filterLauncher = filterLauncher || attrs.androidName() == LAUNCHER

                else -> Unit
            }
        }

        private fun component(name: String, attrs: Attributes, depth: Int) {
            if (stack[1] != "application" || components.size >= MAX_COMPONENTS) return
            val kind = KINDS[name] ?: return
            val className = if (name == "activity-alias") attrs.androidTarget() else attrs.androidName()
            className ?: return
            components += Triple(kind, className, false)
            if (kind == ManifestInfo.Kind.ACTIVITY) {
                activity = components.size - 1
                activityDepth = depth
            }
        }

        fun end() {
            if (stack.isEmpty()) return
            val name = stack.removeAt(stack.size - 1)
            val depth = stack.size + 1
            val launcherFilter = filterMain && filterLauncher
            if (name == "intent-filter" && activity >= 0 && launcherFilter) {
                components[activity] = components[activity].copy(third = true)
            }
            if (depth == activityDepth) {
                activity = -1
                activityDepth = -1
            }
        }

        private companion object {
            const val COMPONENT_DEPTH = 3
            const val MAIN = "android.intent.action.MAIN"
            const val LAUNCHER = "android.intent.category.LAUNCHER"
            val KINDS = mapOf(
                "activity" to ManifestInfo.Kind.ACTIVITY,
                "activity-alias" to ManifestInfo.Kind.ACTIVITY,
                "service" to ManifestInfo.Kind.SERVICE,
                "provider" to ManifestInfo.Kind.PROVIDER,
                "receiver" to ManifestInfo.Kind.RECEIVER,
            )
        }
    }

    /** The attributes of one start element (`ResXMLTree_attribute` records), read lazily. */
    private class Attributes(private val doc: Axml, private val start: Int, private val count: Int, val size: Int) {
        fun plain(name: String): String? = find { ns, attrName, _ -> ns == null && attrName == name }

        fun androidName(): String? = find { ns, attrName, id -> isAndroid(ns, attrName, id, NAME, NAME_ID) }

        fun androidTarget(): String? =
            find { ns, attrName, id -> isAndroid(ns, attrName, id, TARGET_ACTIVITY, TARGET_ACTIVITY_ID) }

        private fun isAndroid(ns: String?, attrName: String?, id: Int, name: String, resId: Int): Boolean =
            if (id != 0) id == resId else ns == ANDROID_NS && attrName == name

        private inline fun find(match: (String?, String?, Int) -> Boolean): String? {
            for (i in 0 until count) {
                val at = start + i * size
                val nameIndex = doc.bytes.int(at + ATTR_NAME)
                val ns = doc.string(doc.bytes.int(at + ATTR_NS))
                if (match(ns, doc.string(nameIndex), doc.resourceId(nameIndex))) return doc.attrValue(at)
            }
            return null
        }

        private companion object {
            const val ATTR_NS = 0
            const val ATTR_NAME = 4
            const val NAME = "name"
            const val TARGET_ACTIVITY = "targetActivity"
            const val NAME_ID = 0x01010003
            const val TARGET_ACTIVITY_ID = 0x01010202
            const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        }
    }

    /** Bounds-checked little-endian reads; out-of-range bytes read as 0. */
    private class Bytes(val data: ByteArray) {
        /** Reads stop here: the whole input until the document header says how long it is. */
        var end = data.size

        fun u8(at: Int): Int = if (at in 0 until end) data[at].toInt() and BYTE else 0

        fun u16(at: Int): Int = u8(at) or (u8(at + 1) shl BYTE_BITS)

        fun int(at: Int): Int = u16(at) or (u16(at + 2) shl SHORT_BITS)

        fun u32(at: Int): Long = int(at).toLong() and UINT
    }

    /** One `ResStringPool`: strings decoded on first use (UTF-8 or UTF-16), then cached. */
    private class StringPool(
        private val bytes: Bytes,
        private val start: Int,
        private val offsets: IntArray,
        private val utf8: Boolean,
    ) {
        private val cache = HashMap<Int, String?>()

        fun get(index: Int): String? {
            if (index < 0 || index >= offsets.size) return null
            return cache.getOrPut(index) { decode(start + offsets[index]) }
        }

        private fun decode(at: Int): String? = when {
            at < 0 || at >= bytes.end -> null
            utf8 -> decodeUtf8(at)
            else -> decodeUtf16(at)
        }

        private fun decodeUtf8(at: Int): String? {
            // UTF-16 length, then UTF-8 byte length; each one or two bytes.
            var p = at + lengthBytes(at)
            if (p >= bytes.end) return null
            val length = if (lengthBytes(p) == 2) {
                ((bytes.u8(p) and LOW_7) shl BYTE_BITS) or bytes.u8(p + 1)
            } else {
                bytes.u8(p)
            }
            p += lengthBytes(p)
            if (p + length > bytes.end) return null
            return String(bytes.data, p, length, Charsets.UTF_8)
        }

        private fun lengthBytes(at: Int): Int = if (bytes.u8(at) and HIGH_BIT_8 != 0) 2 else 1

        private fun decodeUtf16(at: Int): String? {
            var p = at
            var length = bytes.u16(p)
            p += 2
            if (length and HIGH_BIT_16 != 0) {
                length = ((length and LOW_15) shl SHORT_BITS) or bytes.u16(p)
                p += 2
            }
            if (length < 0 || p + length.toLong() * 2 > bytes.end) return null
            val chars = CharArray(length) { i -> bytes.u16(p + i * 2).toChar() }
            return String(chars)
        }
    }

    /** The chunk walk over one binary XML document. */
    private class Axml(val bytes: Bytes) {
        private var pool: StringPool? = null
        private var resourceIds = IntArray(0)
        private var bodyStart = 0

        fun readHeader(): Boolean {
            if (bytes.data.size < CHUNK_HEADER || bytes.u16(0) != RES_XML_TYPE) return false
            bytes.end = minOf(bytes.data.size.toLong(), bytes.u32(SIZE_FIELD)).toInt()
            bodyStart = bytes.u16(HEADER_SIZE_FIELD)
            return bodyStart in CHUNK_HEADER..bytes.end
        }

        fun walk(walk: Walk) {
            var at = bodyStart
            while (at + CHUNK_HEADER <= bytes.end) {
                val type = bytes.u16(at)
                val headerSize = bytes.u16(at + HEADER_SIZE_FIELD)
                val size = bytes.u32(at + SIZE_FIELD)
                if (headerSize < CHUNK_HEADER || size < headerSize || at + size > bytes.end) return
                when (type) {
                    RES_STRING_POOL_TYPE -> if (!readStringPool(at, size.toInt())) return
                    RES_XML_RESOURCE_MAP_TYPE -> readResourceMap(at, headerSize, size.toInt())
                    RES_XML_START_ELEMENT_TYPE -> startElement(at, headerSize, walk)
                    RES_XML_END_ELEMENT_TYPE -> walk.end()
                    else -> Unit
                }
                at += size.toInt()
            }
        }

        private fun startElement(at: Int, headerSize: Int, walk: Walk) {
            val ext = at + headerSize
            if (ext + ELEMENT_EXT > bytes.end) return
            val name = string(bytes.int(ext + ELEMENT_NAME)) ?: return
            val attrSize = bytes.u16(ext + ATTR_SIZE)
            val attrCount = bytes.u16(ext + ATTR_COUNT)
            val first = ext + bytes.u16(ext + ATTR_START)
            val fits = attrSize >= ATTR_MIN_SIZE && first.toLong() + attrCount.toLong() * attrSize <= bytes.end
            walk.start(name, Attributes(this, first, if (fits) attrCount else 0, maxOf(attrSize, ATTR_MIN_SIZE)))
        }

        private fun readStringPool(at: Int, size: Int): Boolean {
            if (pool != null || size < POOL_HEADER) return true
            val count = bytes.u32(at + POOL_COUNT)
            val offsets = at + bytes.u16(at + HEADER_SIZE_FIELD)
            if (count > MAX_STRINGS || offsets + count * INT_BYTES > at + size) return false
            val utf8 = bytes.u32(at + POOL_FLAGS) and UTF8_FLAG != 0L
            val start = at + bytes.u32(at + POOL_STRINGS_START).toInt()
            pool = StringPool(bytes, start, IntArray(count.toInt()) { i -> bytes.int(offsets + i * INT_BYTES) }, utf8)
            return true
        }

        private fun readResourceMap(at: Int, headerSize: Int, size: Int) {
            val count = (size - headerSize) / INT_BYTES
            if (count <= 0 || count > MAX_STRINGS) return
            resourceIds = IntArray(count) { i -> bytes.int(at + headerSize + i * INT_BYTES) }
        }

        fun resourceId(stringIndex: Int): Int = if (stringIndex in resourceIds.indices) resourceIds[stringIndex] else 0

        fun string(index: Int): String? = pool?.get(index)

        /** The value of the attribute at [at]: its raw string, else a string-typed value. */
        fun attrValue(at: Int): String? {
            string(bytes.int(at + ATTR_RAW))?.let { return it }
            if (bytes.u8(at + ATTR_TYPE) != TYPE_STRING) return null
            return string(bytes.int(at + ATTR_DATA))
        }

        private companion object {
            const val RES_XML_TYPE = 0x0003
            const val RES_STRING_POOL_TYPE = 0x0001
            const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
            const val RES_XML_START_ELEMENT_TYPE = 0x0102
            const val RES_XML_END_ELEMENT_TYPE = 0x0103
            const val CHUNK_HEADER = 8
            const val HEADER_SIZE_FIELD = 2
            const val SIZE_FIELD = 4
            const val POOL_HEADER = 28
            const val POOL_COUNT = 8
            const val POOL_FLAGS = 16
            const val POOL_STRINGS_START = 20
            const val UTF8_FLAG = 0x100L
            const val ELEMENT_EXT = 20
            const val ELEMENT_NAME = 4
            const val ATTR_START = 8
            const val ATTR_SIZE = 10
            const val ATTR_COUNT = 12
            const val ATTR_MIN_SIZE = 20
            const val ATTR_RAW = 8
            const val ATTR_TYPE = 15
            const val ATTR_DATA = 16
            const val TYPE_STRING = 0x03
            const val INT_BYTES = 4
        }
    }

    private const val BYTE = 0xFF
    private const val BYTE_BITS = 8
    private const val SHORT_BITS = 16
    private const val HIGH_BIT_8 = 0x80
    private const val LOW_7 = 0x7F
    private const val HIGH_BIT_16 = 0x8000
    private const val LOW_15 = 0x7FFF
    private const val UINT = 0xFFFF_FFFFL
}
