package io.github.hyperprivacy.xposed

import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.util.concurrent.ConcurrentHashMap

/**
 * Blocks TLS handshakes by SNI host name at the platform Conscrypt sockets.
 *
 * Complements [DnsGuard] for apps that resolve via HTTPDNS to hard-coded IPs and then connect to
 * the IP directly: DNS never sees the host, but the socket still carries the real host name for
 * SNI and certificate validation.
 *
 * The host name getter is called through a cached [MethodHandle] rather than `Method.invoke`, so
 * the per-handshake path does not allocate a varargs array.
 */
internal class TlsGuard(private val sinks: SinkContext) {
    private val hostnameGetters = ConcurrentHashMap<Class<*>, MethodHandle>()

    /** Returns whether at least one socket implementation was hooked. */
    fun install(): Boolean {
        val hooked = SOCKET_CLASSES.count { className ->
            val handshake = runCatching { Class.forName(className).getDeclaredMethod(HANDSHAKE_METHOD) }.getOrNull()
            if (handshake == null) {
                sinks.log.warn("TLS sink $className#$HANDSHAKE_METHOD not found")
                return@count false
            }
            // PASSTHROUGH so the deliberate SSLHandshakeException reaches the caller.
            sinks.xposed.hook(handshake)
                .setExceptionMode(ExceptionMode.PASSTHROUGH)
                .intercept(FailOpenHooker(sinks.log, Sinks.TLS, ::decide))
            true
        }
        sinks.log.info("TLS guard hooked $hooked/${SOCKET_CLASSES.size} sinks in ${sinks.env.packageName}")
        sinks.reportSink(Sinks.TLS, hooked > 0)
        return hooked > 0
    }

    private fun decide(chain: Chain): Verdict {
        val socket = chain.thisObject ?: return Verdict.Proceed
        val getter = hostnameGetter(socket.javaClass) ?: return Verdict.Proceed
        val host = getter.invoke(socket) as? String
        sinks.checkHost(host) ?: return Verdict.Proceed
        return Verdict.Throw(NetErrors.tlsHandshake(host))
    }

    private fun hostnameGetter(type: Class<*>): MethodHandle? = hostnameGetters[type]
        ?: Reflection.findNoArgMethod(type, HOSTNAME_METHOD)
            ?.let { MethodHandles.lookup().unreflect(it) }
            ?.also { hostnameGetters[type] = it }

    private companion object {
        const val HANDSHAKE_METHOD = "startHandshake"

        // Declared on the shared Conscrypt base (OpenSSLSocketImpl / AbstractConscryptSocket).
        const val HOSTNAME_METHOD = "getHostname"

        val SOCKET_CLASSES = listOf(
            "com.android.org.conscrypt.ConscryptFileDescriptorSocket",
            "com.android.org.conscrypt.ConscryptEngineSocket",
        )
    }
}
