package io.github.hyperprivacy.xposed.url

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.hyperprivacy.core.net.BlockedWebResponse
import io.github.hyperprivacy.xposed.HookLog
import io.github.hyperprivacy.xposed.SinkStats
import io.github.hyperprivacy.xposed.StackScrub
import io.github.hyperprivacy.xposed.Verdict
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.io.ByteArrayInputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * One process-wide set of hooks on every `WebViewClient.shouldInterceptRequest(WebView,
 * WebResourceRequest)` Chromium can call, shared by every sink that answers WebView requests
 * ([register]: the URL sink, origin blocking): `WebViewClient`'s own implementation (reached by
 * every client that does not override it, and by the null client WebView uses when the app sets
 * none) and every override, found by hooking the `WebViewClient` constructors and inspecting the
 * hierarchy of each new client class once. The constructor hooks are installed at process start,
 * before app code runs, so every client is seen before `setWebViewClient` can hand it to a WebView.
 *
 * Each request is decided once: the registered deciders run in order (each fail-open, the first
 * that does not proceed answers), and an override calling `super.shouldInterceptRequest` for the
 * same request goes straight to the original. Per-class state lives in a [ClassValue] and a weak
 * set, so a plugin's client class never keeps its class loader alive; a class is hooked under a
 * lock before any thread can see it as inspected.
 *
 * The deprecated `shouldInterceptRequest(WebView, String)` is not hooked: Chromium only calls the
 * `WebResourceRequest` overload, whose base implementation is what forwards to the String one.
 */
internal class WebClientHooks private constructor(private val xposed: XposedInterface, private val log: HookLog) {
    private class Entry(val sink: String, val decide: (Chain) -> Verdict) {
        val failures = AtomicInteger()

        @Volatile
        var cell: SinkStats.Cell? = null
    }

    private val entries = CopyOnWriteArrayList<Entry>()

    /** The request whose original is running on this thread (for `super` calls of an override). */
    private val deciding = ThreadLocal<Any?>()

    /** Classes whose overrides are hooked; weak keys, guarded by itself. */
    private val hookedClasses = WeakHashMap<Class<*>, Boolean>()

    private val inspected = object : ClassValue<Boolean>() {
        override fun computeValue(type: Class<*>): Boolean {
            inspect(type)
            return true
        }
    }

    private val dispatcher = Hooker { chain -> dispatch(chain) }
    private var constructors = 0

    private fun install() {
        val base = WebViewClient::class.java
        val hooked = base.declaredConstructors
        hooked.forEach { constructor ->
            xposed.hook(constructor).intercept { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { client -> log.guard("WebView client") { inspected.get(client.javaClass) } }
                result
            }
        }
        synchronized(hookedClasses) { hookedClasses[base] = true }
        val baseEntry = interceptMethod(base)?.also { xposed.hook(it).intercept(dispatcher) }
        constructors = hooked.size
        log.info("WebView: hooked ${hooked.size} constructor(s), base entry ${baseEntry != null}")
    }

    /** Hooks the overrides declared by [type] and its superclasses, each class at most once. */
    private fun inspect(type: Class<*>) = synchronized(hookedClasses) {
        var cls: Class<*>? = type
        while (cls != null && cls != Any::class.java && hookedClasses.put(cls, true) == null) {
            val current = cls
            log.guard("WebView client ${current.name}") {
                interceptMethod(current)?.let {
                    xposed.hook(it).intercept(dispatcher)
                    log.info("WebView: hooked shouldInterceptRequest of ${current.name}")
                }
            }
            cls = current.superclass
        }
    }

    private fun dispatch(chain: Chain): Any? {
        val request = chain.getArg(REQUEST_INDEX)
        if (request != null && deciding.get() === request) return chain.proceed()
        for (entry in entries) {
            when (val verdict = decide(entry, chain)) {
                Verdict.Proceed -> Unit
                is Verdict.Return -> return verdict.value
                is Verdict.Throw -> throw StackScrub.scrub(verdict.error, chain.executable, verdict.top)
                is Verdict.ProceedWith -> return chain.proceed(verdict.args)
            }
        }
        val outer = deciding.get()
        deciding.set(request)
        try {
            return chain.proceed()
        } finally {
            deciding.set(outer)
        }
    }

    /** [entry]'s verdict; a failure of its logic proceeds (bounded log), as in `FailOpenHooker`. */
    private fun decide(entry: Entry, chain: Chain): Verdict = try {
        if (SinkStats.enabled) {
            val cell = entry.cell ?: SinkStats.cell(entry.sink).also { entry.cell = it }
            val start = System.nanoTime()
            try {
                entry.decide(chain)
            } finally {
                cell.record(System.nanoTime() - start)
            }
        } else {
            entry.decide(chain)
        }
    } catch (t: Throwable) {
        if (entry.failures.incrementAndGet() <= MAX_LOGGED_FAILURES) log.error("${entry.sink} hook logic failed", t)
        Verdict.Proceed
    }

    companion object {
        private const val SHOULD_INTERCEPT_REQUEST = "shouldInterceptRequest"
        private const val MAX_LOGGED_FAILURES = 5
        const val VIEW_INDEX = 0
        const val REQUEST_INDEX = 1
        private val EMPTY_BODY = ByteArray(0)
        private val PARAMETER_TYPES = arrayOf(WebView::class.java, WebResourceRequest::class.java)

        private var shared: WebClientHooks? = null

        /**
         * Adds [decide] (measured as sink [sink]) to the process's WebView request hooks, installing
         * them on first use. Returns the number of hooked `WebViewClient` constructors (0: nothing
         * will be seen).
         */
        @Synchronized
        fun register(xposed: XposedInterface, log: HookLog, sink: String, decide: (Chain) -> Verdict): Int {
            val hooks = shared ?: WebClientHooks(xposed, log).also {
                it.install()
                shared = it
            }
            hooks.entries += Entry(sink, decide)
            return hooks.constructors
        }

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
