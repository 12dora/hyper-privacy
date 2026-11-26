package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigCodecTest {
    private val on = rule("on", defaultOn = true)
    private val off = rule("off", defaultOn = false)
    private val rules = listOf(on, off)

    @Test
    fun `missing keys fall back to rule defaults`() {
        assertEquals(mapOf("on" to true, "off" to false), ConfigCodec.decode(emptyMap<String, Any>(), rules))
    }

    @Test
    fun `stored values override defaults`() {
        val raw = mapOf("rule.on" to false, "rule.off" to true)
        assertEquals(mapOf("on" to false, "off" to true), ConfigCodec.decode(raw, rules))
    }

    @Test
    fun `unknown keys and wrong types are ignored`() {
        val raw = mapOf("rule.gone" to true, "other" to false, "rule.on" to "false", "rule.off" to 1)
        assertEquals(mapOf("on" to true, "off" to false), ConfigCodec.decode(raw, rules))
    }

    @Test
    fun `encode then decode round-trips`() {
        val states = mapOf("on" to false, "off" to true)
        assertEquals(states, ConfigCodec.decode(ConfigCodec.encode(states), rules))
    }

    @Test
    fun `key and ruleIdOf are inverse`() {
        assertEquals("rule.x.y", ConfigCodec.key("x.y"))
        assertEquals("x.y", ConfigCodec.ruleIdOf("rule.x.y"))
        assertNull(ConfigCodec.ruleIdOf("x.y"))
        assertNull(ConfigCodec.ruleIdOf(null))
    }

    @Test
    fun `enabledRules keeps catalog order`() {
        assertEquals(listOf(on, off), ConfigCodec.enabledRules(mapOf("rule.off" to true), rules))
    }

    private fun rule(id: String, defaultOn: Boolean) = Rule(
        id = id,
        packages = setOf("com.example.app"),
        title = Text(id, id),
        summary = Text(id, id),
        payload = RulePayload.Domains(listOf("example.com")),
        category = Category.ANALYTICS,
        evidence = "test",
        defaultOn = defaultOn,
    )
}
