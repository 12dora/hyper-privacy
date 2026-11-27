package io.github.hyperprivacy.core.net

/**
 * The instance-field types of one class, as binary names (`java.lang.String`, `int`, `okhttp3.x`).
 * Enough to recognise OkHttp's value classes when R8 has renamed them and their members.
 */
public data class ClassShape(val name: String, val fieldTypes: List<String>)

/**
 * Structural recognition of OkHttp's `HttpUrl`, `Request` and `Response` in partially obfuscated
 * builds (class and member names renamed). Real names always win; the shapes below hold for
 * OkHttp 3.x, 4.x and 5.x:
 * - `HttpUrl`: scheme, username, password, host, fragment and url as Strings, an `int` port and
 *   `List` path segments;
 * - `Request`: an `HttpUrl` field plus the `String` method (which tells it apart from `Address`,
 *   the other OkHttp class holding an `HttpUrl`);
 * - `Response`: its `Request`, the `int` status code, the `String` message and the two `long`
 *   timestamps (sent / received at); a class merely holding a `Request` and an `int` is not one.
 */
public object OkHttpShapes {
    public const val HTTP_URL: String = "okhttp3.HttpUrl"
    public const val REQUEST: String = "okhttp3.Request"

    private const val STRING = "java.lang.String"
    private const val INT = "int"
    private const val LIST = "java.util.List"
    private const val LONG = "long"

    /** `Response.sentRequestAtMillis` and `receivedResponseAtMillis`. */
    private const val RESPONSE_LONGS = 2

    /** An `HttpUrl` has at least scheme, host and url plus two of username / password / fragment. */
    private const val MIN_URL_STRINGS = 4

    public fun isHttpUrl(shape: ClassShape): Boolean {
        if (shape.name == HTTP_URL) return true
        val types = shape.fieldTypes
        return types.count { it == STRING } >= MIN_URL_STRINGS && INT in types && LIST in types
    }

    /** [isUrlType] tells whether a field type name is an `HttpUrl`. */
    public fun isRequest(shape: ClassShape, isUrlType: (String) -> Boolean): Boolean = shape.name == REQUEST ||
        (STRING in shape.fieldTypes && shape.fieldTypes.any(isUrlType))

    /** Whether [shape] is a `Response` to requests of type [requestType]. */
    public fun isResponse(shape: ClassShape, requestType: String): Boolean {
        val types = shape.fieldTypes
        return requestType in types && INT in types && STRING in types && types.count { it == LONG } >= RESPONSE_LONGS
    }
}

/**
 * Which methods of an OkHttp call class are its execution entry, the method both `execute()` and
 * the async path (`AsyncCall.run`, which never goes through `execute()`) call:
 * `getResponseWithInterceptorChain` by name (OkHttp 4 mangles it to `…$okhttp`); when R8 renamed it,
 * the no-arg methods returning `Response` minus `execute()` (it only forwards to the entry) unless
 * nothing else remains. Those declaring `IOException` are preferred. R8 may drop the entry's
 * `throws` clause; it is then taken only when it is unambiguous (exactly one such method) and the
 * call contract still proves that `IOException` travels through it: another `Response` method of
 * the class (`execute()`) or of an interface it implements (`Call.execute()`, [contractThrowsIo])
 * declares it. Otherwise nothing is hooked (the sink stays off rather than throw a checked
 * exception into a helper such as a cached-response getter that nobody guards).
 */
public object OkHttpEntries {
    public const val ENTRY: String = "getResponseWithInterceptorChain"
    public const val EXECUTE: String = "execute"

    public fun <T> select(
        candidates: List<T>,
        name: (T) -> String,
        isResponse: (T) -> Boolean,
        throwsIo: (T) -> Boolean,
        contractThrowsIo: Boolean = false,
    ): List<T> {
        val named = candidates.filter { name(it) == ENTRY || name(it).startsWith("$ENTRY$") }
        if (named.isNotEmpty()) return named
        val shaped = candidates.filter(isResponse)
        val preferred = shaped.filter { name(it) != EXECUTE }.ifEmpty { shaped }
        val throwing = preferred.filter(throwsIo)
        if (throwing.isNotEmpty()) return throwing
        val proven = contractThrowsIo || shaped.any(throwsIo)
        return if (proven && preferred.size == 1) preferred else emptyList()
    }
}
