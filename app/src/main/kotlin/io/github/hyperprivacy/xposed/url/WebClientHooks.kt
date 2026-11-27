package io.github.hyperprivacy.xposed.url

import android.webkit.ServiceWorkerClient
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
 *
 * Service workers fetch outside any WebView: Chromium asks the process's `ServiceWorkerClient`
 * (`shouldInterceptRequest(WebResourceRequest)`, API 24; androidx `ServiceWorkerClientCompat`
 * reaches it through a framework subclass) instead. [registerServiceWorker] hooks that surface
 * the same way, as a separate instance. Deciders read the request with [requestOf], which works
 * for both; a service-worker request has no view ([viewOf] is `null`).
 */
internal class WebClientHooks private constructor(
    private val xposed: XposedInterface,
    private val log: HookLog,
    private val surface: Surface,
) {
    /** A client class whose `shouldInterceptRequest` Chromium calls: its base class and parameters. */
    private class Surface(val name: String, val base: Class<*>, val parameterTypes: Array<Class<*>>) {
        val requestIndex: Int = parameterTypes.size - 1
    }

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
        val base = surface.base
        val hooked = base.declaredConstructors
        hooked.forEach { constructor ->
            xposed.hook(constructor).intercept { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { client ->
                    log.guard("${surface.name} client") { inspected.get(client.javaClass) }
                }
                result
            }
        }
        synchronized(hookedClasses) { hookedClasses[base] = true }
        val baseEntry = interceptMethod(base)?.also { xposed.hook(it).intercept(dispatcher) }
        constructors = hooked.size
        log.info("${surface.name}: hooked ${hooked.size} constructor(s), base entry ${baseEntry != null}")
    }

    /** Hooks the overrides declared by [type] and its superclasses, each class at most once. */
    private fun inspect(type: Class<*>) = synchronized(hookedClasses) {
        var cls: Class<*>? = type
        while (cls != null && cls != Any::class.java && hookedClasses.put(cls, true) == null) {
            val current = cls
            log.guard("${surface.name} client ${current.name}") {
                interceptMethod(current)?.let {
                    xposed.hook(it).intercept(dispatcher)
                    log.info("${surface.name}: hooked shouldInterceptRequest of ${current.name}")
                }
            }
            cls = current.superclass
        }
    }

    private fun dispatch(chain: Chain): Any? {
        val request = chain.getArg(surface.requestIndex)
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

    /** The concrete `shouldInterceptRequest` of this surface declared by [cls], if any. */
    private fun interceptMethod(cls: Class<*>): Method? = cls.declaredMethods.firstOrNull {
        it.name == SHOULD_INTERCEPT_REQUEST && !Modifier.isAbstract(it.modifiers) && !it.isBridge &&
            it.parameterTypes.contentEquals(surface.parameterTypes)
    }

    companion object {
        private const val SHOULD_INTERCEPT_REQUEST = "shouldInterceptRequest"
        private const val MAX_LOGGED_FAILURES = 5
        private val EMPTY_BODY = ByteArray(0)
        private val WEB_VIEW = Surface(
            "WebView",
            WebViewClient::class.java,
            arrayOf(WebView::class.java, WebResourceRequest::class.java),
        )
        private val SERVICE_WORKER = Surface(
            "ServiceWorker",
            ServiceWorkerClient::class.java,
            arrayOf(WebResourceRequest::class.java),
        )

        private val shared = HashMap<Surface, WebClientHooks>()

        /** The request a hooked `shouldInterceptRequest` of either surface was called with. */
        fun requestOf(chain: Chain): WebResourceRequest? = chain.args.lastOrNull() as? WebResourceRequest

        /** The WebView of a hooked `WebViewClient` call; `null` for a service-worker request. */
        fun viewOf(chain: Chain): WebView? = chain.args.firstOrNull() as? WebView

        /**
         * Adds [decide] (measured as sink [sink]) to the process's WebView request hooks, installing
         * them on first use. Returns the number of hooked `WebViewClient` constructors (0: nothing
         * will be seen).
         */
        fun register(xposed: XposedInterface, log: HookLog, sink: String, decide: (Chain) -> Verdict): Int =
            register(WEB_VIEW, xposed, log, sink, decide)

        /**
         * [register] for service-worker requests (`ServiceWorkerClient`); returns the number of hooked
         * `ServiceWorkerClient` constructors.
         */
        fun registerServiceWorker(
            xposed: XposedInterface,
            log: HookLog,
            sink: String,
            decide: (Chain) -> Verdict,
        ): Int = register(SERVICE_WORKER, xposed, log, sink, decide)

        @Synchronized
        private fun register(
            surface: Surface,
            xposed: XposedInterface,
            log: HookLog,
            sink: String,
            decide: (Chain) -> Verdict,
        ): Int {
            val hooks = shared.getOrPut(surface) { WebClientHooks(xposed, log, surface).also { it.install() } }
            hooks.entries += Entry(sink, decide)
            return hooks.constructors
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
