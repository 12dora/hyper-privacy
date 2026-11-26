package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.health.EndpointVerdict
import io.github.hyperprivacy.core.health.TelemetryHeuristics
import io.github.hyperprivacy.core.status.CodeLoads
import io.github.hyperprivacy.core.status.EndpointRecorder
import io.github.hyperprivacy.core.status.ObservedEndpoint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeLoadsTest {
    private val pkg = "com.example.app"

    @Test
    fun `private paths keep only the directory category and file name`() {
        assertEquals("/private-files/plugin.apk", CodeLoads.describe("/data/user/0/$pkg/files/a/b/plugin.apk", pkg))
        assertEquals("/private-files/plugin.apk", CodeLoads.describe("/data/user/10/$pkg/files/plugin.apk", pkg))
        assertEquals("/private-cache/x.dex", CodeLoads.describe("/data/data/$pkg/cache/x.dex", pkg))
        assertEquals("/private-code-cache/x.jar", CodeLoads.describe("/data/user_de/0/$pkg/code_cache/x.jar", pkg))
        assertEquals(
            "/private-app_tinker/patch.apk",
            CodeLoads.describe("/data/user/0/$pkg/app_tinker/p/patch.apk", pkg),
        )
        assertEquals("/private-root/x.so", CodeLoads.describe("/data/user/0/$pkg/x.so", pkg))
        assertEquals("/private-other/x.so", CodeLoads.describe("/data/user/0/$pkg/weird/x.so", pkg))
        assertEquals("/foreign-private/x.so", CodeLoads.describe("/data/user/0/com.other/files/x.so", pkg))
    }

    @Test
    fun `external and other locations are categorised`() {
        assertEquals(
            "/external-app/x.dex",
            CodeLoads.describe("/storage/emulated/0/Android/data/$pkg/files/x.dex", pkg),
        )
        assertEquals("/external-shared/x.dex", CodeLoads.describe("/storage/emulated/0/Download/x.dex", pkg))
        assertEquals("/external-shared/x.dex", CodeLoads.describe("/sdcard/x.dex", pkg))
        assertEquals("/local-tmp/x.dex", CodeLoads.describe("/data/local/tmp/x.dex", pkg))
        assertEquals("/other/x.dex", CodeLoads.describe("/mnt/x.dex", pkg))
    }

    @Test
    fun `installed and system code is not recorded`() {
        listOf(
            "/system/lib64/libc.so",
            "/apex/com.android.art/lib64/libart.so",
            "/data/app/~~abc==/$pkg-xyz==/base.apk",
            "/data/app/~~abc==/$pkg-xyz==/lib/arm64/libfoo.so",
            "/product/app/Foo/Foo.apk",
            "/data/dalvik-cache/arm64/x.dex",
        ).forEach { assertNull(CodeLoads.describe(it, pkg), it) }
        assertNull(CodeLoads.describe("relative.so", pkg))
        assertNull(CodeLoads.describe("/data/user/0/$pkg/files/", pkg))
        assertNull(CodeLoads.describe(null, pkg))
    }

    @Test
    fun `long hex and digit runs in names are redacted`() {
        assertEquals(
            "/private-files/patch_*.apk",
            CodeLoads.describe("/data/user/0/$pkg/files/patch_3fa2b19c0d.apk", pkg),
        )
        assertEquals("/private-files/v1234567.so", CodeLoads.describe("/data/user/0/$pkg/files/v1234567.so", pkg))
        assertEquals("/private-files/u*_cfg.jar", CodeLoads.describe("/data/user/0/$pkg/files/u20261127_cfg.jar", pkg))
    }

    @Test
    fun `records fit the observed channel and are never suspected endpoints`() {
        val path = assertNotNull(CodeLoads.describe("/data/user/0/$pkg/files/libtracker_log.so", pkg))
        assertEquals(path, ObservedEndpoint.pathPrefixOf(path))
        assertEquals(CodeLoads.MEMORY_PATH, ObservedEndpoint.pathPrefixOf(CodeLoads.MEMORY_PATH))
        listOf(CodeLoads.DEX_HOST, CodeLoads.NATIVE_HOST).forEach { host ->
            assertTrue(ObservedEndpoint.isValidHost(host))
            assertTrue(CodeLoads.isCodeLoad(host))
            assertEquals(EndpointVerdict.UNREMARKABLE, TelemetryHeuristics.classify(host, path))
        }
        assertNotNull(EndpointRecorder().record(pkg, CodeLoads.NATIVE_HOST, path, false, 1L))
    }
}
