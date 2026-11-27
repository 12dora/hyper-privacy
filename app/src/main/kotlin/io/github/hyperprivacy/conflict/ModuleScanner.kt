package io.github.hyperprivacy.conflict

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.edit
import io.github.hyperprivacy.BuildConfig
import io.github.hyperprivacy.core.conflict.DeclaredScope
import io.github.hyperprivacy.core.conflict.ModuleOverlap
import io.github.hyperprivacy.core.conflict.OverlapHit
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.zip.ZipFile

/**
 * Finds other installed Xposed modules that overlap with this one ([ModuleOverlap]).
 *
 * Runs in this app's own process only, on a background thread, and never touches the other apps.
 * Which apps are visible is decided by the manifest `<queries>` (launcher apps, module settings
 * screens and the well-known modules); there is no `QUERY_ALL_PACKAGES`.
 *
 * Cost per pass: one package list with metadata and one intent query. An app is a module when it is
 * a well-known one, carries the legacy metadata flag or answers the module settings intent; only an
 * app none of these settles, and only once per build (version and update time), has its APK's zip
 * directory read for the modern marker (`META-INF/xposed/`). Labels and descriptions are loaded for
 * modules only.
 *
 * The per-build verdicts are kept on disk under salted hashes of package and build, never as
 * package names, so the file is not a list of the apps installed here.
 */
