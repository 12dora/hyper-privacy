package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.config.ConfigBackup
import io.github.hyperprivacy.core.config.ParsedBackup
import io.github.hyperprivacy.core.tracker.OriginHints
import io.github.hyperprivacy.core.tracker.OriginMatcher
import io.github.hyperprivacy.core.tracker.TrackerCategory
import io.github.hyperprivacy.core.tracker.TrackerOrigin
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.core.tracker.Trackers
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Policy of origin blocking: precedence, opt-in SDKs, device-local suspect switches. */
class OriginPolicyTest {
    @Test
    fun `the app's own root wins a tie with an sdk prefix, whatever the order`() {
        val matcher = OriginMatcher(listOf("com.x.lib." to "sdk", "com.x." to "other"), listOf("com.x.lib."))
        assertEquals(OriginMatcher.OWN, matcher.classify("com.x.lib.Feed"))
        assertEquals("other", matcher.sdkId(matcher.classify("com.x.Ad")))
    }

    @Test
    fun `frames are skipped and resolved by the caller's rules`() {
        val matcher = OriginMatcher(listOf("com.qq.e." to "gdt"), listOf("$PKG."))
        // A module frame renamed by R8 is skipped; an unknown frame is claimed only by the resolver.
        val frames = listOf("a.a", "b.c", "com.qq.e.Ad")
        val skipped = matcher.attribute(frames.iterator(), { it }, { it == "a.a" }, { OriginMatcher.UNKNOWN })
        assertEquals("gdt", matcher.sdkId(skipped))
        val gdt = matcher.indexOf("gdt")
        val plugin = matcher.attribute(
            frames.iterator(),
            { it },
            null,
            { if (it == "a.a") gdt else OriginMatcher.UNKNOWN },
        )
        assertEquals(gdt, plugin)
        assertEquals(OriginMatcher.UNKNOWN, matcher.attribute(listOf("a.a", "b.c").iterator(), { it }))
    }

    @Test
    fun `offerwall sdks are opt-in as a whole`() {
        listOf("tapjoy", "fyber-fairbid").forEach { id ->
            val sdk = requireNotNull(Trackers.byId(id))
            assertFalse(sdk.originBlock, id)
            assertFalse(sdk.defaultBlock, id)
            assertTrue(sdk.originBlockable && sdk.switchable, id)
            assertFalse(TrackerProtection.isBlocked(emptyMap<String, Any>(), PKG, sdk), id)
            assertTrue(TrackerProtection.isBlocked(mapOf(TrackerProtection.blockKey(PKG, id) to true), PKG, sdk), id)
        }
        val defaultOn = Trackers.all.filter { it.originBlockable && it.originBlock }
        assertTrue(defaultOn.all { TrackerCategory.ADS in it.categories })
        assertTrue(Trackers.all.none { !it.originBlock && it.defaultBlock })
    }

    @Test
    fun `suspect switches stay on the device across an import`() {
        val suspect = TrackerOrigin.suspectKey(PKG, "x.y.ads.")
        assertTrue(TrackerOrigin.isSuspectKey(suspect))
        assertFalse(TrackerOrigin.isSuspectKey(TrackerProtection.blockKey(PKG, "tencent-gdt")))
        assertFalse(TrackerOrigin.isSuspectKey(TrackerOrigin.originKey(PKG)))
        val parsed = ConfigBackup.parse(
            """{"format":"hyperprivacy-config","schema":1,"apps":{"$PKG":{"protect":true}}}""",
        )
        val backup = assertIs<ParsedBackup.Ok>(parsed).backup
        val current = mapOf(suspect to true, TrackerProtection.blockKey(PKG, "tencent-gdt") to false)
        val plan = backup.plan(current, SELF, { true }) { setOf("tencent-gdt") }
        // "Restore defaults" does clear them (they default to off).
        assertTrue(suspect in ConfigBackup.resetKeys(current))
        assertFalse(suspect in plan.removes || suspect in plan.puts)
        assertTrue(TrackerProtection.blockKey(PKG, "tencent-gdt") in plan.removes)
    }

    @Test
    fun `app roots round-trip and older hints still decode`() {
        val hints = OriginHints(own = setOf("$PKG.", "x.y."), appRoots = setOf("$PKG."))
        assertEquals(hints, OriginHints.decode(hints.encode()))
        assertEquals(emptySet(), OriginHints.decode("o:$PKG.").appRoots)
        assertTrue(OriginHints.isInsideApp("$PKG.member.", PKG, emptySet()))
        assertFalse(OriginHints.isInsideApp("com.example.application.", PKG, emptySet()))
    }

    private companion object {
        const val PKG = "com.example.app"
        const val SELF = "io.github.hyprivacy"
    }
}
