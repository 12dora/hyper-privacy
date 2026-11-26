package io.github.hyperprivacy.core.status

/**
 * Diagnostics records of code an app loads at runtime (dex through a class loader, native
 * libraries through `System.load` / `loadLibrary`), carried on the observed-endpoint channel.
 *
 * A load is an [ObservedEndpoint] whose host is [DEX_HOST] or [NATIVE_HOST] (under the reserved
 * `.invalid` TLD, so it can never be a real endpoint) and whose path is `/<origin>/<file name>`
 * from [describe]. The path keeps only what a tracker scan needs to find the file again: where it
 * lives by category (app-private `files`, `cache`, an `app_*` directory, external storage …) and its
 * file name. User ids, package directories and long hex / digit runs (hashes, timestamps, account
 * ids in names) are dropped.
 *
 * Installed and system code (`/system`, `/apex`, `/data/app` …) is not recorded: it is on disk for
 * any static scan already, and every process loads plenty of it.
 */
// The path literals classify paths other code loaded; nothing here opens a file.
@Suppress("SdCardPath")
public object CodeLoads {
    private const val SUFFIX = ".code-load.invalid"
    public const val DEX_HOST: String = "dex$SUFFIX"
    public const val NATIVE_HOST: String = "native$SUFFIX"

    /** Origin of an in-memory dex (`InMemoryDexClassLoader`), which has no file. */
    public const val MEMORY_PATH: String = "/memory/in-memory-dex"

    private const val MAX_NAME = 64
    private const val MIN_REDACTED_RUN = 8
    private const val REDACTED = "*"

    public fun isCodeLoad(host: String): Boolean = host.endsWith(SUFFIX)

    private val INSTALLED_ROOTS = listOf(
        "/system/", "/system_ext/", "/product/", "/vendor/", "/odm/", "/apex/", "/data/app/",
        "/data/dalvik-cache/", "/data/misc/", "/data/resource-cache/", "/data/apex/",
    )

    private val PRIVATE_ROOTS = listOf("/data/user/", "/data/user_de/", "/data/data/")
    private val APP_EXTERNAL_DIRS = listOf("/Android/data/", "/Android/obb/")
    private val EXTERNAL_ROOTS = listOf("/storage/emulated/", "/sdcard/", "/storage/self/primary/")

    private val PRIVATE_DIRS = mapOf(
        "files" to "private-files",
        "cache" to "private-cache",
        "code_cache" to "private-code-cache",
        "no_backup" to "private-no-backup",
        "lib" to "private-lib",
        "databases" to "private-databases",
    )

    /**
     * The redacted `/<origin>/<file>` of a loaded [path] in [packageName]'s process, or `null` for
     * installed / system code and unusable paths.
     */
    public fun describe(path: String?, packageName: String): String? {
        val clean = path?.trim()?.takeIf { it.startsWith('/') } ?: return null
        if (INSTALLED_ROOTS.any(clean::startsWith)) return null
        val name = fileName(clean) ?: return null
        return "/${origin(clean, packageName)}/$name"
    }

    private fun origin(path: String, packageName: String): String {
        PRIVATE_ROOTS.firstOrNull(path::startsWith)?.let { root -> return privateOrigin(path, root, packageName) }
        if (EXTERNAL_ROOTS.any(path::startsWith)) {
            val appSpecific = APP_EXTERNAL_DIRS.any(path::contains)
            return if (appSpecific) "external-app" else "external-shared"
        }
        return when {
            path.startsWith("/mnt/expand/") -> "adopted-storage"
            path.startsWith("/data/local/tmp/") -> "local-tmp"
            else -> "other"
        }
    }

    /** `/data/user/<n>/<pkg>/<dir>/…`, `/data/data/<pkg>/<dir>/…` → the category of `<dir>`. */
    private fun privateOrigin(path: String, root: String, packageName: String): String {
        val segments = path.removePrefix(root).split('/')
        val withUser = root != "/data/data/"
        val owner = segments.getOrNull(if (withUser) 1 else 0)
        val dir = segments.getOrNull(if (withUser) 2 else 1)
        if (owner != packageName) return "foreign-private"
        val isFile = segments.size <= (if (withUser) 3 else 2)
        return when {
            dir == null || isFile -> "private-root"
            dir.startsWith("app_") -> "private-" + redact(dir).take(MAX_NAME)
            else -> PRIVATE_DIRS[dir] ?: "private-other"
        }
    }

    private fun fileName(path: String): String? {
        val name = path.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return null
        return redact(name).take(MAX_NAME)
    }

    /** Replaces runs of at least [MIN_REDACTED_RUN] hex digits with [REDACTED]. */
    internal fun redact(text: String): String {
        val out = StringBuilder(text.length)
        var runStart = -1
        for (i in 0..text.length) {
            val hex = i < text.length && Character.digit(text[i], HEX_RADIX) >= 0
            if (hex) {
                if (runStart < 0) runStart = i
                continue
            }
            if (runStart >= 0) {
                if (i - runStart >= MIN_REDACTED_RUN) out.append(REDACTED) else out.append(text, runStart, i)
                runStart = -1
            }
            if (i < text.length) out.append(text[i])
        }
        return out.toString()
    }

    private const val HEX_RADIX = 16
}
