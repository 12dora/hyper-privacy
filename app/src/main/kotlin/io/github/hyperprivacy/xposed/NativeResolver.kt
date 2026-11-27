package io.github.hyperprivacy.xposed

/**
 * JNI surface of `libhpnative.so` (`app/src/main/cpp`), kept by name in `proguard-rules.pro`; its
 * frames are under the module's source root, which stack scrubbing hides ([ModuleFrames]). Only
 * [NativeResolverGuard] calls it, after loading the library. None of these throws: results are
 * status codes.
 */
internal object NativeResolver {
    /** Library name for `System.loadLibrary`. */
    const val LIBRARY = "hpnative"

    /** Resolves the originals and patches the libraries loaded so far: patched slots, or < 0 on failure. */
    @JvmStatic
    external fun nativeInstall(): Int

    /** Patches libraries loaded since the last scan: patched slots, or < 0 when not installed. */
    @JvmStatic
    external fun nativeRescan(): Int

    /**
     * Publishes a host table ([io.github.hyperprivacy.core.match.NativeHostTable]); `null` or empty
     * publishes none. Returns its usable entries.
     */
    @JvmStatic
    external fun nativeSetTable(table: ByteArray?): Int
}
