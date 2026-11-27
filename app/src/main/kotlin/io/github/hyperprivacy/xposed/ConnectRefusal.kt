package io.github.hyperprivacy.xposed

import android.os.StrictMode
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import io.github.hyperprivacy.core.net.PlausibleErrors
import io.github.libxposed.api.XposedInterface
import java.io.FileDescriptor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * libcore's refused connect as this device would produce it, for [ConnectGuard]:
 *
 * - **Message**: the full `IoBridge.createMessageForException` text including
 *   ` from /<local> (port <n>)`. The local side is what the socket would have had after a real
 *   attempt: its bound address and port if it is bound, otherwise the source address the kernel
 *   routes to the destination (a connected UDP probe, which sends nothing) and an ephemeral port.
 * - **Frames**: genuine `IoBridge` frames with this build's line numbers, harvested once per
 *   process and timeout mode by letting the original [bridgeConnect] (no hooks, via the framework
 *   invoker) fail against a closed loopback port. Nothing leaves the device. When the harvest is
 *   impossible (no `IoBridge`, no INTERNET permission, an unexpected result) the scrubber falls
 *   back to one synthesized frame.
 * - **Bounded**: the probe socket carries a send timeout of [HARVEST_TIMEOUT_MS] (which also
 *   bounds a blocking `connect`), so a loopback port that drops instead of refusing costs at most
 *   that once, and yields no template. Exactly one thread harvests per mode, without a lock: the
 *   others (and any later call if the harvest failed) take the synthesized frame instead of waiting.
 *
 * Both probes run under a lax StrictMode thread policy, so they never raise a network-on-main-
 * thread violation of their own; every failure degrades the error's fidelity, never the block.
 */
internal class ConnectRefusal(private val xposed: XposedInterface, private val bridgeConnect: Method?) {
    private val timedFrames = Harvest(timed = true)
    private val blockingFrames = Harvest(timed = false)

    /** One mode's template: claimed by the first caller, read lock-free by everyone. */
    private inner class Harvest(private val timed: Boolean) {
        private val claimed = AtomicBoolean()

        @Volatile
        private var frames: TopFrames? = null

        fun get(): TopFrames? {
            frames?.let { return it }
            if (claimed.get() || !claimed.compareAndSet(false, true)) return null
            return harvest(timed).also { frames = it }
        }
    }

    /** The refusal of a connect of [fd] (when known) to [address]:[port] with [timeoutMs]. */
    fun verdict(fd: FileDescriptor?, address: InetAddress, port: Int, timeoutMs: Int): Verdict.Throw {
        val local = localEndpoint(fd, address)
        val error = NetErrors.connectRefused(address, port, timeoutMs, local?.first, local?.second ?: 0)
        val top = when {
            bridgeConnect == null -> null
            timeoutMs > 0 -> timedFrames.get()
            else -> blockingFrames.get()
        }
        return Verdict.Throw(error, top)
    }

    private fun localEndpoint(fd: FileDescriptor?, destination: InetAddress): Pair<String, Int>? {
        val bound = fd?.let { socketName(it) }
        val boundAddress = bound?.address
        val boundPort = bound?.port ?: 0
        val source = boundAddress?.takeUnless { it.isAnyLocalAddress } ?: sourceAddressFor(destination)
        return when {
            source != null -> source.toString() to (boundPort.takeIf { it != 0 } ?: randomEphemeralPort())

            // Unroutable: libcore would print the unbound wildcard, as getsockname reports it.
            boundAddress != null -> boundAddress.toString() to boundPort

            else -> null
        }
    }

    private fun randomEphemeralPort(): Int = PlausibleErrors.ephemeralPort(ThreadLocalRandom.current().nextInt())

    private fun socketName(fd: FileDescriptor): InetSocketAddress? = try {
        Os.getsockname(fd) as? InetSocketAddress
    } catch (_: ErrnoException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** The local address the kernel would use towards [destination]; connecting UDP sends no packet. */
    private fun sourceAddressFor(destination: InetAddress): InetAddress? = withLaxThreadPolicy {
        val family = if (destination is Inet6Address) OsConstants.AF_INET6 else OsConstants.AF_INET
        val probe = Os.socket(family, OsConstants.SOCK_DGRAM, 0)
        try {
            Os.connect(probe, destination, PROBE_PORT)
            socketName(probe)?.address?.takeUnless { it.isAnyLocalAddress }
        } finally {
            Os.close(probe)
        }
    }

    private fun harvest(timed: Boolean): TopFrames? = withLaxThreadPolicy {
        val method = bridgeConnect ?: return@withLaxThreadPolicy null
        val fd = Os.socket(OsConstants.AF_INET6, OsConstants.SOCK_STREAM, 0)
        try {
            // Bounds the blocking (timeout 0) connect too: Linux applies SO_SNDTIMEO to connect().
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, PROBE_TIMEOUT)
            val invoker = xposed.getInvoker(method).setType(XposedInterface.Invoker.Type.ORIGIN)
            invoker.invoke(
                null,
                fd,
                InetAddress.getByAddress(LOOPBACK),
                CLOSED_PORT,
                if (timed) HARVEST_TIMEOUT_MS else 0,
            )
            null // Something listens there after all: no template.
        } catch (e: InvocationTargetException) {
            templateOf(e.targetException, timed)
        } finally {
            Os.close(fd)
        }
    }

    /** The genuine frames down to `IoBridge.connect`, if [error] is the expected refusal. */
    private fun templateOf(error: Throwable?, timed: Boolean): TopFrames? {
        val cause = error?.cause as? ErrnoException ?: return null
        if (error !is ConnectException || cause.errno != OsConstants.ECONNREFUSED) return null
        val frames = headOf(error.stackTrace) ?: return null
        val thrower = frames.first()
        val throwingMethod = if (timed) IS_CONNECTED else CONNECT
        if (thrower.className != IO_BRIDGE || thrower.methodName != throwingMethod) return null
        return TopFrames(frames, headOf(cause.stackTrace) ?: return null)
    }

    private fun headOf(frames: Array<StackTraceElement>): Array<StackTraceElement>? {
        val end = frames.indexOfFirst { it.className == IO_BRIDGE && it.methodName == CONNECT }
        return if (end < 0) null else frames.copyOfRange(0, end + 1)
    }

    private inline fun <T> withLaxThreadPolicy(block: () -> T?): T? {
        val saved = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        return try {
            block()
        } catch (_: Throwable) {
            // Fidelity only: the block itself never depends on the probe.
            null
        } finally {
            StrictMode.setThreadPolicy(saved)
        }
    }

    private companion object {
        const val IO_BRIDGE = "libcore.io.IoBridge"
        const val CONNECT = "connect"
        const val IS_CONNECTED = "isConnected"

        /** tcpmux: below 1024, so no app can listen on it, and no Android daemon does. */
        const val CLOSED_PORT = 1
        const val PROBE_PORT = 9
        const val HARVEST_TIMEOUT_MS = 250
        val LOOPBACK = byteArrayOf(127, 0, 0, 1)
        val PROBE_TIMEOUT: StructTimeval by lazy { StructTimeval.fromMillis(HARVEST_TIMEOUT_MS.toLong()) }
    }
}
