package io.github.hyperprivacy.xposed.origin

import android.annotation.SuppressLint
import io.github.hyperprivacy.xposed.NetErrors
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.Sinks
import io.github.hyperprivacy.xposed.Verdict
import io.github.hyperprivacy.xposed.url.OkHttpCalls
import io.github.hyperprivacy.xposed.url.OkHttpUrlReader
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketImpl
import java.net.URL
import java.nio.channels.SocketChannel
import java.nio.channels.spi.SelectorProvider
import java.util.WeakHashMap
import javax.net.ssl.SSLSocket

/**
 * Network creation points of [AdOrigin] and the sinks that fail what was tagged, with the regular
 * neutral network errors. Each one is optional; a missing one is skipped. A request to a keep host
 * ([AdOrigin.isKept]) is never failed.
 *
 * - OkHttp call construction (`RealCall`, public names), or `OkHttpClient.newCall` when the call
 *   class was renamed; a tagged call fails in its execution entry like a network error, for
 *   `execute()` and `enqueue()` alike, so async dispatch keeps the origin. Entries are found by
 *   name even when the request URL cannot be read in this build;
 * - `URL.openConnection`, which decides and fails at once;
 * - `Socket` and `SocketChannel` construction, failed in `Socket.connect` / the channel's connect.
 *   Sockets the platform makes for itself are not attributed: accepted sockets (`Socket(SocketImpl)`)
 *   and TLS sockets layered over an existing one (`SSLSocket()`, never connected themselves);
 * - Volley `Request` construction (public names), failed in `BasicNetwork.performRequest`;
 * - Cronet request builders ([OriginCronetGuard]).
 *
 * Per-class state (call classes seen at runtime) lives in a [ClassValue], so it never keeps a
 * plugin's class loader alive; a class is hooked under a lock before any thread can see it as done.
 */
internal class OriginNetGuard(private val hooks: OriginHooks) {
    private val origin get() = hooks.origin
    private val loader get() = hooks.env.classLoader

    /** One OkHttp call class: its URL reader (if resolvable) and how many entries were hooked. */
    private class CallClass(val reader: OkHttpUrlReader?, val hooked: Int)

    /** Call classes whose entries are hooked; weak keys, guarded by itself (first sight of a class only). */
    private val hookedCallClasses = WeakHashMap<Class<*>, Boolean>()

    private val callClasses = object : ClassValue<CallClass>() {
        override fun computeValue(type: Class<*>): CallClass = hookCallEntries(type)
    }

    /** Returns the number of hooked entry points. */
    fun install(): Int = listOf(
        "OkHttp" to ::installOkHttp,
        "URL" to ::installUrl,
        "sockets" to ::installSockets,
        "Volley" to ::installVolley,
        "Cronet" to { OriginCronetGuard(hooks).install() },
    ).sumOf { (name, install) -> hooks.log.guard("origin: $name") { install() } ?: 0 }

    private fun installOkHttp(): Int {
        var count = 0
        OkHttpCalls.callClasses(loader).forEach { type ->
            if (callClasses.get(type).hooked == 0) return@forEach
            count += hooks.creation(type.declaredConstructors.toList(), "okhttp call", hooks::tagSdk)
        }
        if (count > 0) return count
        // Renamed call class: tag what newCall returns, and hook that class's entries on first sight.
        val client = Reflection.loadClass(OKHTTP_CLIENT, loader) ?: return 0
        val newCall = client.declaredMethods.filter { it.name == NEW_CALL && it.parameterCount == 1 }
        newCall.forEach { method ->
            hooks.hook(method, ExceptionMode.PROTECTIVE) { chain ->
                val rule = if (origin.active) hooks.safely("newCall") { hooks.sdkRule() } else null
                val call = chain.proceed()
                if (call != null && rule != null) {
                    origin.tag(call, rule)
                    hooks.safely("call class") { callClasses.get(call.javaClass) }
                }
                call
            }
        }
        return newCall.size
    }

    /** Hooks the execution entries of call class [type], once per class whichever thread sees it first. */
    private fun hookCallEntries(type: Class<*>): CallClass = synchronized(hookedCallClasses) {
        val reader = hooks.safely("okhttp reader") { OkHttpUrlReader.resolve(type) }
        if (hookedCallClasses.put(type, true) != null) return CallClass(reader, 0)
        val entries = OkHttpCalls.entries(type, reader)
        entries.forEach { hooks.sink(it, Sinks.ORIGIN_OKHTTP, ::failCall) }
        CallClass(reader, entries.size)
    }

    private fun failCall(chain: Chain): Verdict {
        val call = chain.thisObject ?: return Verdict.Proceed
        val rule = origin.ruleOf(call) ?: return Verdict.Proceed
        val url = callClasses.get(call.javaClass).reader?.let { runCatching { it.read(call) }.getOrNull() }
        if (AdOrigin.isKept(hooks.hostOf(url))) return Verdict.Proceed
        origin.hit(rule, "okhttp")
        return Verdict.Throw(hooks.httpFailure(url))
    }

    private fun installUrl(): Int {
        val methods = listOfNotNull(
            runCatching { URL::class.java.getDeclaredMethod(OPEN_CONNECTION) }.getOrNull(),
            runCatching { URL::class.java.getDeclaredMethod(OPEN_CONNECTION, Proxy::class.java) }.getOrNull(),
        )
        methods.forEach { hooks.sink(it, Sinks.ORIGIN_URL, ::failUrl) }
        return methods.size
    }

