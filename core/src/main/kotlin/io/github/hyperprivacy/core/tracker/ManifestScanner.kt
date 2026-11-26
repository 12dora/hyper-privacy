package io.github.hyperprivacy.core.tracker

import java.io.File
import java.io.IOException
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

    // Offsets in one attribute record (`ResXMLTree_attribute`) read outside the chunk reader.
    private const val ATTR_NS = 0
    private const val ATTR_NAME = 4

    /** Parses the manifest of [apk]; throws [IOException] when the APK or its manifest cannot be read. */
    public fun scan(apk: File): ManifestInfo {
        val bytes = try {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(ENTRY) ?: throw IOException("No manifest in $apk")
                if (entry.size > MAX_MANIFEST_BYTES) throw IOException("Manifest too large in $apk")
                zip.getInputStream(entry).use { input -> input.readNBytes(MAX_MANIFEST_BYTES + 1) }
            }
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException("Cannot read the manifest of $apk", e)
        }
        if (bytes.size > MAX_MANIFEST_BYTES) throw IOException("Manifest too large in $apk")
        return parse(bytes) ?: throw IOException("Unreadable manifest in $apk")
    }

    /** Parses binary manifest [bytes]; `null` when it is not AXML or names no package. */
    public fun parse(bytes: ByteArray): ManifestInfo? {
        val doc = Axml(bytes)
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

        // The innermost open activity (index into components) and its intent filter state.
        private val stack = ArrayList<String>()
        private var activity = -1
        private var activityDepth = -1
        private var filterMain = false
        private var filterLauncher = false

        fun start(name: String, attrs: Attributes) {
            if (stack.size >= MAX_DEPTH) return
            stack += name
            val depth = stack.size
            when {
                depth == 1 && name == "manifest" -> packageName = attrs.plain("package")
                depth == 2 && name != "application" -> Unit
                depth == 2 -> application = attrs.androidName()
                depth == COMPONENT_DEPTH -> component(name, attrs, depth)
                name == "intent-filter" && activity >= 0 -> {
                    filterMain = false
                    filterLauncher = false
                }
                name == "action" && activity >= 0 -> filterMain = filterMain || attrs.androidName() == MAIN
                name == "category" && activity >= 0 ->
                    filterLauncher = filterLauncher || attrs.androidName() == LAUNCHER
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
            if (name == "intent-filter" && activity >= 0 && filterMain && filterLauncher) {
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

    /** The attributes of one start element, read lazily from the document. */
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
                val nameIndex = doc.int(at + ATTR_NAME)
                if (match(doc.string(doc.int(at + ATTR_NS)), doc.string(nameIndex), doc.resourceId(nameIndex))) {
                    return doc.attrValue(at)
                }
            }
            return null
        }

        private companion object {
            const val NAME = "name"
            const val TARGET_ACTIVITY = "targetActivity"
            const val NAME_ID = 0x01010003
            const val TARGET_ACTIVITY_ID = 0x01010202
            const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        }
    }

    /** Little-endian chunk reader over the whole manifest; every read is bounds-checked. */
    private class Axml(private val bytes: ByteArray) {
        private var stringOffsets = IntArray(0)
        private var stringsStart = 0
        private var utf8 = false
        private var resourceIds = IntArray(0)
        private val cache = HashMap<Int, String?>()
        private var bodyStart = 0
        /** Reads stop here: the whole input until the document header says how long it is. */
        private var end = bytes.size

        fun readHeader(): Boolean {
            if (bytes.size < CHUNK_HEADER || u16(0) != RES_XML_TYPE) return false
            end = minOf(bytes.size.toLong(), u32(SIZE_FIELD)).toInt()
            bodyStart = u16(HEADER_SIZE_FIELD)
            return bodyStart in CHUNK_HEADER..end
        }

        fun walk(walk: Walk) {
            var at = bodyStart
            while (at + CHUNK_HEADER <= end) {
                val type = u16(at)
                val headerSize = u16(at + HEADER_SIZE_FIELD)
                val size = u32(at + SIZE_FIELD)
                if (headerSize < CHUNK_HEADER || size < headerSize || at + size > end) return
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
            if (ext + ELEMENT_EXT > end) return
            val name = string(int(ext + ELEMENT_NAME)) ?: return
            val attrStart = u16(ext + ATTR_START)
            val attrSize = u16(ext + ATTR_SIZE)
            val attrCount = u16(ext + ATTR_COUNT)
            if (attrSize < ATTR_MIN_SIZE) {
                walk.start(name, Attributes(this, 0, 0, ATTR_MIN_SIZE))
                return
            }
            val first = ext + attrStart
            val fits = first.toLong() + attrCount.toLong() * attrSize <= end
            walk.start(name, Attributes(this, first, if (fits) attrCount else 0, attrSize))
        }

        private fun readStringPool(at: Int, size: Int): Boolean {
            if (stringOffsets.isNotEmpty() || size < POOL_HEADER) return true
            val count = u32(at + POOL_COUNT)
            if (count > MAX_STRINGS) return false
            val flags = u32(at + POOL_FLAGS)
            utf8 = flags and UTF8_FLAG != 0L
            stringsStart = at + u32(at + POOL_STRINGS_START).toInt()
            val offsets = at + u16(at + HEADER_SIZE_FIELD)
            if (offsets + count * INT_BYTES > at + size) return false
            stringOffsets = IntArray(count.toInt()) { i -> int(offsets + i * INT_BYTES) }
            return true
        }

        private fun readResourceMap(at: Int, headerSize: Int, size: Int) {
            val count = (size - headerSize) / INT_BYTES
            if (count <= 0 || count > MAX_STRINGS) return
            resourceIds = IntArray(count) { i -> int(at + headerSize + i * INT_BYTES) }
        }

        fun resourceId(stringIndex: Int): Int = if (stringIndex in resourceIds.indices) resourceIds[stringIndex] else 0

        /** The value of the attribute at [at]: its raw string, else a string-typed value. */
        fun attrValue(at: Int): String? {
            string(int(at + ATTR_RAW))?.let { return it }
            if (u8(at + ATTR_TYPE) != TYPE_STRING) return null
            return string(int(at + ATTR_DATA))
        }

        fun string(index: Int): String? {
            if (index < 0 || index >= stringOffsets.size) return null
            return cache.getOrPut(index) { decode(stringsStart + stringOffsets[index]) }
        }

        private fun decode(at: Int): String? {
            if (at < 0 || at >= end) return null
            return if (utf8) decodeUtf8(at) else decodeUtf16(at)
        }

        private fun decodeUtf8(at: Int): String? {
            var p = at
            // UTF-16 length, then UTF-8 byte length; each one or two bytes.
            p += if (u8(p) and HIGH_BIT_8 != 0) 2 else 1
            if (p >= end) return null
            val length = if (u8(p) and HIGH_BIT_8 != 0) ((u8(p) and LOW_7) shl BYTE_BITS) or u8(p + 1) else u8(p)
            p += if (u8(p) and HIGH_BIT_8 != 0) 2 else 1
            if (p + length > end) return null
            return String(bytes, p, length, Charsets.UTF_8)
        }

        private fun decodeUtf16(at: Int): String? {
            var p = at
            var length = u16(p)
            p += 2
            if (length and HIGH_BIT_16 != 0) {
                length = ((length and LOW_15) shl SHORT_BITS) or u16(p)
                p += 2
            }
            if (length < 0 || p + length.toLong() * 2 > end) return null
            val chars = CharArray(length) { i -> u16(p + i * 2).toChar() }
            return String(chars)
        }

        fun u8(at: Int): Int = if (at in 0 until end) bytes[at].toInt() and BYTE else 0

        fun u16(at: Int): Int = u8(at) or (u8(at + 1) shl BYTE_BITS)

        fun int(at: Int): Int = u16(at) or (u16(at + 2) shl SHORT_BITS)

        fun u32(at: Int): Long = int(at).toLong() and UINT

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
            const val BYTE = 0xFF
            const val BYTE_BITS = 8
            const val SHORT_BITS = 16
            const val HIGH_BIT_8 = 0x80
            const val LOW_7 = 0x7F
            const val HIGH_BIT_16 = 0x8000
            const val LOW_15 = 0x7FFF
            const val UINT = 0xFFFF_FFFFL
        }
    }
}
