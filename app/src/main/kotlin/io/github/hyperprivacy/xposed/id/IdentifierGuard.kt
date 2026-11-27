package io.github.hyperprivacy.xposed.id

import android.content.ContentProvider
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Binder
import io.github.hyperprivacy.core.rule.IdentifierSource
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.xposed.GuardEnv
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.Sinks
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * IDENTIFIER rules for [IdentifierSource.MIUI_ID_PROVIDER]: `content://com.miui.idprovider/…`
 * (OAID / VAID / AAID / UDID) is served by `com.miui.idprovider.IdProvider` inside the security
 * center. Its `query` overloads are hooked in whichever process of the package loads the class.
 *
 * For third-party callers only ([CallerClassifier]) the real query still runs, its value length is
 * read, and a one-row cursor is returned whose `uniform_id` is all `'0'` (dashes kept, so UUID-
 * shaped ids stay well-formed), mirroring an opted-out advertising id. System apps, the provider's
 * own process and every failure path get the untouched real result.
 */
internal class IdentifierGuard(private val env: GuardEnv) {
    private val callers = CallerClassifier()
    private val failures = AtomicInteger()

    /** Guards re-entry when one hooked overload delegates to another on the same thread. */
    private val inside = ThreadLocal<Boolean>()

    /** Returns the number of methods hooked for [rule]. */
    fun install(rule: Rule, payload: RulePayload.Identifiers): Int {
        val hooked = when (payload.source) {
            IdentifierSource.MIUI_ID_PROVIDER -> installIdProvider(rule.id)

            // Enforced in system_server by xposed.system.SettingsKeyGuard, never in an app process.
            IdentifierSource.SETTINGS_KEYS -> return 0

            // Enforced per app by MediaDrmGuard; AppGuards never routes it here.
            IdentifierSource.MEDIA_DRM_ID -> return 0

            // Enforced per app by xposed.fp.HardwareFingerprintGuard; AppGuards never routes it here.
            IdentifierSource.HARDWARE_TRAITS -> return 0

            // Enforced per app by xposed.fp.SensorDataGuard; AppGuards never routes it here.
            IdentifierSource.SENSOR_DATA -> return 0
        }
        env.reporter.sink(
            env.packageName,
            Sinks.MIUI_ID_PROVIDER,
            if (hooked > 0) HookState.ACTIVE else HookState.MISSED,
        )
        return hooked
    }

    private fun installIdProvider(ruleId: String): Int {
        val type = Reflection.loadClass(ID_PROVIDER_CLASS, env.classLoader) ?: run {
            env.log.warn("$ruleId: $ID_PROVIDER_CLASS not found in ${env.packageName}")
            return 0
        }
        // Normally declared on IdProvider itself; fall back to an intermediate base class, never
        // to ContentProvider (that would hook every provider of the process).
        val queries = generateSequence(type) { it.superclass }
            .takeWhile { it != ContentProvider::class.java && it != Any::class.java }
            .map { cls -> cls.declaredMethods.filter { it.isQuery() } }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        queries.forEach { env.xposed.hook(it).intercept(MaskingHooker(ruleId)) }
        env.log.info("$ruleId: hooked ${queries.size} query method(s) of $ID_PROVIDER_CLASS in ${env.packageName}")
        return queries.size
    }

    private inner class MaskingHooker(private val ruleId: String) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val uid = Binder.getCallingUid()
            if (!shouldMask(chain, uid)) return chain.proceed()
            inside.set(true)
            val real = try {
                chain.proceed()
            } finally {
                inside.set(false)
            }
            val masked = guarded { mask(real as? Cursor) } ?: run {
                guarded { (real as? Cursor)?.moveToPosition(BEFORE_FIRST) }
                return real
            }
            env.reporter.hit(env.packageName, ruleId, "uid $uid")
            return masked
        }

        private fun shouldMask(chain: Chain, uid: Int): Boolean = guarded {
            inside.get() != true && env.switches.isEnabled(ruleId) &&
                callers.isThirdPartyOnly(uid, (chain.thisObject as? ContentProvider)?.context)
        } == true
    }

    /**
     * Zeroed copy of [real] (which is then closed), or `null` when there is nothing to hide; the
     * caller rewinds and returns [real] in that case. Other columns are copied unchanged so a
     * client reading them keeps working.
     */
    private fun mask(real: Cursor?): Cursor? {
        if (real == null) return null
        val column = real.getColumnIndex(COLUMN)
        val value = if (column >= 0 && real.moveToFirst()) real.getString(column) else null
        if (value.isNullOrEmpty()) return null
        val names = real.columnNames
        val row = Array(names.size) { i -> if (i == column) zeros(value) else real.valueAt(i) }
        val masked = MatrixCursor(names, 1).apply { addRow(row) }
        guarded { real.close() }
        return masked
    }

    /** Runs [block]; any failure is logged (bounded) and yields `null`, i.e. the real result. */
    private inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        if (failures.incrementAndGet() <= MAX_LOGGED_FAILURES) env.log.error("id provider hook failed", t)
        null
    }

    private fun Cursor.valueAt(index: Int): Any? = when (getType(index)) {
        Cursor.FIELD_TYPE_INTEGER -> getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> getBlob(index)
        Cursor.FIELD_TYPE_STRING -> getString(index)
        else -> null
    }

    private fun Method.isQuery(): Boolean = name == QUERY && Cursor::class.java.isAssignableFrom(returnType) &&
        !Modifier.isAbstract(modifiers) && !isBridge

    private companion object {
        const val ID_PROVIDER_CLASS = "com.miui.idprovider.IdProvider"
        const val QUERY = "query"

        /** Column read by the framework client `com.android.id.impl.IdProviderImpl`. */
        const val COLUMN = "uniform_id"
        const val BEFORE_FIRST = -1
        const val MAX_LOGGED_FAILURES = 5

        fun zeros(value: String): String = buildString(value.length) {
            value.forEach { append(if (it == '-') '-' else '0') }
        }
    }
}
