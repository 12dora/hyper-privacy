package io.github.hyperprivacy.core.fingerprint

import io.github.hyperprivacy.core.id.DerivedIds

/**
 * The per-app view of hardware characteristics that any app can read without a permission and that
 * fingerprinting SDKs hash into a device id (rule `fingerprint.hardware`). Pure and allocation-free
 * after [derive]: the hooks in the app process only call the transforms below.
 *
 * What is changed, and by how much, is a deliberate, narrow list; everything an app might need for
 * its function is left alone:
 *
 * - sensor resolution and power draw: scaled by a stable per-app factor within +-[MAX_SCALE_DELTA]
 *   (1 %). Neither is an input to motion, compass or proximity logic, and 1 % is below any
 *   threshold an app derives from them. Zero and non-finite values stay as they are.
 * - sensor version: raised by a stable per-app amount of 1..[MAX_VERSION_OFFSET]. It is an opaque
 *   module version no app branches on; zero ("unspecified") stays zero and the value never wraps.
 * - file system id (`statvfs` `f_fsid`): XOR with a stable per-app mask. That is a bijection, so
 *   two paths on one file system still compare equal and two file systems still differ, as callers
 *   that compare ids expect; only the value that is unique to this device's partitions changes.
 *
 * Left alone on purpose: sensor maximum range (apps compare proximity and light readings with it),
 * min/max delay and FIFO sizes (sampling rate and batching), name, vendor and type (apps select
 * and work around sensors by them), and every sensor event value.
 */
public class HardwareTraits private constructor(
    private val resolutionScale: Float,
    private val powerScale: Float,
    private val versionOffset: Int,
    private val fsidMask: Long,
) {
    /** A sensor's resolution as this app sees it. */
    public fun resolution(real: Float): Float = scale(real, resolutionScale)

    /** A sensor's power draw (mA) as this app sees it. */
    public fun power(real: Float): Float = scale(real, powerScale)

    /** A sensor's version as this app sees it. */
    public fun version(real: Int): Int =
        if (real <= 0 || real > Int.MAX_VALUE - MAX_VERSION_OFFSET) real else real + versionOffset

    /** A `statvfs` file system id as this app sees it; an involution, so `fsid(fsid(x)) == x`. */
    public fun fsid(real: Long): Long = real xor fsidMask

    private fun scale(real: Float, factor: Float): Float {
        if (real == 0f || !real.isFinite()) return real
        val scaled = real * factor
        return if (scaled.isFinite()) scaled else real
    }

    public companion object {
        /** Largest relative change of a scaled value. */
        public const val MAX_SCALE_DELTA: Float = 0.01f

        /** Largest amount added to a sensor version. */
        public const val MAX_VERSION_OFFSET: Int = 255

        private const val LABEL = "hyperprivacy/hardware-traits/v1"
        private const val BYTE_MASK = 0xFF
        private const val SHORT_MAX = 65_535f
        private const val BITS_PER_BYTE = 8
        private const val POWER_AT = 2
        private const val VERSION_AT = 4
        private const val FSID_AT = 8
        private const val FSID_BYTES = 8

        /**
         * The traits of [packageName] under the module [secret]: the same for one app on one
         * device, different for another app, not derivable without the secret.
         */
        public fun derive(packageName: String, secret: ByteArray): HardwareTraits {
            val bytes = DerivedIds.derive(LABEL.toByteArray(Charsets.UTF_8), packageName, secret)
            val mask = (0 until FSID_BYTES).fold(0L) { acc, i ->
                (acc shl BITS_PER_BYTE) or (bytes[FSID_AT + i].toLong() and BYTE_MASK.toLong())
            }
            return HardwareTraits(
                resolutionScale = scaleAt(bytes, 0),
                powerScale = scaleAt(bytes, POWER_AT),
                versionOffset = 1 + unsigned(bytes[VERSION_AT]) % MAX_VERSION_OFFSET,
                fsidMask = if (mask == 0L) 1L else mask,
            )
        }

        private fun unsigned(byte: Byte): Int = byte.toInt() and BYTE_MASK

        /** A factor in `[1 - MAX_SCALE_DELTA, 1 + MAX_SCALE_DELTA]`, never exactly 1. */
        private fun scaleAt(bytes: ByteArray, index: Int): Float {
            val unit = (unsigned(bytes[index]) shl BITS_PER_BYTE) or unsigned(bytes[index + 1])
            val factor = 1f + (unit / SHORT_MAX * 2f - 1f) * MAX_SCALE_DELTA
            return if (factor == 1f) 1f + MAX_SCALE_DELTA / 2f else factor
        }
    }
}
