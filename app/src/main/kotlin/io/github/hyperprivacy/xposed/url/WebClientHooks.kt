package io.github.hyperprivacy.xposed.url

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.hyperprivacy.core.net.BlockedWebResponse
import io.github.hyperprivacy.xposed.HookLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import java.io.ByteArrayInputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Puts [hooker] on every `WebViewClient.shouldInterceptRequest(WebView, WebResourceRequest)`
 * Chromium can call: `WebViewClient`'s own implementation (reached by every client that does not
 * override it, and by the null client WebView uses when the app sets none) and every override,
 * found by hooking the `WebViewClient` constructors and inspecting the hierarchy of each new
 * client class once. The constructor hooks are installed at process start, before app code runs,
 * so every client is seen before `setWebViewClient` can hand it to a WebView.
 *
 * The deprecated `shouldInterceptRequest(WebView, String)` is not hooked: Chromium only calls the
 * `WebResourceRequest` overload, whose base implementation is what forwards to the String one.
 */
internal class WebClientHooks(
    private val xposed: XposedInterface,
    private val log: HookLog,
    private val hooker: Hooker,
) {
    private val inspected: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()

    /** Returns the number of hooked `WebViewClient` constructors (0: nothing will be seen). */
    fun install(): Int {
        val base = WebViewClient::class.java
        val constructors = base.declaredConstructors
        constructors.forEach { constructor ->
            xposed.hook(constructor).intercept { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { onClient(it.javaClass) }
                result
            }
        }
        inspected.add(base)
        val baseEntry = interceptMethod(base)?.also { xposed.hook(it).intercept(hooker) }
        log.info("WebView: hooked ${constructors.size} constructor(s), base entry ${baseEntry != null}")
        return constructors.size
    }

    /** Hooks the overrides declared by [type] and its superclasses, each class at most once. */
    private fun onClient(type: Class<*>) {
        if (type in inspected) return
        log.guard("WebView client ${type.name}") {
            generateSequence(type) { it.superclass }
                .takeWhile { inspected.add(it) }
                .forEach { cls ->
                    interceptMethod(cls)?.let {
                        xposed.hook(it).intercept(hooker)
                        log.info("WebView: hooked shouldInterceptRequest of ${cls.name}")
                    }
                }
        }
    }

    companion object {
        private const val SHOULD_INTERCEPT_REQUEST = "shouldInterceptRequest"
        const val VIEW_INDEX = 0
        const val REQUEST_INDEX = 1
        private val EMPTY_BODY = ByteArray(0)
        private val PARAMETER_TYPES = arrayOf(WebView::class.java, WebResourceRequest::class.java)

        /** The concrete `shouldInterceptRequest(WebView, WebResourceRequest)` declared by [cls], if any. */
        private fun interceptMethod(cls: Class<*>): Method? = cls.declaredMethods.firstOrNull {
            it.name == SHOULD_INTERCEPT_REQUEST && !Modifier.isAbstract(it.modifiers) && !it.isBridge &&
                it.parameterTypes.contentEquals(PARAMETER_TYPES)
        }

        /** The neutral answer to a blocked request: [BlockedWebResponse] (empty 204). */
        fun blockedResponse(request: WebResourceRequest) = WebResourceResponse(
            BlockedWebResponse.MIME_TYPE,
            BlockedWebResponse.ENCODING,
            BlockedWebResponse.STATUS,
            BlockedWebResponse.REASON,
            BlockedWebResponse.headers(request.requestHeaders.orEmpty()),
            ByteArrayInputStream(EMPTY_BODY),
        )
    }
}
