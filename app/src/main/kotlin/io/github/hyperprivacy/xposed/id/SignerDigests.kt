package io.github.hyperprivacy.xposed.id

import android.content.pm.PackageInfo
import android.content.pm.Signature
import java.security.MessageDigest

/**
 * Hex SHA-256 digests of a package's current signing certificates, the [io.github.hyperprivacy.core.system.InstalledApp.signers]
 * that [io.github.hyperprivacy.core.system.VendorTrust] compares. Needs a [PackageInfo] fetched with
 * `GET_SIGNING_CERTIFICATES`; anything else yields an empty set (never trusted by signature).
 */
internal object SignerDigests {
    fun of(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val certificates: Array<Signature> = signing.apkContentsSigners ?: return emptySet()
        return certificates.mapTo(HashSet()) { digest(it.toByteArray()) }
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
