package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.net.OkHttpEntries
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OkHttpEntriesTest {
    /** A no-arg method of a call class: its name, whether it returns `Response`, whether it declares `IOException`. */
    private data class M(val name: String, val response: Boolean = true, val throwsIo: Boolean = false)

    private fun select(vararg methods: M, contract: Boolean = false) =
        OkHttpEntries.select(methods.toList(), M::name, M::response, M::throwsIo, contract)

    @Test
    fun `named entry wins, mangled or not`() {
        val entry = M("getResponseWithInterceptorChain\$okhttp")
        assertEquals(listOf(entry), select(M("execute", throwsIo = true), entry))
        val plain = M("getResponseWithInterceptorChain", throwsIo = true)
        assertEquals(listOf(plain), select(plain, M("execute")))
    }

    @Test
    fun `renamed entry is kept when only execute still declares IOException`() {
        // R8 stripped the throws clause of the renamed entry; execute() kept it through okhttp3.Call.
        val entry = M("a")
        assertEquals(listOf(entry), select(M("execute", throwsIo = true), entry, M("b", response = false)))
    }

    @Test
    fun `stripped throws need the call contract, and IOException-declaring ones are preferred`() {
        assertEquals(emptyList(), select(M("execute"), M("a")), "no proof IOException is expected: not hooked")
        assertEquals(listOf(M("a")), select(M("execute"), M("a"), contract = true), "Call.execute declares it")
        assertEquals(listOf(M("b", throwsIo = true)), select(M("a"), M("b", throwsIo = true)))
    }

    @Test
    fun `stripped throws with several candidates are ambiguous and hook nothing`() {
        // e.g. a renamed entry next to a cached-response getter: throwing into the getter would crash.
        assertEquals(emptyList(), select(M("execute"), M("a"), M("cached"), contract = true))
        assertEquals(emptyList(), select(M("execute", throwsIo = true), M("a"), M("b")))
    }

    @Test
    fun `execute alone is used only when nothing else returns a Response`() {
        assertEquals(listOf(M("execute")), select(M("execute"), M("request", response = false), contract = true))
        assertEquals(emptyList(), select(M("request", response = false), M("timeout", response = false)))
    }
}
