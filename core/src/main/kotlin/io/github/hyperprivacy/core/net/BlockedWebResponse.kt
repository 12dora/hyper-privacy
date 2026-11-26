package io.github.hyperprivacy.core.net

/**
 * What a WebView gets instead of a blocked request: `204 No Content` with an empty body.
 *
 * 204 rather than 403/404 or a network error: RUM and beacon SDKs treat any 2xx as delivered and
 * drop the batch, whereas errors make many of them queue and retry (more wake-ups, same block).
 * `WebResourceResponse` rejects 3xx codes and an empty reason phrase; 204 with a reason is valid.
 *
 * For a cross-origin `fetch` / XHR (the request carries `Origin`) the response echoes that origin
 * and allows credentials, so the page sees a success instead of a CORS failure; a preflight gets
 * its requested method and headers allowed.
 */
public object BlockedWebResponse {
    public const val STATUS: Int = 204
    public const val REASON: String = "No Content"
    public const val MIME_TYPE: String = "text/plain"
    public const val ENCODING: String = "utf-8"

    private const val ORIGIN = "Origin"
    private const val REQUEST_METHOD = "Access-Control-Request-Method"
    private const val REQUEST_HEADERS = "Access-Control-Request-Headers"
    private const val ALLOW_ORIGIN = "Access-Control-Allow-Origin"
    private const val ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials"
    private const val ALLOW_METHODS = "Access-Control-Allow-Methods"
    private const val ALLOW_HEADERS = "Access-Control-Allow-Headers"
    private const val VARY = "Vary"

    /** Response headers for a blocked request with [requestHeaders] (names matched case-insensitively). */
    public fun headers(requestHeaders: Map<String, String>): Map<String, String> {
        val origin = requestHeaders.valueOf(ORIGIN) ?: return emptyMap()
        return buildMap {
            put(ALLOW_ORIGIN, origin)
            put(ALLOW_CREDENTIALS, true.toString())
            put(VARY, ORIGIN)
            requestHeaders.valueOf(REQUEST_METHOD)?.let { put(ALLOW_METHODS, it) }
            requestHeaders.valueOf(REQUEST_HEADERS)?.let { put(ALLOW_HEADERS, it) }
        }
    }

    private fun Map<String, String>.valueOf(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
