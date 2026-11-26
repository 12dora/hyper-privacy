package io.github.hyperprivacy.core.config

/**
 * The module's random secret for per-app identifier derivation ([io.github.hyperprivacy.core.id.DerivedIds]).
 *
 * Generated once by the module app on first run and stored as [BYTES] random bytes in lowercase hex
 * under [KEY] in the remote preferences group [ConfigCodec.PREFS_GROUP], which hooked processes
 * read. It is never shown, exported or reset with the rule switches: a new secret would hand every
 * app a new derived id. Without it hooked processes leave identifiers untouched (fail-open).
 */
public object ModuleSecret {
    public const val KEY: String = "secret.id"
    public const val BYTES: Int = 32

    private const val HEX = "0123456789abcdef"
    private const val NIBBLE = 4
    private const val LOW_NIBBLE = 0xF
    private const val HEX_RADIX = 16

    /** The stored form of [bytes] ([BYTES] bytes from a `SecureRandom`, supplied by the caller). */
    public fun encode(bytes: ByteArray): String {
        require(bytes.size == BYTES) { "secret must be $BYTES bytes" }
        return buildString(BYTES * 2) {
            bytes.forEach { byte ->
                append(HEX[(byte.toInt() shr NIBBLE) and LOW_NIBBLE])
                append(HEX[byte.toInt() and LOW_NIBBLE])
            }
        }
    }

    /** The secret bytes of a stored [raw] value, or `null` when absent or malformed. */
    public fun parse(raw: Any?): ByteArray? {
        val text = raw as? String ?: return null
        if (text.length != BYTES * 2) return null
        val out = ByteArray(BYTES)
        for (i in 0 until BYTES) {
            val high = Character.digit(text[2 * i], HEX_RADIX)
            val low = Character.digit(text[2 * i + 1], HEX_RADIX)
            if (high < 0 || low < 0) return null
            out[i] = ((high shl NIBBLE) or low).toByte()
        }
        return out
    }
}
