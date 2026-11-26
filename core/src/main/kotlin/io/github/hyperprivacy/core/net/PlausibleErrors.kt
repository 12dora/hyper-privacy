package io.github.hyperprivacy.core.net

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The errors blocking sinks hand to the app, shaped exactly like ordinary network failures on
 * Android so that crash / network-error SDKs that upload exception messages cannot tell a blocked
 * request from a flaky network, and no message names this module or its rules.
 *
 * Each factory copies the wording of the platform code that throws the same type for the same
 * situation (bionic `gai_strerror`, libcore `IoBridge`, Conscrypt `NativeCrypto`, OkHttp). The
 * choice between equally plausible variants is stable per host: a given server "fails" the same way
 * every time, as a real misbehaving server would.
 *
 * Stack traces are the caller's business: the hook layer rewrites the frames of the returned
 * exceptions so module classes never appear in them.
 */
public object PlausibleErrors {
    /** `gai_strerror(EAI_NODATA)`: what Android reports for a name with no usable address. */
    private const val NO_ADDRESS = "No address associated with hostname"
    private const val CONNECTION_REFUSED = "ECONNREFUSED (Connection refused)"

    /** Conscrypt: the peer closed the socket during the handshake (`SSL_ERROR_ZERO_RETURN`). */
    public const val TLS_CLOSED_BY_PEER: String = "Connection closed by peer"

    /** OkHttp / okio: a read or connect deadline passed. */
    public const val TIMEOUT: String = "timeout"

    /** bionic's EAI_NODATA, the `GaiException` error code behind [unresolvedHost]. */
    public const val EAI_NODATA: Int = 7

    /** Message of the `GaiException` libcore chains as the cause of [unresolvedHost]. */
    public const val GAI_CAUSE_MESSAGE: String = "android_getaddrinfo failed: EAI_NODATA ($NO_ADDRESS)"

    /** Tag bits of an arm64 heap pointer under Android's tagged-pointer allocator (`0xb400…`). */
    private const val HEAP_TAG = -0x4bff_ff90_0000_0000L // 0xb4000070_00000000
    private const val HEAP_OFFSET_MASK = 0x0000_000f_ffff_fff8L

    /** `InetAddress.getAllByName` for a name the resolver has no address for. */
    public fun unresolvedHostMessage(host: String): String = "Unable to resolve host \"$host\": $NO_ADDRESS"

    public fun unresolvedHost(host: String): UnknownHostException = UnknownHostException(unresolvedHostMessage(host))

    /** How a TLS handshake with [host] fails; stable per host. */
    public enum class TlsFailure { CLOSED_BY_PEER, RESET_BY_PEER }

    public fun tlsFailureFor(host: String?): TlsFailure =
        if (stableBit(host)) TlsFailure.RESET_BY_PEER else TlsFailure.CLOSED_BY_PEER

    /**
     * Conscrypt's handshake failure for [host]. [sslPointer] is the native `SSL*` Conscrypt prints in
     * read errors; pass [plausibleSslPointer] of a random value so it differs per connection.
     */
    public fun tlsHandshakeMessage(host: String?, sslPointer: Long): String = when (tlsFailureFor(host)) {
        TlsFailure.CLOSED_BY_PEER -> TLS_CLOSED_BY_PEER

        TlsFailure.RESET_BY_PEER ->
            "Read error: ssl=0x${java.lang.Long.toHexString(sslPointer)}: I/O error during system call, " +
                "Connection reset by peer"
    }

    /** A tagged arm64 heap address (`0xb400007xxxxxxxx8`) derived from [random], as printed for `SSL*`. */
    public fun plausibleSslPointer(random: Long): Long = HEAP_TAG or (random and HEAP_OFFSET_MASK)

    /**
     * libcore `IoBridge.createMessageForException` for a refused connect: [address] and [local] are
     * `InetAddress.toString()` (`host/ip` or `/ip`). The ` from <local> (port <n>)` clause is present
     * whenever `getsockname` on the socket succeeds, i.e. practically always; pass `null` only when
     * the local address cannot be determined. With a timeout libcore polls and the failing call is
     * `isConnected`; without one it is `connect` itself.
     */
    public fun connectRefusedMessage(
        address: String,
        port: Int,
        timeoutMs: Int,
        local: String? = null,
        localPort: Int = 0,
    ): String = buildString {
        append("failed to connect to ").append(address).append(" (port ").append(port).append(')')
        if (local != null) append(" from ").append(local).append(" (port ").append(localPort).append(')')
        if (timeoutMs > 0) append(" after ").append(timeoutMs).append("ms")
        append(": ").append(if (timeoutMs > 0) "isConnected" else "connect").append(" failed: ")
        append(CONNECTION_REFUSED)
    }

    /** First and last port of Linux's default `ip_local_port_range`, where connect picks the source port. */
    public const val FIRST_EPHEMERAL_PORT: Int = 32_768
    public const val LAST_EPHEMERAL_PORT: Int = 60_999

    /** An ephemeral source port derived from [random], as the kernel would pick for an unbound socket. */
    public fun ephemeralPort(random: Int): Int =
        FIRST_EPHEMERAL_PORT + Math.floorMod(random, LAST_EPHEMERAL_PORT - FIRST_EPHEMERAL_PORT + 1)

    /**
     * A line number for a stack frame of [className]#[methodName] when no genuine frame is at hand:
     * stable per method (a real frame always shows the same line) and in the range real platform
     * source files use. Never `-1` / absent, which genuine Java frames never show.
     */
    public fun plausibleLine(className: String, methodName: String): Int =
        FIRST_PLAUSIBLE_LINE + Math.floorMod("$className#$methodName".hashCode(), PLAUSIBLE_LINE_SPAN)

    private const val FIRST_PLAUSIBLE_LINE = 60
    private const val PLAUSIBLE_LINE_SPAN = 840

    /**
     * An HTTP client's failure for a request to an otherwise reachable [host] (path-level block):
     * OkHttp's route failure `Failed to connect to host/ip:port` when an address of the host is
     * known (`/ip:port` when [host] is itself an IP literal, as `InetSocketAddress` prints it), else
     * okio's plain `timeout`.
     */
    public fun httpFailureMessage(host: String, ip: String?, port: Int): String = when {
        IpLiterals.isIpLiteral(host) -> "Failed to connect to /${host.removeSurrounding("[", "]")}:$port"
        ip != null -> "Failed to connect to $host/$ip:$port"
        else -> TIMEOUT
    }

    public fun httpFailure(host: String, ip: String?, port: Int): IOException {
        val message = httpFailureMessage(host, ip, port)
        return if (message == TIMEOUT) SocketTimeoutException(message) else ConnectException(message)
    }

    /** Default port of an `http` / `https` URL whose port is unspecified (`-1`). */
    public fun portOf(scheme: String?, port: Int): Int = when {
        port > 0 -> port
        scheme.equals("http", ignoreCase = true) -> HTTP_PORT
        else -> HTTPS_PORT
    }

    private const val HTTP_PORT = 80
    private const val HTTPS_PORT = 443

    /** One stable pseudo-random bit per host (String.hashCode is specified, so stable across runs). */
    private fun stableBit(host: String?): Boolean = host != null && (host.hashCode() ushr HASH_BIT) and 1 == 1

    private const val HASH_BIT = 7
}
