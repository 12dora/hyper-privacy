package io.github.hyperprivacy.xposed.id

import android.content.pm.PackageInfo
import android.content.pm.Signature
import io.github.hyperprivacy.core.system.InstalledApp
import java.security.MessageDigest

/**
 * Hex SHA-256 digests of a package's signing certificates, the [io.github.hyperprivacy.core.system.InstalledApp.signers]
 * and [io.github.hyperprivacy.core.system.InstalledApp.pastSigners] that
 * [io.github.hyperprivacy.core.system.VendorTrust] compares. Needs a [PackageInfo] fetched with
 * `GET_SIGNING_CERTIFICATES`; anything else yields empty sets (never trusted by signature).
 */
internal object SignerDigests {
    /** [info] as an [InstalledApp] with its current and past signers. */
    fun installedApp(info: PackageInfo, uid: Int, isSystem: Boolean): InstalledApp =
        InstalledApp(info.packageName, uid, isSystem, of(info), past(info))

    /** The current signers. */
    fun of(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val certificates: Array<Signature> = signing.apkContentsSigners ?: return emptySet()
        return certificates.mapTo(HashSet()) { digest(it.toByteArray()) }
    }

    /**
     * The certificates of the APK v3 rotation lineage other than the current signers (verified by
     * PackageManager at install). Empty without rotation, and for multi-signer packages, which
     * cannot rotate.
     */
    fun past(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        if (signing.hasMultipleSigners() || !signing.hasPastSigningCertificates()) return emptySet()
        val history: Array<Signature> = signing.signingCertificateHistory ?: return emptySet()
        return history.mapTo(HashSet()) { digest(it.toByteArray()) } - of(info)
    }

    private fun digest(bytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(hash.size * 2) {
            hash.forEach { byte ->
                val value = byte.toInt() and BYTE_MASK
                append(HEX[value ushr NIBBLE]).append(HEX[value and NIBBLE_MASK])
            }
        }
    }

    private const val HEX = "0123456789abcdef"
    private const val BYTE_MASK = 0xFF
    private const val NIBBLE = 4
    private const val NIBBLE_MASK = 0xF
}