    private fun failUrl(chain: Chain): Verdict {
        if (!origin.active) return Verdict.Proceed
        val url = chain.thisObject as? URL ?: return Verdict.Proceed
        if (url.protocol != HTTP && url.protocol != HTTPS) return Verdict.Proceed
        if (AdOrigin.isKept(url.host)) return Verdict.Proceed
        val rule = hooks.sdkRule() ?: return Verdict.Proceed
        origin.hit(rule, "url")
        return Verdict.Throw(hooks.httpFailure(url.host, url.protocol, url.port))
    }

    // sun.nio.ch is core-platform API reachable from module code; a miss only skips the NIO sink.
    @SuppressLint("PrivateApi")
    private fun installSockets(): Int {
        // Accepted sockets are built around a SocketImpl by ServerSocket: inbound, never attributed.
        val constructors = Socket::class.java.declaredConstructors.filterNot {
            it.parameterCount == 1 && it.parameterTypes[0] == SocketImpl::class.java
        }
        // A TLS socket built with no address is layered over a connected one (every HTTPS connection
        // of OkHttp and HttpsURLConnection) and never connects itself: not worth a stack walk.
        var count = hooks.creation(constructors, "socket") { chain ->
            if (chain.thisObject !is SSLSocket || chain.args.isNotEmpty()) hooks.tagSdk(chain)
        }
        runCatching {
            Socket::class.java.getDeclaredMethod(CONNECT, SocketAddress::class.java, Int::class.javaPrimitiveType)
        }.getOrNull()?.let {
            hooks.sink(it, Sinks.ORIGIN_SOCKET, ::failSocket)
            count++
        }
        runCatching { SocketChannel::class.java.getDeclaredConstructor(SelectorProvider::class.java) }
            .getOrNull()?.let { count += hooks.creation(listOf(it), "socket channel", hooks::tagSdk) }
        val channelImpl = runCatching { Class.forName(SOCKET_CHANNEL_IMPL) }.getOrNull()
        channelImpl?.declaredMethods?.filter {
            it.name == CONNECT && it.parameterTypes.contentEquals(arrayOf(SocketAddress::class.java))
        }?.forEach {
            hooks.sink(it, Sinks.ORIGIN_CHANNEL, ::failChannel)
            count++
        }
        return count
    }

    private fun failSocket(chain: Chain): Verdict {
        val rule = origin.ruleOf(chain.thisObject) ?: return Verdict.Proceed
        val target = chain.getArg(0) as? InetSocketAddress
        if (AdOrigin.isKept(target?.hostString)) return Verdict.Proceed
        origin.hit(rule, "socket")
        val address = target?.address ?: return Verdict.Throw(NetErrors.nioConnectRefused())
        val timeout = chain.getArg(1) as? Int ?: 0
        return Verdict.Throw(NetErrors.connectRefused(address, target.port, timeout, null, 0))
    }

    private fun failChannel(chain: Chain): Verdict {
        val rule = origin.ruleOf(chain.thisObject) ?: return Verdict.Proceed
        if (AdOrigin.isKept((chain.getArg(0) as? InetSocketAddress)?.hostString)) return Verdict.Proceed
        origin.hit(rule, "socket")
        return Verdict.Throw(NetErrors.nioConnectRefused())
    }

    private fun installVolley(): Int {
        val request = Reflection.loadClass(VOLLEY_REQUEST, loader) ?: return 0
        val network = Reflection.loadClass(VOLLEY_NETWORK, loader) ?: return 0
        val errorType = Reflection.loadClass(VOLLEY_NO_CONNECTION, loader) ?: return 0
        val error = runCatching { errorType.getConstructor(Throwable::class.java) }.getOrNull() ?: return 0
        val perform = network.declaredMethods.filter {
            it.name == PERFORM_REQUEST && it.parameterCount == 1 && it.parameterTypes[0] == request
        }
        if (perform.isEmpty()) return 0
        val getUrl = runCatching { request.getMethod(GET_URL) }.getOrNull()
        var count = hooks.creation(request.declaredConstructors.toList(), "volley request", hooks::tagSdk)
        perform.forEach { method ->
            hooks.sink(method, Sinks.ORIGIN_VOLLEY) { chain -> failVolley(chain, error, getUrl) }
            count++
        }
        return count
    }

    private fun failVolley(chain: Chain, error: Constructor<*>, getUrl: Method?): Verdict {
        val request = chain.getArg(0) ?: return Verdict.Proceed
        val rule = origin.ruleOf(request) ?: return Verdict.Proceed
        val url = getUrl?.let { runCatching { it.invoke(request) as? String }.getOrNull() }
        if (AdOrigin.isKept(hooks.hostOf(url))) return Verdict.Proceed
        val failure = error.newInstance(hooks.httpFailure(url)) as? Throwable ?: return Verdict.Proceed
        origin.hit(rule, "volley")
        return Verdict.Throw(failure)
    }

    private companion object {
        const val OKHTTP_CLIENT = "okhttp3.OkHttpClient"
        const val NEW_CALL = "newCall"
        const val OPEN_CONNECTION = "openConnection"
        const val CONNECT = "connect"
        const val HTTP = "http"
        const val HTTPS = "https"
        const val SOCKET_CHANNEL_IMPL = "sun.nio.ch.SocketChannelImpl"
        const val VOLLEY_REQUEST = "com.android.volley.Request"
        const val VOLLEY_NETWORK = "com.android.volley.toolbox.BasicNetwork"
        const val VOLLEY_NO_CONNECTION = "com.android.volley.NoConnectionError"
        const val PERFORM_REQUEST = "performRequest"
        const val GET_URL = "getUrl"
    }
}
