package io.github.hyperprivacy.core.status

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The status relay credentials of one protected package ([StatusRelay]), derived from the module
 * secret: the [token] that names the package in a relay call, and the key that seals its payload
 * (AES-GCM, random nonce). Both differ per package, so what one app learns about its own relay
 * says nothing about another app's.
 *
 * Immutable; [seal] and [open] are thread-safe (each call builds its own cipher).
 */
public class RelayKeys private constructor(
    public val packageName: String,
    public val token: String,
    private val key: SecretKeySpec,
) {
    /** [plaintext] sealed: nonce ‖ ciphertext ‖ tag. */
    public fun seal(plaintext: ByteArray, random: SecureRandom): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        }
        val out = ByteArray(NONCE_BYTES + cipher.getOutputSize(plaintext.size))
        nonce.copyInto(out)
        cipher.doFinal(plaintext, 0, plaintext.size, out, NONCE_BYTES)
        return out
    }

    /** The plaintext of [sealed], or `null` when it was not sealed with this key or is malformed. */
    public fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size < OVERHEAD) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES))
            }
            cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    public companion object {
        /** Length of [token]: 32 lowercase hex characters. */
        public const val TOKEN_LENGTH: Int = 32

        /** Bytes [seal] adds to the plaintext. */
        public const val OVERHEAD: Int = 12 + 16

        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BYTES = 16
        private const val HMAC = "HmacSHA256"
        private const val AES = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TOKEN_LABEL = "hyperprivacy/status-relay/token/v2:"
        private const val KEY_LABEL = "hyperprivacy/status-relay/key/v2:"
        private const val HEX = "0123456789abcdef"
        private const val NIBBLE = 4
        private const val NIBBLE_MASK = 0xF

        /** The credentials of [packageName] under the module [secret]. */
        public fun derive(secret: ByteArray, packageName: String): RelayKeys {
            val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(secret, HMAC)) }
            val tokenBytes = mac.doFinal((TOKEN_LABEL + packageName).toByteArray(Charsets.UTF_8))
            val keyBytes = mac.doFinal((KEY_LABEL + packageName).toByteArray(Charsets.UTF_8))
            val token = buildString(TOKEN_LENGTH) {
                for (i in 0 until TOKEN_LENGTH / 2) {
                    val value = tokenBytes[i].toInt()
                    append(HEX[(value shr NIBBLE) and NIBBLE_MASK]).append(HEX[value and NIBBLE_MASK])
                }
            }
            return RelayKeys(packageName, token, SecretKeySpec(keyBytes, 0, KEY_BYTES, AES))
        }
    }
}
