package io.github.hyperprivacy.xposed.fp

import android.system.Os
import android.system.StructStatVfs
import io.github.hyperprivacy.xposed.GuardEnv
import java.io.FileDescriptor

/**
 * `Os#statvfs` and `Os#fstatvfs`: the result's `f_fsid` identifies the device's partitions and is
 * read by SDKs that want a hardware-bound id. Only that one field changes (XOR with a per-app
 * mask, see [io.github.hyperprivacy.core.fingerprint.HardwareTraits.fsid]); block sizes and counts
 * stay real, so free-space checks and `StatFs` are unaffected. Storage volume UUIDs, `StorageManager`
 * and file paths are deliberately not touched: the UUID is part of `/storage/<uuid>` paths and of
 * the identifiers `StorageStatsManager` and document providers expect.
 *
 * One small object is allocated per call (the result struct has final fields); the system call it
 * follows is far heavier.
 */
internal class FsIdHooks(private val env: GuardEnv, private val state: FingerprintState) {
    /** Returns the number of methods hooked. */
    fun install(): Int {
        val methods = listOfNotNull(
            env.declared(Os::class.java, "statvfs", String::class.java),
            env.declared(Os::class.java, "fstatvfs", FileDescriptor::class.java),
        )
        return env.hookAll(methods, ResultHook(state, ::mask))
    }

    private fun mask(real: Any?): Any? {
        val stat = real as? StructStatVfs ?: return null
        return state.replace(GROUP, stat) { traits, value -> withFsid(value, traits.fsid(value.f_fsid)) }
    }

    private fun withFsid(stat: StructStatVfs, fsid: Long) = StructStatVfs(
        stat.f_bsize, stat.f_frsize, stat.f_blocks, stat.f_bfree, stat.f_bavail,
        stat.f_files, stat.f_ffree, stat.f_favail, fsid, stat.f_flag, stat.f_namemax,
    )

    companion object {
        val GROUP = TraitGroup("fsid")
    }
}
