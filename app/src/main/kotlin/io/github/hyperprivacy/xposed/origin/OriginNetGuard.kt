package io.github.hyperprivacy.xposed.origin

import android.annotation.SuppressLint
import io.github.hyperprivacy.xposed.NetErrors
import io.github.hyperprivacy.xposed.Reflection
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
import java.net.URL
import java.nio.channels.SocketChannel
import java.nio.channels.spi.SelectorProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Network creation points of [AdOrigin] and the sinks that fail what was tagged, with the regular
 * neutral network errors. Each one is optional; a missing one is skipped.
 *
 * - OkHttp call construction (`RealCall`, public names), or `OkHttpClient.newCall` when the call
 *   class was renamed; a tagged call fails in its execution entry like a network error, for
 *   `execute()` and `enqueue()` alike, so async dispatch keeps the origin;
 * - `URL.openConnection`, which decides and fails at once;
 * - `Socket` and `SocketChannel` construction, failed in `Socket.connect` / the channel's connect;
 * - Volley `Request` construction (public names), failed in `BasicNetwork.performRequest`.
 */
internal class OriginNetGuard(private val hooks: OriginHooks) {
    private val origin get() = hooks.origin
    private val loader get() = hooks.env.classLoader
    private val readers = ConcurrentHashMap<Class<*>, OkHttpUrlReader>()
    private val hookedCallClasses: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()

    /** Returns the number of hooked entry points. */
    fun install(): Int = listOf(
        "OkHttp" to ::installOkHttp,
        "URL" to ::installUrl,
        "sockets" to ::installSockets,
        "Volley" to ::installVolley,
    ).sumOf { (name, install) -> hooks.log.guard("origin: $name") { install() } ?: 0 }

    private fun installOkHttp(): Int {
        var count = 0
        OkHttpCalls.callClasses(loader).forEach { type ->
            if (hookCallEntries(type) == 0) return@forEach
            type.declaredConstructors.forEach { count += hooks.creation(it, "okhttp call", hooks::tagSdk) }
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
                    if (call.javaClass !in hookedCallClasses) {
                        hooks.safely("call class") { hookCallEntries(call.javaClass) }
                    }
                }
                call
            }
        }
        return newCall.size
    }

    private fun hookCallEntries(type: Class<*>): Int {
        if (!hookedCallClasses.add(type)) return 0
        val reader = OkHttpUrlReader.resolve(type) ?: return 0
        readers[type] = reader
        val entries = OkHttpCalls.entries(type, reader)
        entries.forEach { hooks.sink(it, "okhttp exec", ::failCall) }
        return entries.size
    }

    private fun failCall(chain: Chain): Verdict {
        val call = chain.thisObject ?: return Verdict.Proceed
        val rule = origin.ruleOf(call) ?: return Verdict.Proceed
        val url = readers[call.javaClass]?.let { runCatching { it.read(call) }.getOrNull() }
        origin.hit(rule, "okhttp")
        return Verdict.Throw(hooks.httpFailure(url))
    }

    private fun installUrl(): Int {
        val methods = listOfNotNull(
            runCatching { URL::class.java.getDeclaredMethod(OPEN_CONNECTION) }.getOrNull(),
            runCatching { URL::class.java.getDeclaredMethod(OPEN_CONNECTION, Proxy::class.java) }.getOrNull(),
        )
        methods.forEach { hooks.sink(it, "url", ::failUrl) }
        return methods.size
    }

    private fun failUrl(chain: Chain): Verdict {
        if (!origin.active) return Verdict.Proceed
        val url = chain.thisObject as? URL ?: return Verdict.Proceed
        if (url.protocol != HTTP && url.protocol != HTTPS) return Verdict.Proceed
        val rule = hooks.sdkRule() ?: return Verdict.Proceed
        origin.hit(rule, "url")
        return Verdict.Throw(hooks.httpFailure(url.host, url.protocol, url.port))
    }

    // sun.nio.ch is core-platform API reachable from module code; a miss only skips the NIO sink.
    @SuppressLint("PrivateApi")
    private fun installSockets(): Int {
        var count = 0
        Socket::class.java.declaredConstructors.forEach { count += hooks.creation(it, "socket", hooks::tagSdk) }
        runCatching {
            Socket::class.java.getDeclaredMethod(CONNECT, SocketAddress::class.java, Int::class.javaPrimitiveType)
        }.getOrNull()?.let {
            hooks.sink(it, "socket connect", ::failSocket)
            count++
        }
        runCatching { SocketChannel::class.java.getDeclaredConstructor(SelectorProvider::class.java) }
            .getOrNull()?.let { count += hooks.creation(it, "socket channel", hooks::tagSdk) }
        val channelImpl = runCatching { Class.forName(SOCKET_CHANNEL_IMPL) }.getOrNull()
        channelImpl?.declaredMethods?.filter {
            it.name == CONNECT && it.parameterTypes.contentEquals(arrayOf(SocketAddress::class.java))
        }?.forEach {
            hooks.sink(it, "channel connect", ::failChannel)
            count++
        }
        return count
    }

    private fun failSocket(chain: Chain): Verdict {
        val rule = origin.ruleOf(chain.thisObject) ?: return Verdict.Proceed
        origin.hit(rule, "socket")
        val target = chain.getArg(0) as? InetSocketAddress
        val address = target?.address ?: return Verdict.Throw(NetErrors.nioConnectRefused())
        val timeout = chain.getArg(1) as? Int ?: 0
        return Verdict.Throw(NetErrors.connectRefused(address, target.port, timeout, null, 0))
    }

    private fun failChannel(chain: Chain): Verdict {
        val rule = origin.ruleOf(chain.thisObject) ?: return Verdict.Proceed
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
        var count = 0
        request.declaredConstructors.forEach { count += hooks.creation(it, "volley request", hooks::tagSdk) }
        perform.forEach { method ->
            hooks.sink(method, "volley perform") { chain -> failVolley(chain, error, getUrl) }
            count++
        }
        return count
    }

    private fun failVolley(chain: Chain, error: Constructor<*>, getUrl: Method?): Verdict {
        val request = chain.getArg(0) ?: return Verdict.Proceed
        val rule = origin.ruleOf(request) ?: return Verdict.Proceed
        val url = getUrl?.let { runCatching { it.invoke(request) as? String }.getOrNull() }
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
