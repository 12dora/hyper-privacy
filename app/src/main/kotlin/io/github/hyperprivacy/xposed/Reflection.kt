package io.github.hyperprivacy.xposed

import android.annotation.SuppressLint
import android.content.Context

/** Framework internals reached by reflection; each returns `null` instead of throwing. */
internal object Reflection {
    /** The current process's Application, or `null` before it is created. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun currentApplication(): Context? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()

    /** system_server's system Context. Only meaningful inside system_server. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun systemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(current) as? Context
    }.getOrNull()

    /** Loads [name] through [loader] without initialising it, or `null` if absent. */
    fun loadClass(name: String, loader: ClassLoader?): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }

    /** Finds a no-arg method by walking up from [type], made accessible. */
    fun findNoArgMethod(type: Class<*>, name: String): java.lang.reflect.Method? = generateSequence(
        type,
    ) { it.superclass }
        .firstNotNullOfOrNull { cls -> runCatching { cls.getDeclaredMethod(name) }.getOrNull() }
        ?.apply { isAccessible = true }
}
