package io.github.hyperprivacy.xposed

import android.annotation.SuppressLint
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method
import java.net.UnknownHostException

/**
 * Blocks name resolution at libcore's single Java DNS sink. Every `InetAddress` lookup (and so
 * OkHttp, HttpURLConnection, …) funnels into it after IP literals have already been handled, so
 * no literal parsing is needed here and nothing we call re-enters the hook.
 */
internal class DnsGuard(
    private val xposed: XposedInterface,
    private val log: HookLog,
    private val packageName: String,
    private val policy: DomainPolicy,
    private val reporter: StatusReporter,
) {
    /** Returns whether the sink was hooked. */
    fun install(): Boolean {
        val sink = findSink() ?: run {
            log.warn("DNS sink $SINK_CLASS#$SINK_METHOD not found")
            return false
        }
        // PASSTHROUGH: the deliberate UnknownHostException must reach the caller; FailOpenHooker
        // already guarantees no other exception from our logic escapes.
        xposed.hook(sink)
            .setExceptionMode(ExceptionMode.PASSTHROUGH)
            .intercept(FailOpenHooker(log, "dns", ::decide))
        log.info("DNS guard installed in $packageName")
        return true
    }

    private fun decide(chain: Chain): Verdict {
        val host = chain.getArg(0) as? String
        val ruleId = policy.blockingRule(host) ?: return Verdict.Proceed
        reporter.hit(packageName, ruleId)
        return Verdict.Throw(UnknownHostException(host))
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
