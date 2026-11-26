package io.github.hyperprivacy.xposed.drm

import android.media.MediaDrm
import io.github.hyperprivacy.core.id.DerivedIds
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.xposed.GuardEnv
import io.github.hyperprivacy.xposed.Sinks
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * IDENTIFIER rules for `MEDIA_DRM_ID`: `MediaDrm#getPropertyByteArray("deviceUniqueId")` (and the
 * `getPropertyString` form some plugins answer) is one value for every app on the device, so SDKs
 * use it to link apps. This process gets instead `HMAC-SHA256(secret, real ‖ packageName)` cut to the
 * real length ([DerivedIds]): stable for this app, different in every other one.
 *
 * Only the Java return value changes; the DRM HAL keeps using the real id for provisioning and
 * licences, so playback is unaffected. The real call always runs first and any failure, a missing
 * module secret included, returns the real value (fail-open). The NDK `AMediaDrm_*` path is native
 * and out of reach.
 *
 * Reusable for any protected third-party app ([Catalog.protectedAppRules]); it refuses to run in a
 * vendor-catalog package even if a rule list routes it there, because vendor backends (account,
 * wallet, TSM, FIDO, Find Device) bind the device by this id across all vendor apps.
 */
internal class MediaDrmGuard(private val env: GuardEnv) {
    private class Cached(val real: ByteArray, val derived: ByteArray, val secret: ByteArray)

    private val lastBytes = AtomicReference<Cached?>()
    private val failures = AtomicInteger()
    private val warnedNoSecret = AtomicBoolean()

    /** Returns the number of methods hooked for [rule]. */
    fun install(rule: Rule): Int {
        if (env.packageName in Catalog.allPackages) {
            env.log.warn("${rule.id}: ${env.packageName} is a vendor package; DRM id left untouched")
            return 0
        }
        val methods = METHODS.mapNotNull(::find)
        methods.forEach { env.xposed.hook(it).intercept(DerivingHooker(rule.id)) }
        val hooked = methods.size
        env.reporter.sink(env.packageName, Sinks.MEDIA_DRM, if (hooked > 0) HookState.ACTIVE else HookState.MISSED)
        if (hooked > 0 && env.switches.secret == null) {
            // Not yet written by the module app: stays MISSED until it appears (calls pass through until then).
            env.log.warn("${rule.id}: module secret missing; DRM id left untouched in ${env.packageName}")
            env.switches.addListener {
                if (env.switches.secret != null) env.reporter.state(env.packageName, rule.id, HookState.ACTIVE)
            }
            return 0
        }
        return hooked
    }

    private inner class DerivingHooker(private val ruleId: String) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val applies = try {
                chain.getArg(0) == MediaDrm.PROPERTY_DEVICE_UNIQUE_ID && env.switches.isEnabled(ruleId)
            } catch (_: RuntimeException) {
                false
            }
            if (!applies) return chain.proceed()
            val real = chain.proceed()
            return try {
                derive(real)?.also { env.reporter.hit(env.packageName, ruleId, MediaDrm.PROPERTY_DEVICE_UNIQUE_ID) }
                    ?: real
            } catch (t: Throwable) {
                if (failures.incrementAndGet() <= MAX_LOGGED_FAILURES) env.log.error("$ruleId derivation failed", t)
                real
            }
        }
    }

    /** The per-app replacement of [real], or `null` to return it unchanged. */
    private fun derive(real: Any?): Any? {
        val secret = env.switches.secret ?: run {
            if (warnedNoSecret.compareAndSet(false, true)) env.log.warn("module secret missing; DRM id untouched")
            return null
        }
        return when (real) {
            is ByteArray -> deriveBytes(real, secret)
            is String -> DerivedIds.deriveText(real, env.packageName, secret)
            else -> null
        }
    }

    /** Callers usually read the id once or a few times; the last result is reused. */
    private fun deriveBytes(real: ByteArray, secret: ByteArray): ByteArray {
        lastBytes.get()?.let { cached ->
            if (cached.secret === secret && cached.real.contentEquals(real)) return cached.derived.copyOf()
        }
        val derived = DerivedIds.derive(real, env.packageName, secret)
        lastBytes.set(Cached(real.copyOf(), derived, secret))
        return derived.copyOf()
    }

    private fun find(name: String): Method? = try {
        MediaDrm::class.java.getDeclaredMethod(name, String::class.java)
    } catch (_: NoSuchMethodException) {
        env.log.warn("MediaDrm#$name(String) not found in ${env.packageName}")
        null
    }

    private companion object {
        val METHODS = listOf("getPropertyByteArray", "getPropertyString")
        const val MAX_LOGGED_FAILURES = 5
    }
}
