package io.github.hyperprivacy.xposed.origin

import android.webkit.WebView
import dalvik.system.BaseDexClassLoader
import io.github.hyperprivacy.xposed.Sinks
import io.github.hyperprivacy.xposed.Verdict
import io.github.hyperprivacy.xposed.url.WebClientHooks
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode

/**
 * Web view and class loader creation points of [AdOrigin]:
 *
 * - `WebView` construction decides who owns a web view: an SDK (blocked), the app (never looked at
 *   again) or nobody known. Loads (`loadUrl` / `postUrl` with a URL that is not `javascript:` or
 *   `about:`, `loadData` / `loadDataWithBaseURL` with content, whatever its base URL) decide again
 *   only where that can change the answer: a web view nobody known created is attributed on its
 *   first load and then left alone; a web view an SDK owns is attributed on each load, so a pooled
 *   web view the app takes back (an app load) is the app's again, and one another SDK loads is that
 *   SDK's. So an app's own web view costs at most one stack walk in its lifetime. Every request of
 *   a web view an enabled SDK owns is answered with the neutral empty response ([WebClientHooks]),
 *   except requests to keep hosts. Service-worker fetches belong to no web view, so they cannot be
 *   attributed here; the URL sink still checks them.
 * - `BaseDexClassLoader` construction: a loader an SDK creates (a plugin) makes the classes it
 *   defines count as that SDK. Hook trampolines (in-memory dex under the module's own loaders) are
 *   skipped.
 */
internal class OriginWebGuard(private val hooks: OriginHooks) {
    private val origin get() = hooks.origin

    /** The module's class loader and its ancestors: in-memory dex under them is hook trampolines. */
    private val frameworkLoaders: Set<ClassLoader> =
        generateSequence(OriginWebGuard::class.java.classLoader) { it.parent }
            .filter { it !== Any::class.java.classLoader }
            .toSet()

    /** Returns the number of hooked entry points. */
    fun install(): Int = (hooks.log.guard("origin: WebView") { installWebView() } ?: 0) +
        (hooks.log.guard("origin: class loaders") { installLoaders() } ?: 0)

    private fun installWebView(): Int {
        var count = hooks.creation(WebView::class.java.declaredConstructors.toList(), "webview") { chain ->
            val view = chain.thisObject ?: return@creation
            origin.attribute()?.let { origin.tag(view, it) }
        }
        if (count == 0) return 0
        WebView::class.java.declaredMethods.filter { it.name in LOAD_METHODS }.forEach { method ->
            val urlLoad = method.name in URL_LOADS
            hooks.hook(method, ExceptionMode.PROTECTIVE) { chain ->
                if (origin.active) hooks.safely("webview load") { onLoad(chain, urlLoad) }
                chain.proceed()
            }
            count++
        }
        return count + WebClientHooks.register(hooks.env.xposed, hooks.log, Sinks.ORIGIN_WEBVIEW, ::answer)
    }

    private fun onLoad(chain: Chain, urlLoad: Boolean) {
        val view = chain.thisObject ?: return
        if (!isContentLoad(chain, urlLoad)) return
        val state = origin.tagOf(view)
        // The app's own view, or one already attributed to nobody: never walked again.
        if (state == AdOrigin.OWN || state == AdOrigin.SEEN) return
        val loader = origin.attribute()
        when {
            loader != null -> if (loader != state) origin.tag(view, loader)

            // Nobody known loads into an SDK's view: it stays the SDK's.
            state == null -> origin.tag(view, AdOrigin.SEEN)
        }
    }

    /** Whether this load puts content in the view: a URL other than `javascript:` / `about:`, or data. */
    private fun isContentLoad(chain: Chain, urlLoad: Boolean): Boolean {
        if (urlLoad) {
            val url = chain.getArg(0) as? String ?: return false
            return !url.startsWith(JAVASCRIPT, ignoreCase = true) && !url.startsWith(ABOUT, ignoreCase = true)
        }
        val data = chain.getArg(if (chain.args.size >= DATA_WITH_BASE_ARGS) 1 else 0) as? String
        return !data.isNullOrBlank()
    }

    private fun answer(chain: Chain): Verdict {
        val rule = origin.ruleOf(WebClientHooks.viewOf(chain)) ?: return Verdict.Proceed
        val request = WebClientHooks.requestOf(chain) ?: return Verdict.Proceed
        if (AdOrigin.isKept(request.url?.host)) return Verdict.Proceed
        origin.hit(rule, "webview")
        return Verdict.Return(WebClientHooks.blockedResponse(request))
    }

    private fun installLoaders(): Int {
        val constructors = BaseDexClassLoader::class.java.declaredConstructors.toList()
        return hooks.creation(constructors, "class loader") { chain ->
            val loader = chain.thisObject as? ClassLoader ?: return@creation
            val parent = chain.args.firstNotNullOfOrNull { it as? ClassLoader }
            if (parent != null && parent in frameworkLoaders) return@creation
            hooks.sdkRule()?.let { origin.recordLoader(loader, it) }
        }
    }

    private companion object {
        const val JAVASCRIPT = "javascript:"
        const val ABOUT = "about:"

        /** `loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl)`: data is the second argument. */
        const val DATA_WITH_BASE_ARGS = 5
        val URL_LOADS = setOf("loadUrl", "postUrl")
        val LOAD_METHODS = URL_LOADS + setOf("loadData", "loadDataWithBaseURL")
    }
}
