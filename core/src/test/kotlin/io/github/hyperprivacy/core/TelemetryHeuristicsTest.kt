package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.health.EndpointVerdict
import io.github.hyperprivacy.core.health.TelemetryHeuristics
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.rule.UrlPrefix
import io.github.hyperprivacy.core.status.ObservedEndpoint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TelemetryHeuristicsTest {
    /** Real endpoints seen on device that are reporting channels. */
    private val positives = listOf(
        "tracking.miui.com" to "/track/v4",
        "mqs-log.miui.com" to "/data",
        "data.mistat.xiaomi.com" to "/mistats",
        "apm-rum.inf.miui.com" to null,
        "sdkreport.e.qq.com" to null,
        "ossp.voicecloud.cn" to null,
        "mbd.baidu.com" to "/ztbox",
        "rmonitor.qq.com" to null,
        "log-sdk.clickwifi.net" to null,
        "api.example.com" to "/v2/collect",
        "cdn2.example.com" to "/beacon1",
    )

    /** Vendor services and look-alike words that must never be flagged. */
    private val negatives = listOf(
        "api.xmpush.xiaomi.com" to null,
        "resolver.msg.xiaomi.net" to null,
        "update.miui.com" to null,
        "weatherapi.market.xiaomi.com" to "/wtr-v3/weather",
        "account.xiaomi.com" to "/pass/serviceLogin",
        "galleryapi.micloud.xiaomi.net" to "/mic/gallery",
        "catalog.example.com" to null,
        "blog.example.com" to "/posts",
        "logistics.example.com" to null,
        "status.example.com" to "/",
        "stats.update.example.com" to null,
        "register.example.com" to "/log",
    )

    private val noRules = emptyList<Rule>()

    @Test
    fun `positives are suspected`() {
        val wrong = positives.filter { (host, path) ->
            TelemetryHeuristics.classify(host, path, noRules) != EndpointVerdict.SUSPECTED_REPORTING
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `negatives are unremarkable`() {
        val wrong = negatives.filter { (host, path) ->
            TelemetryHeuristics.classify(host, path, noRules) != EndpointVerdict.UNREMARKABLE
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `tokenization splits on separators and digits`() {
        assertEquals(listOf("mqs", "log", "miui", "com"), TelemetryHeuristics.tokens("MQS-log.miui.com"))
        assertEquals(listOf("a", "b", "track", "v"), TelemetryHeuristics.tokens("a_b/track/v4"))
        assertEquals(emptyList(), TelemetryHeuristics.tokens("/"))
    }

    @Test
    fun `covered endpoints are known`() {
        val rules = listOf(rule(RulePayload.Domains(listOf("tracking.miui.com"))))
        assertEquals(EndpointVerdict.KNOWN, TelemetryHeuristics.classify("a.tracking.miui.com", "/track", rules))
        val urlRules = listOf(rule(RulePayload.Urls(listOf(UrlPrefix("api.example.com", "/v2/collect")))))
        assertEquals(EndpointVerdict.KNOWN, TelemetryHeuristics.classify("api.example.com", "/v2/collect", urlRules))
        assertEquals(
            EndpointVerdict.SUSPECTED_REPORTING,
            TelemetryHeuristics.classify("api.example.com", "/v3/collect", urlRules),
        )
    }

    @Test
    fun `suspected filters blocked, covered and unremarkable endpoints`() {
        val rules = listOf(rule(RulePayload.Domains(listOf("tracking.miui.com"))))
        val open = endpoint("log-sdk.clickwifi.net", null, blocked = false)
        val input = listOf(
            open,
            endpoint("mqs-log.miui.com", "/data", blocked = true),
            endpoint("tracking.miui.com", "/track", blocked = false),
            endpoint("weatherapi.market.xiaomi.com", null, blocked = false),
        )
        assertEquals(listOf(open), TelemetryHeuristics.suspected(input, rules))
    }

    @Test
    fun `shipped catalog covers its own hosts`() {
        val host = Catalog.rules.firstNotNullOf { (it.payload as? RulePayload.Domains)?.suffixes?.first() }
        assertEquals(EndpointVerdict.KNOWN, TelemetryHeuristics.classify("log.$host", "/track/v4"))
    }

    private fun endpoint(host: String, path: String?, blocked: Boolean) =
        ObservedEndpoint("com.example.app", host, path, count = 1, firstSeen = 0, lastSeen = 0, blocked = blocked)

    private fun rule(payload: RulePayload) = Rule(
        id = "test.rule",
        packages = setOf("com.example.app"),
        title = Text("t", "t"),
        summary = Text("s", "s"),
        payload = payload,
        category = Category.ANALYTICS,
        evidence = "test",
    )
}
