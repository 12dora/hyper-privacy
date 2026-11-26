package io.github.hyperprivacy.xposed.origin

import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.tracker.OriginMatcher
import io.github.hyperprivacy.core.tracker.TrackerOrigin
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.core.tracker.Trackers
import io.github.hyperprivacy.xposed.HookLog
import io.github.hyperprivacy.xposed.StatusReporter
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Origin attribution of advertising SDK code in one protected app process: decides, where a
 * request, socket, web view, class loader or page is *created*, whether an enabled ad SDK's code
 * asked for it ([OriginMatcher] over the creating stack), tags what it decided to block, and
 * answers the execution sinks from the tags. Async dispatch (OkHttp `enqueue`, executors,
 * Handlers) keeps the origin because the decision was taken on the creating thread.
 *
 * The [Table] of enabled prefixes is rebuilt from the preferences whenever they change ([rebuild],
 * off the hooked threads) and published through one volatile field: `null` means no ad SDK is
 * enabled here, and every hook returns after that single read.
 *
 * Costs per hooked call while a table is set:
 * - creation points: one `Throwable().stackTrace` (about 10-30 µs on a phone) plus one
 *   [OriginMatcher.attribute] walk (a few µs); these run per new call, socket, web view or loader,
 *   never per connect or per byte;
 * - execution sinks ([ruleOf]): one volatile read, then, only once something was tagged, one
 *   uncontended lock and one hash probe of a weak map; no allocation;
 * - class loaders an SDK created are consulted only for frames no prefix claims, through
 *   `ClassLoader.findLoadedClass` (no loading, no exception), and cached per class name.
 *
 * Every entry point is fail-open: callers run it under `FailOpenHooker` or catch everything.
 */
