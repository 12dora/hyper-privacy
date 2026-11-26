package io.github.hyperprivacy.xposed

import android.annotation.SuppressLint
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.io.FileDescriptor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/**
 * IP-level sink: refuses Java connections to addresses that DOMAIN rules cover even though no host
 * name is in sight, i.e. IP literals the rules list (HTTPDNS bootstrap IPs, hard-coded endpoints)
 * and addresses this process learned for a blocked host ([SinkContext.checkConnect]).
 *
 * Entry points, all optional, each reporting under [Sinks.CONNECT]:
 * - `libcore.io.IoBridge#connect(FileDescriptor, InetAddress, int, int)`, where every
 *   `java.net.Socket` / `DatagramSocket` connect ends (the 3-argument overload delegates to it);
 *   falls back to `java.net.Socket#connect(SocketAddress, int)` when the hidden class is unreachable;
 * - the static `sun.nio.ch.Net#connect` overloads used by NIO `SocketChannel` (they call native code
 *   directly, bypassing `IoBridge`).
 *
 * The refusal is libcore's own `ECONNREFUSED` [java.net.ConnectException] ([NetErrors.connectRefused]).
 * Native sockets (Cronet, WebView, NDK code) never pass through here.
 *
 * Hot path: every socket connect. With no rule able to match and diagnostics off, the cost is one
 * volatile read; otherwise one hash probe per connect.
 */
internal class ConnectGuard(private val sinks: SinkContext) {
    /** Returns whether at least one entry point was hooked. */
    fun install(): Boolean {
        val bridge = ioBridgeConnect()
        val primary = bridge?.let { hook(it, ADDRESS_ARG, PORT_ARG, TIMEOUT_ARG) }
            ?: socketConnect()?.let { hook(it, SOCKET_ADDRESS_ARG, NO_ARG, SOCKET_TIMEOUT_ARG) }
        val nio = nioConnect()?.let { method ->
            val address = method.parameterTypes.indexOf(InetAddress::class.java)
            hook(method, address, address + 1, NIO)
        }
        val hooked = primary != null || nio != null
        sinks.log.info(
            "connect guard: ${if (bridge != null) "IoBridge" else "Socket"}=${primary != null}, nio=${nio != null} " +
                "in ${sinks.env.packageName}",
        )
        sinks.reportSink(Sinks.CONNECT, hooked)
        return hooked
    }

    /** Hooks [executable]; [addressArg] holds an `InetAddress` or (`Socket`) a `SocketAddress`. */
    private fun hook(executable: Executable, addressArg: Int, portArg: Int, timeoutArg: Int): Executable? {
        sinks.xposed.hook(executable)
            .setExceptionMode(ExceptionMode.PASSTHROUGH)
            .intercept(FailOpenHooker(sinks.log, Sinks.CONNECT) { decide(it, addressArg, portArg, timeoutArg) })
        return executable
    }

    private fun decide(chain: Chain, addressArg: Int, portArg: Int, timeoutArg: Int): Verdict {
        if (!sinks.connectChecksActive) return Verdict.Proceed
        val target = chain.getArg(addressArg)
        val address: InetAddress
        val port: Int
        if (target is InetSocketAddress) {
            address = target.address ?: return Verdict.Proceed
            port = target.port
        } else {
            address = target as? InetAddress ?: return Verdict.Proceed
            port = chain.getArg(portArg) as? Int ?: return Verdict.Proceed
        }
        sinks.checkConnect(address, port) ?: return Verdict.Proceed
        if (timeoutArg == NIO) return Verdict.Throw(NetErrors.nioConnectRefused())
        val timeout = if (timeoutArg == NO_ARG) 0 else chain.getArg(timeoutArg) as? Int ?: 0
        return Verdict.Throw(NetErrors.connectRefused(address, port, timeout))
    }

    // Hidden core-platform API: reachable from module code where the framework exempts it. A miss
    // falls back to the public Socket#connect and is logged.
    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    private fun ioBridgeConnect(): Method? = try {
        Class.forName(IO_BRIDGE).getDeclaredMethod(
            CONNECT,
            FileDescriptor::class.java,
            InetAddress::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
    } catch (e: ReflectiveOperationException) {
        sinks.log.warn("$IO_BRIDGE#$CONNECT not found; falling back to Socket#connect", e)
        null
    } catch (e: LinkageError) {
        sinks.log.warn("$IO_BRIDGE unavailable; falling back to Socket#connect", e)
        null
    }

    private fun socketConnect(): Method? = try {
        Socket::class.java.getDeclaredMethod(CONNECT, SocketAddress::class.java, Int::class.javaPrimitiveType)
    } catch (_: NoSuchMethodException) {
        null
    }

    /**
     * The static `Net.connect(…, InetAddress, int)` overload that calls into native code: the one
     * with the most parameters (the shorter ones delegate to it), or `null` if the shape differs.
     */
    @SuppressLint("PrivateApi")
    private fun nioConnect(): Method? = try {
        Class.forName(NIO_NET).declaredMethods.filter { method ->
            val types = method.parameterTypes
            val address = types.indexOf(InetAddress::class.java)
            method.name == CONNECT && Modifier.isStatic(method.modifiers) && !Modifier.isNative(method.modifiers) &&
                address >= 0 && types.getOrNull(address + 1) == Int::class.javaPrimitiveType
        }.maxByOrNull { it.parameterCount }
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: LinkageError) {
        null
    }

    private companion object {
        const val IO_BRIDGE = "libcore.io.IoBridge"
        const val NIO_NET = "sun.nio.ch.Net"
        const val CONNECT = "connect"
        const val NO_ARG = -1

        /** Timeout marker for NIO, whose refusal is the bare native message. */
        const val NIO = -2

        // IoBridge.connect(FileDescriptor fd, InetAddress address, int port, int timeoutMs)
        const val ADDRESS_ARG = 1
        const val PORT_ARG = 2
        const val TIMEOUT_ARG = 3

        // Socket.connect(SocketAddress endpoint, int timeout)
        const val SOCKET_ADDRESS_ARG = 0
        const val SOCKET_TIMEOUT_ARG = 1
    }
}
