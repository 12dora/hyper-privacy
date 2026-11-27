package io.github.hyperprivacy.xposed

/**
 * Which stack frames are the module's, by class name alone: a constant-time, allocation-free,
 * lock-free prefix test that never loads or looks up a class.
 *
 * Release builds have R8 move every class it may rename into one package of the module's own
 * (`-repackageclasses` in `proguard-rules.pro`); the few classes kept by name (the entry point)
 * stay under the module's source root. Both prefixes are read from real classes at runtime, so
 * debug builds (nothing repackaged) work the same. Neither can match the hooked app's classes:
 * R8 never emits that package name for an app, and `kotlin.`, `kotlinx.`, `androidx.` frames are
 * never claimed, whoever bundles them.
 */
internal object ModuleFrames {
    /** The module's source root (`io.github.hyperprivacy.`): the entry class is kept by name. */
    private val sourceRoot: String = ModuleMain::class.java.name.let { name ->
        name.substring(0, name.lastIndexOf('.', name.lastIndexOf('.') - 1) + 1)
    }

    /** The package R8 moved renamed classes into; equals a subpackage of [sourceRoot] in debug builds. */
    private val repackaged: String = ModuleFrames::class.java.name.substringBeforeLast('.', "").let { "$it." }

    /** Both prefixes, for [io.github.hyperprivacy.core.stack.LiveStackFilter]. */
    val prefixes: Array<String> = when {
        repackaged == "." || repackaged.startsWith(sourceRoot) -> arrayOf(sourceRoot)
        else -> arrayOf(repackaged, sourceRoot)
    }

    fun isOurs(className: String): Boolean {
        for (prefix in prefixes) if (className.startsWith(prefix)) return true
        return false
    }
}
