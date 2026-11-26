package io.github.hyperprivacy.xposed.origin

import android.webkit.WebResourceRequest
import android.webkit.WebView
import dalvik.system.BaseDexClassLoader
import io.github.hyperprivacy.xposed.FailOpenHooker
import io.github.hyperprivacy.xposed.Verdict
import io.github.hyperprivacy.xposed.url.WebClientHooks
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode

/**
 * Web view and class loader creation points of [AdOrigin]:
 *
 * - `WebView` construction, and `loadUrl` / `postUrl` / `loadData*` on a web view of unknown origin
 *   (an SDK loading it first makes it that SDK's; `javascript:` and `about:` loads never do). Every
 *   request of a tagged web view is answered with the neutral empty response ([WebClientHooks]).
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
        var count = 0
        WebView::class.java.declaredConstructors.forEach { constructor ->
            count += hooks.creation(constructor, "webview") { chain ->
                val view = chain.thisObject ?: return@creation
                origin.attribute()?.let { origin.tag(view, it) }
            }
        }
        if (count == 0) return 0
        WebView::class.java.declaredMethods.filter { it.name in LOAD_METHODS }.forEach { method ->
            hooks.hook(method, ExceptionMode.PROTECTIVE) { chain ->
                if (origin.active) hooks.safely("webview load") { onLoad(chain) }
                chain.proceed()
            }
            count++
        }
        val hooker = FailOpenHooker(hooks.log, "origin webview", ::answer)
        return count + WebClientHooks(hooks.env.xposed, hooks.log, hooker).install()
    }

    private fun onLoad(chain: Chain) {
        val view = chain.thisObject ?: return
        val url = chain.getArg(0) as? String
        if (url != null && (url.startsWith(JAVASCRIPT) || url.startsWith(ABOUT))) return
        if (origin.isDecided(view)) return
        origin.attribute()?.let { origin.tag(view, it) }
    }

    private fun answer(chain: Chain): Verdict {
        val rule = origin.ruleOf(chain.getArg(WebClientHooks.VIEW_INDEX)) ?: return Verdict.Proceed
        val request = chain.getArg(WebClientHooks.REQUEST_INDEX) as? WebResourceRequest ?: return Verdict.Proceed
        origin.hit(rule, "webview")
        return Verdict.Return(WebClientHooks.blockedResponse(request))
    }

    private fun installLoaders(): Int {
        val constructors = BaseDexClassLoader::class.java.declaredConstructors
        constructors.forEach { constructor ->
            hooks.creation(constructor, "class loader") { chain ->
                val loader = chain.thisObject as? ClassLoader ?: return@creation
                val parent = chain.args.firstNotNullOfOrNull { it as? ClassLoader }
                if (parent != null && parent in frameworkLoaders) return@creation
                hooks.sdkRule()?.let { origin.recordLoader(loader, it) }
            }
        }
        return constructors.size
    }

    private companion object {
        const val JAVASCRIPT = "javascript:"
        const val ABOUT = "about:"
        val LOAD_METHODS = setOf("loadUrl", "postUrl", "loadData", "loadDataWithBaseURL")
    }
}
