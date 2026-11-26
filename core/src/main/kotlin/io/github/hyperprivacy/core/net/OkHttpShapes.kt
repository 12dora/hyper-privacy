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
 * - `Response`: its `Request` plus the `int` status code.
 */
public object OkHttpShapes {
    public const val HTTP_URL: String = "okhttp3.HttpUrl"
    public const val REQUEST: String = "okhttp3.Request"

    private const val STRING = "java.lang.String"
    private const val INT = "int"
    private const val LIST = "java.util.List"

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
    public fun isResponse(shape: ClassShape, requestType: String): Boolean =
        requestType in shape.fieldTypes && INT in shape.fieldTypes
}
