package io.github.hyperprivacy.core.status

/**
 * Facts about one hooked process, sent alongside its rule reports so the app can tell which app
 * version actually ran and when.
 *
 * @property versionCode the host's own installed `longVersionCode`.
 * @property processStartAt wall-clock millis at which the process started.
 */
public data class ProcessMeta(val packageName: String, val versionCode: Long, val processStartAt: Long) {
    public companion object {
        /**
         * Name system_server reports the module's own code version under: the version of the
         * module APK it loaded at boot, which differs from the installed one after an update
         * until the device restarts. Has no dot, so it never collides with a package name.
         */
        public const val MODULE: String = "module"
    }
}

/** Wire format for [ProcessMeta] rows: `package  versionCode  processStartAt` per line. */
public object MetaCodec {
    internal const val FIELD_COUNT = 3

    public fun encode(rows: Iterable<ProcessMeta>): String = TsvRows.encode(rows.map(::fields))

    internal fun fields(it: ProcessMeta): List<String> =
        listOf(it.packageName, it.versionCode.toString(), it.processStartAt.toString())

    public fun decode(text: String?, maxRows: Int = TsvRows.DEFAULT_MAX_ROWS): List<ProcessMeta> =
        TsvRows.decode(text, FIELD_COUNT, maxRows, ::parse)

    internal fun parse(fields: List<String>): ProcessMeta? {
        val pkg = fields[0].ifEmpty { return null }
        val code = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val startedAt = fields[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return ProcessMeta(pkg, code, startedAt)
    }
}
