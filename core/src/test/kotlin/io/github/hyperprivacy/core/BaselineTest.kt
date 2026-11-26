package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.rule.Baseline
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.VersionPin
import io.github.hyperprivacy.core.status.MetaCodec
import io.github.hyperprivacy.core.status.ProcessMeta
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BaselineTest {
    @Test
    fun `parse keeps well-formed rows`() {
        val table = """
            com.a 12 1.2
            com.b 7 -
            com.c x 1.0
            com.d -1 1.0
            com.e 1
        """
        assertEquals(
            mapOf("com.a" to VersionPin(12, "1.2"), "com.b" to VersionPin(7, null)),
            Baseline.parse(table),
        )
    }

    @Test
    fun `generated table is populated and only lists vendor packages`() {
        assertTrue(Baseline.packages.size > MIN_SURVEYED)
        val vendor = Regex(
            "^(com\\.(miui|xiaomi|mi|android|mobiletools|baidu\\.input|sohu|iflytek|milink|lbe|duokan|mfashion|fido)" +
                "|miui|com\\.unionpay\\.tsm)",
        )
        assertEquals(emptyList(), Baseline.packages.filterNot { vendor.containsMatchIn(it) })
        assertNull(Baseline.of("com.example.not.surveyed"))
    }

    @Test
    fun `catalog targets have a baseline`() {
        val missing = Catalog.allPackages.filter { Baseline.of(it) == null }
        assertTrue(missing.size * 2 <= Catalog.allPackages.size, "no baseline for $missing")
    }

    @Test
    fun `meta codec round-trips and drops bad rows`() {
        val rows = listOf(ProcessMeta("com.a", 40_007_540, 1_700_000_000_000), ProcessMeta("com.b", 0, 0))
        assertEquals(rows, MetaCodec.decode(MetaCodec.encode(rows)))
        val bad = listOf("com.a\t-1\t0", "com.a\t1", "\t1\t1", "com.a\tx\t1", "com.a\t1\t-5").joinToString("\n")
        assertEquals(emptyList(), MetaCodec.decode(bad))
    }

    private companion object {
        const val MIN_SURVEYED = 100
    }
}
