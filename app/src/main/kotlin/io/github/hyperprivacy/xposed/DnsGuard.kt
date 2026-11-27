package io.github.hyperprivacy.xposed

import android.annotation.SuppressLint
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method

/**
 * Blocks name resolution at libcore's single Java DNS sink. Every `InetAddress` lookup (and so
 * OkHttp, HttpURLConnection, …) funnels into it after IP literals have already been handled, so
 * no literal parsing is needed here and nothing we call re-enters the hook.
 *
 * Successful answers are also recorded host by address ([SinkContext.recordAnswers]) for the
 * connect sink.
 *
 * Hot path: with diagnostics off, a call costs one host normalisation and O(labels) table probes.
 */
internal class DnsGuard(private val sinks: SinkContext) {
    /** Returns whether the sink was hooked. */
    fun install(): Boolean {
        val sink = findSink()
        if (sink != null) {
            // PASSTHROUGH: the deliberate UnknownHostException must reach the caller; FailOpenHooker
            // already guarantees no other exception from our logic escapes.
            val blocker = FailOpenHooker(sinks.log, Sinks.DNS, ::decide)
            sinks.xposed.hook(sink)
                .setExceptionMode(ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = blocker.intercept(chain)
                    try {
                        learn(chain, result)
                    } catch (_: Throwable) {
                        // Learning is best effort; the answer is returned as it is.
                    }
                    result
                }
        } else {
            sinks.log.warn("DNS sink $SINK_CLASS#$SINK_METHOD not found")
        }
        sinks.reportSink(Sinks.DNS, sink != null)
        return sink != null
    }

    private fun decide(chain: Chain): Verdict {
        val host = chain.getArg(0) as? String
        if (host == null) return Verdict.Proceed
        sinks.checkHost(host) ?: return Verdict.Proceed
        return Verdict.Throw(NetErrors.unresolvedHost(host))
    }

    /** Feeds a successful answer to the address book (IP sink, error messages); the caller catches. */
    private fun learn(chain: Chain, result: Any?) {
        val host = chain.getArg(0) as? String ?: return
        (result as? Array<*>)?.let { sinks.recordAnswers(host, it) }
    }

    // Hidden API: reachable from module code only where the framework exempts it. The lookup is
    // verified at runtime and a miss is logged and reported, so the lint check adds nothing here.
    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi")
    private fun findSink(): Method? = runCatching {
        Class.forName(SINK_CLASS).getDeclaredMethod(SINK_METHOD, String::class.java, Int::class.javaPrimitiveType)
    }.getOrNull()

    private companion object {
        const val SINK_CLASS = "java.net.Inet6AddressImpl"
        const val SINK_METHOD = "lookupAllHostAddr"
    }
}
