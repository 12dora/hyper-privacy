package io.github.hyperprivacy.core.id

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Per-app replacement of a device-wide identifier: `HMAC-SHA256(secret, real ‖ packageName)`,
 * truncated (or, past 32 bytes, extended with counter blocks) to the real value's length.
 *
 * Stable for one app on one device (same real value, package and secret), different between apps,
 * and not invertible to the real value or linkable across apps without the secret. Pure; the
 * caller supplies the module secret.
 */
public object DerivedIds {
    private const val ALGORITHM = "HmacSHA256"
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val NIBBLE_MASK = 0xF
    private const val DECIMAL = 10
    private const val LETTERS = 26
    private const val BYTE_MASK = 0xFF

    /** The derived value of [real] for [packageName]; same length as [real]. Empty stays empty. */
    public fun derive(real: ByteArray, packageName: String, secret: ByteArray): ByteArray {
        if (real.isEmpty()) return real
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret, ALGORITHM)) }
        val message = real + packageName.toByteArray(Charsets.UTF_8)
        val out = ByteArray(real.size)
        var filled = 0
        var counter = 0
        while (filled < out.size) {
            mac.update(message)
            if (counter > 0) mac.update(counter.toByte())
            val block = mac.doFinal()
            val take = minOf(block.size, out.size - filled)
            block.copyInto(out, filled, 0, take)
            filled += take
            counter++
        }
        return out
    }

    /**
     * [derive] for an id exposed as text, keeping its format so parsers and validators still accept
     * it: separators (`-`, `:` …) stay in place, a hex id stays hex (in its letter case), a decimal id
     * stays decimal; otherwise digits, lower- and upper-case letters keep their class.
     */
    public fun deriveText(real: String, packageName: String, secret: ByteArray): String {
        if (real.isEmpty()) return real
        val bytes = derive(real.toByteArray(Charsets.UTF_8), packageName, secret)
        val alnum = real.filter { it.isLetterOrDigit() }
        val decimal = alnum.all { it in '0'..'9' }
        val hex = !decimal && alnum.all { Character.digit(it, HEX_DIGITS.length) >= 0 }
        val upper = alnum.any { it in 'A'..'F' }
        return buildString(real.length) {
            real.forEachIndexed { i, char ->
                val b = bytes[i % bytes.size].toInt() and BYTE_MASK
                append(
                    when {
                        !char.isLetterOrDigit() -> char
                        decimal -> '0' + b % DECIMAL
                        hex -> HEX_DIGITS[b and NIBBLE_MASK].let { if (upper) it.uppercaseChar() else it }
                        char in '0'..'9' -> '0' + b % DECIMAL
                        char in 'a'..'z' -> 'a' + b % LETTERS
                        char in 'A'..'Z' -> 'A' + b % LETTERS
                        else -> char
                    },
                )
            }
        }
    }
}