internal class AdOrigin(
    private val log: HookLog,
    private val packageName: String,
    private val reporter: StatusReporter,
) {
    /** What is enabled now: the matcher and the rule ids it can return. */
    class Table(val matcher: OriginMatcher, val rules: Set<String>)

    @Volatile
    private var table: Table? = null

    /** Objects decided at creation: value is the blocking rule id, or [OWN] for the app's own. */
    private val tags = WeakHashMap<Any, String>()

    @Volatile
    private var anyTagged = false

    private class Loader(val ref: WeakReference<ClassLoader>, val sdkId: String)

    private val loaders = AtomicReference<Array<Loader>>(emptyArray())
    private val loaderClasses = ConcurrentHashMap<String, String>()
    private val reported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** `ClassLoader.findLoadedClass`, resolved once. */
    private val findLoaded: Method? by lazy {
        runCatching {
            ClassLoader::class.java.getDeclaredMethod("findLoadedClass", String::class.java).apply {
                isAccessible = true
            }
        }.getOrNull()
    }

    private val resolver: (String) -> Int = ::resolveByLoader

    /** Whether any ad SDK is enabled; one volatile read. */
    val active: Boolean get() = table != null

    /** Recomputes the table from the raw preferences (the listener thread, never a hooked one). */
    @Synchronized
    fun rebuild(raw: Map<String, *>) {
        table = log.guard("build origin table") { build(raw) }
    }

    /**
     * The rule id of the enabled ad SDK whose code is creating something right now, [OWN] when the
     * app's own code is, or `null`. Walks the current stack once.
     */
    fun attribute(): String? {
        val current = table ?: return null
        val frames = Throwable(STACK_PROBE).stackTrace
        val index = current.matcher.attribute(frames, if (loaders.get().isEmpty()) null else resolver)
        return when {
            index == OriginMatcher.OWN -> OWN
            index < 0 -> null
            else -> ruleIdOf(current.matcher.sdkId(index))
        }
    }

    /** Rule id of the enabled SDK that class [type] belongs to (by prefix or recorded loader), or `null`. */
    fun ruleOfClass(type: Class<*>): String? {
        val current = table ?: return null
        val kind = current.matcher.classify(type.name)
        if (kind >= 0) return ruleIdOf(current.matcher.sdkId(kind))
        if (kind != OriginMatcher.UNKNOWN) return null
        val loader = type.classLoader ?: return null
        val sdkId = loaders.get().firstOrNull { it.ref.get() === loader }?.sdkId ?: return null
        return ruleIdOf(sdkId).takeIf { it in current.rules }
    }

    /** Records [value] (a rule id from [attribute], or [OWN]) for [target]. */
    fun tag(target: Any, value: String) {
        synchronized(tags) { tags[target] = value }
        anyTagged = true
    }

    /** Whether [target] was decided at creation (blocked or own). */
    fun isDecided(target: Any): Boolean {
        if (!anyTagged) return false
        return synchronized(tags) { tags.containsKey(target) }
    }

    /** The rule blocking [target], if it was tagged at creation and that rule is still enabled. */
    fun ruleOf(target: Any?): String? {
        if (target == null || !anyTagged) return null
        val current = table ?: return null
        val rule = synchronized(tags) { tags[target] } ?: return null
        return rule.takeIf { it != OWN && it in current.rules }
    }

    /** Remembers [loader], created by the code of [ruleId]'s SDK: classes it defines count as that SDK. */
    fun recordLoader(loader: ClassLoader, ruleId: String) {
        val sdkId = sdkIdOf(ruleId) ?: return
        while (true) {
            val current = loaders.get()
            val live = current.filter { it.ref.get() != null }
            if (live.size >= MAX_LOADERS) return
            if (loaders.compareAndSet(current, (live + Loader(WeakReference(loader), sdkId)).toTypedArray())) break
        }
        loaderClasses.clear()
    }

    /** Counts one block by [ruleId]; [what] is log-only. */
    fun hit(ruleId: String, what: String) {
        // Origin-only SDKs have no network rule whose state is reported at install: say it is active.
        if (reported.add(ruleId)) reporter.state(packageName, ruleId, HookState.ACTIVE)
        reporter.hit(packageName, ruleId, what)
    }

    private fun resolveByLoader(className: String): Int {
        val current = table ?: return OriginMatcher.UNKNOWN
        val cached = loaderClasses[className]
        val sdkId = cached ?: lookupLoader(className).also {
            if (loaderClasses.size < MAX_CACHED_CLASSES) loaderClasses[className] = it
        }
        if (sdkId.isEmpty()) return OriginMatcher.UNKNOWN
        return current.matcher.indexOf(sdkId)
    }

    private fun lookupLoader(className: String): String {
        val find = findLoaded ?: return NONE
        val owner = loaders.get().firstOrNull { entry ->
            val loader = entry.ref.get()
            val type = loader?.let { runCatching { find.invoke(it, className) as? Class<*> }.getOrNull() }
            type != null && type.classLoader === loader
        }
        return owner?.sdkId ?: NONE
    }

    private fun build(raw: Map<String, *>): Table? {
        val detected = TrackerProtection.detected(raw, packageName)?.toSet() ?: return null
        val hints = TrackerOrigin.origin(raw, packageName)
        val enabled = detected.mapNotNull(Trackers::byId)
            .filter { it.originBlockable && TrackerProtection.isBlocked(raw, packageName, it) }
            .associateBy { it.id }
        val entries = ArrayList<Pair<String, String>>()
        enabled.values.forEach { sdk -> sdk.codePrefixes.forEach { entries += it to sdk.id } }
        hints.relocated.forEach { (root, sdkId) -> if (sdkId in enabled) entries += root to sdkId }
        hints.suspects.forEach { suspect ->
            if (TrackerOrigin.isSuspectBlocked(raw, packageName, suspect.prefix)) {
                entries += suspect.prefix to SUSPECT_OWNER
            }
        }
        if (entries.isEmpty()) return null
        val matcher = OriginMatcher(entries, hints.own + "$packageName.")
        if (matcher.isEmpty) return null
        return Table(matcher, entries.mapTo(HashSet()) { ruleIdOf(it.second) })
    }

    companion object {
        /** Tag value of objects the app's own code created. */
        const val OWN = ""

        private const val NONE = ""
        private const val STACK_PROBE = "origin"
        private const val SUSPECT_OWNER = "suspect"
        private const val MAX_LOADERS = 16
        private const val MAX_CACHED_CLASSES = 4096

        private fun ruleIdOf(owner: String): String =
            if (owner == SUSPECT_OWNER) TrackerOrigin.SUSPECT_RULE_ID else TrackerProtection.ruleId(owner)

        private fun sdkIdOf(ruleId: String): String? = when (ruleId) {
            TrackerOrigin.SUSPECT_RULE_ID -> SUSPECT_OWNER
            else -> TrackerProtection.sdkIdOf(ruleId)
        }

        /**
         * Whether attribution can ever apply in [packageName] under [raw]: an ad SDK was detected or
         * the manifest has suspected ad packages. Otherwise nothing is hooked at all.
         */
        fun canEverMatch(raw: Map<String, *>, packageName: String): Boolean {
            val detected = TrackerProtection.detected(raw, packageName) ?: return false
            val hints = TrackerOrigin.origin(raw, packageName)
            return detected.any { Trackers.byId(it)?.originBlockable == true } || hints.suspects.isNotEmpty()
        }
    }
}
