package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.stack.FrameOrigin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameLoadersTest {
    /** An app loader with custom Java loading code: a stack sample must never run it. */
    private class RecordingLoader(parent: ClassLoader) : ClassLoader(parent) {
        val asked = mutableListOf<String>()

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            asked += name
            return super.loadClass(name, resolve)
        }
    }

    private val xposed = FakeXposed()
    private val log = HookLog(xposed.api)

    @Test
    fun `origin lookups never load a class through the app's loader`() {
        val app = RecordingLoader(checkNotNull(FrameLoadersTest::class.java.classLoader))
        val switches = RuleSwitches.open(null, log, { emptyMap() }, { false })
        val env = GuardEnv(xposed.api, log, PACKAGE, app, switches, StatusReporter(log, { null }))
        val origin = FrameLoaders.origin(env) // NONE when the lookup is unavailable (a JVM without opened java.lang).
        val names = listOf("k2", "PwtZ.MmA.HookBridge", "android.hardware.FruntshQuierniep", "java.lang.Thread", "a")
        for (name in names) {
            val answer = origin.of(name)
            assertTrue(answer in FrameOrigin.OTHER..FrameOrigin.FOREIGN, "$name -> $answer")
            assertEquals(answer, origin.of(name)) // Cached: the same answer again.
        }
        assertTrue(app.asked.isEmpty(), "loadClass called for ${app.asked}")
    }

    private companion object {
        const val PACKAGE = "com.example.app"
    }
}