internal class ModuleScanner(context: Context) {
    private val pm: PackageManager = context.packageManager
    private val cache: SharedPreferences by lazy { context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE) }

    /** Overlapping modules among the visible apps, sorted by label. Blocking: call off the main thread. */
    // Visibility comes from the manifest `<queries>` (launcher apps, module settings screens, known modules).
    @SuppressLint("QueryPermissionsNeeded")
    fun overlapping(): List<OverlapHit> {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        val installed = runCatching { pm.getInstalledPackages(flags) }.getOrDefault(emptyList())
        val settings = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(ModuleOverlap.SETTINGS_CATEGORY)
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                .mapNotNullTo(HashSet()) { it.activityInfo?.packageName }
        }.getOrDefault(emptySet())
        val salt = salt()
        val stored = cache.all
        val verdicts = HashMap<String, String>()
        val digest = MessageDigest.getInstance("SHA-256")
        val hits = installed.mapNotNull { info -> hit(info, settings, salt, digest, stored, verdicts) }
        // Also rewrites a cache that has no salt of its own yet (an older plaintext one), so the salt is persisted.
        val dirty = stored[SALT_KEY] != salt || stored.size != verdicts.size + 1 ||
            verdicts.any { (key, value) -> stored[key] != value }
        if (dirty) {
            cache.edit {
                clear()
                putString(SALT_KEY, salt)
                verdicts.forEach { (key, verdict) -> putString(key, verdict) }
            }
        }
        return hits.sortedBy { it.label.lowercase() }
    }

    private fun hit(
        info: PackageInfo,
        settings: Set<String>,
        salt: String,
        digest: MessageDigest,
        stored: Map<String, *>,
        verdicts: MutableMap<String, String>,
    ): OverlapHit? {
        val app = info.applicationInfo ?: return null
        val pkg = info.packageName
        if (pkg == BuildConfig.APPLICATION_ID || app.flags and ApplicationInfo.FLAG_SYSTEM != 0) return null
        val marked = pkg in ModuleOverlap.KNOWN || pkg in settings || app.metaData?.containsKey(LEGACY_FLAG) == true
        val isModule = marked || run {
            val key = cacheKey(digest, salt, pkg, info)
            val verdict = (stored[key] as? String) ?: if (isModuleApk(app)) YES else NO
            verdicts[key] = verdict
            verdict == YES
        }
        if (!isModule) return null
        val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkg)
        val description = listOfNotNull(
            runCatching { app.loadDescription(pm)?.toString() }.getOrNull(),
            app.metaData?.getString(LEGACY_DESCRIPTION),
        ).joinToString(" ")
        val areas = ModuleOverlap.areasOf(pkg, label, description)
        return if (areas.isEmpty()) null else OverlapHit(pkg, label, areas)
    }

    /**
     * The scope [packageName] declares in its own APK (modern `META-INF/xposed/scope.list`, else
     * the legacy `xposedscope` array), [DeclaredScope.Unknown] when it declares none or anything
     * fails. Kept in memory per build, never on disk. Blocking: call off the main thread.
     */
    fun declaredScope(packageName: String): DeclaredScope = runCatching {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        val info = pm.getPackageInfo(packageName, flags)
        val app = info.applicationInfo ?: return@runCatching DeclaredScope.Unknown
        val build = info.longVersionCode to info.lastUpdateTime
        synchronized(scopes) { scopes[packageName] }?.takeIf { it.first == build }?.let { return@runCatching it.second }
        val scope = modernScope(app).takeUnless { it == DeclaredScope.Unknown } ?: legacyScope(app)
        synchronized(scopes) { scopes[packageName] = build to scope }
        scope
    }.getOrDefault(DeclaredScope.Unknown)

    private val scopes = HashMap<String, Pair<Pair<Long, Long>, DeclaredScope>>()

    /**
     * `scope.list` from the base APK (where the module metadata lives). At most [DeclaredScope.MAX_CHARS]
     * bytes are inflated; a longer file is rejected as a whole, never parsed truncated.
     */
    private fun modernScope(app: ApplicationInfo): DeclaredScope {
        val path = app.sourceDir ?: return DeclaredScope.Unknown
        val bytes = runCatching {
            ZipFile(File(path)).use { zip ->
                val entry = zip.getEntry(SCOPE_ENTRY) ?: return@use null
                if (entry.size > DeclaredScope.MAX_CHARS) return@use OVERSIZE
                zip.getInputStream(entry).use { it.readNBytes(DeclaredScope.MAX_CHARS + 1) }
            }
        }.getOrNull() ?: return DeclaredScope.Unknown
        if (bytes === OVERSIZE || bytes.size > DeclaredScope.MAX_CHARS) return DeclaredScope.Unknown
        return DeclaredScope.parseList(String(bytes, Charsets.UTF_8))
    }

    /** The legacy `xposedscope` meta-data: a string-array resource of the module (or a single string). */
    private fun legacyScope(app: ApplicationInfo): DeclaredScope {
        val meta = app.metaData ?: return DeclaredScope.Unknown
        val id = meta.getInt(LEGACY_SCOPE, 0)
        if (id == 0) {
            // Length checked before anything is split.
            val text = meta.getString(LEGACY_SCOPE)?.takeIf { it.length <= DeclaredScope.MAX_CHARS }
            return DeclaredScope.of(text?.split(';', ','))
        }
        return DeclaredScope.of(readArray(app, id))
    }

    /**
     * The `xposedscope` array, read entry by entry: rejected (`null`) before anything is copied when
     * it has more than [DeclaredScope.MAX_ENTRIES] entries, and as soon as its text passes
     * [DeclaredScope.MAX_CHARS].
     */
    private fun readArray(app: ApplicationInfo, id: Int): List<String?>? = runCatching {
        val array = pm.getResourcesForApplication(app).obtainTypedArray(id)
        try {
            val count = array.length()
            if (count > DeclaredScope.MAX_ENTRIES) return@runCatching null
            var chars = 0
            (0 until count).map { index ->
                val item = array.getString(index)
                chars += item?.length ?: 0
                if (chars > DeclaredScope.MAX_CHARS) return@runCatching null
                item
            }
        } finally {
            array.recycle()
        }
    }.getOrNull()

    /** Whether the APK carries the modern module marker; an unreadable one is simply not a module. */
    private fun isModuleApk(app: ApplicationInfo): Boolean = runCatching {
        ZipFile(File(app.sourceDir)).use { zip -> MARKERS.any { zip.getEntry(it) != null } }
    }.getOrDefault(false)

    /** This install's random salt, created on first use. */
    private fun salt(): String = cache.getString(SALT_KEY, null) ?: ByteArray(SALT_BYTES)
        .also(SecureRandom()::nextBytes)
        .let(HEX::formatHex)

    /** The first [KEY_CHARS] hex characters of the build's salted hash; [digest] is reused across one pass. */
    private fun cacheKey(digest: MessageDigest, salt: String, pkg: String, info: PackageInfo): String {
        val text = "$salt\u0000$pkg\u0000${info.longVersionCode}\u0000${info.lastUpdateTime}"
        return HEX.formatHex(digest.digest(text.toByteArray(Charsets.UTF_8)), 0, KEY_CHARS / 2)
    }

    private companion object {
        const val CACHE_FILE = "xposed_modules"
        const val SALT_KEY = "salt"
        const val SALT_BYTES = 16
        const val KEY_CHARS = 32
        const val LEGACY_FLAG = "xposedmodule"
        const val LEGACY_DESCRIPTION = "xposeddescription"
        const val LEGACY_SCOPE = "xposedscope"
        const val SCOPE_ENTRY = "META-INF/xposed/scope.list"

        /** Marks a `scope.list` whose declared size is already over the limit. */
        val OVERSIZE = ByteArray(0)
        const val YES = "1"
        const val NO = "0"
        val HEX: HexFormat = HexFormat.of()
        val MARKERS = listOf("META-INF/xposed/java_init.list", "META-INF/xposed/module.prop")
    }
}
