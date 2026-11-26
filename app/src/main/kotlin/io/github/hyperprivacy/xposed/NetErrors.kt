package io.github.hyperprivacy.xposed

import android.annotation.SuppressLint
import android.system.ErrnoException
import android.system.OsConstants
import io.github.hyperprivacy.core.net.IpLiterals
import io.github.hyperprivacy.core.net.PlausibleErrors
import java.io.IOException
import java.lang.reflect.Constructor
import java.net.ConnectException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ThreadLocalRandom
import javax.net.ssl.SSLHandshakeException

/**
 * The exceptions blocking sinks throw, built from [PlausibleErrors] wording plus the cause chain the
 * platform attaches to the same failure (`GaiException` under a DNS failure, `ErrnoException` under
 * a refused connect), so even `getCause()` looks ordinary. Callers return them as `Verdict.Throw`,
 * whose stack trace [StackScrub] rewrites.
 */
internal object NetErrors {
    private const val GETADDRINFO = "android_getaddrinfo"

    /** `android.system.GaiException(String, int)`; hidden, so optional. */
    private val gaiConstructor: Constructor<*>? by lazy { findGaiConstructor() }

    /** What libcore throws when the resolver has no address for [host]. */
    fun unresolvedHost(host: String): UnknownHostException = PlausibleErrors.unresolvedHost(host).apply {
        gaiCause()?.let(::initCause)
    }

    /** Conscrypt's handshake failure for [host] (stable variant per host, fresh `SSL*` per call). */
    fun tlsHandshake(host: String?): SSLHandshakeException {
        val pointer = PlausibleErrors.plausibleSslPointer(ThreadLocalRandom.current().nextLong())
        return SSLHandshakeException(PlausibleErrors.tlsHandshakeMessage(host, pointer))
    }

    /** libcore `IoBridge.connect` refusing a connection to [address]:[port]. */
    fun connectRefused(address: InetAddress, port: Int, timeoutMs: Int): ConnectException {
        val message = PlausibleErrors.connectRefusedMessage(address.toString(), port, timeoutMs)
        val call = if (timeoutMs > 0) "isConnected" else "connect"
        return ConnectException(message).apply { initCause(ErrnoException(call, OsConstants.ECONNREFUSED)) }
    }

    /** NIO's native refusal (`sun.nio.ch.Net.connect`), which carries no address. */
    fun nioConnectRefused(): ConnectException = ConnectException("Connection refused")

    /**
     * An HTTP client's failure for a request to [host] blocked at the URL layer: when [hostBlocked]
     * (a DOMAIN rule, so in reality DNS would already have failed) the resolver error, otherwise the
     * route failure / timeout of [PlausibleErrors.httpFailure] with the host's last known [ip].
     */
    fun httpFailure(host: String, hostBlocked: Boolean, ip: String?, port: Int): IOException =
        if (hostBlocked && !IpLiterals.isIpLiteral(host)) {
            unresolvedHost(host)
        } else {
            PlausibleErrors.httpFailure(host, ip, port)
        }

    private fun gaiCause(): Throwable? = try {
        gaiConstructor?.newInstance(GETADDRINFO, PlausibleErrors.EAI_NODATA) as? Throwable
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    private fun findGaiConstructor(): Constructor<*>? = try {
        Class.forName("android.system.GaiException")
            .getConstructor(String::class.java, Int::class.javaPrimitiveType)
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: LinkageError) {
        null
    }
}
