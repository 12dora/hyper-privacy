package io.github.hyperprivacy.xposed.origin

import io.github.hyperprivacy.core.match.HostMatcher
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.tracker.OriginHints
import io.github.hyperprivacy.core.tracker.OriginMatcher
import io.github.hyperprivacy.core.tracker.TrackerOrigin
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.core.tracker.Trackers
import io.github.hyperprivacy.xposed.HookLog
import io.github.hyperprivacy.xposed.RuleSwitches
import io.github.hyperprivacy.xposed.StatusReporter
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Origin attribution of advertising SDK code in one protected app process: decides, where a
 * request, socket, web view, class loader or page is *created*, whether an enabled ad SDK's code
 * asked for it ([OriginMatcher] over the creating stack), tags what it decided to block, and
 * answers the execution sinks from the tags. Async dispatch (OkHttp `enqueue`, executors,
 * Handlers) keeps the origin because the decision was taken on the creating thread.
 *
 * The [Table] of enabled prefixes is rebuilt from the preferences whenever they change
 * ([refresh], off the hooked threads, serialised) and published through one volatile field:
 * `null` means no ad SDK is enabled here *or protection is paused* ([RuleSwitches.paused], read
 * when the table is rebuilt; the switches notify their listeners when a pause starts or ends),
 * and every hook returns after that single read.
 *
 * Frames are read with [StackWalker] (class references kept, no `StackTraceElement` strings,
 * stops at the first decisive frame). A frame of this module's own class loader is skipped
 * whatever R8 renamed its class to; a frame whose class no prefix claims counts as an SDK only
 * when its actual class loader is one the SDK created ([recordLoader], compared by identity),
 * never by its name, so an obfuscated app or module class can never be taken for a plugin's.
 *
 * Costs per hooked call while a table is set:
 * - creation points: one stack walk up to the first decisive frame (a few µs) plus one trie walk
 *   per frame read; these run per new call, socket, web view or loader, never per connect or byte;
 * - execution sinks ([ruleOf]): one volatile read, then, only once something was blocked, one
 *   lock-free identity probe ([IdentityTags]); no allocation;
 * - keep hosts ([isKept]): one suffix-table lookup on a host already at hand.
 *
 * Every entry point is fail-open: callers run it under `FailOpenHooker` or catch everything.
 */
internal class AdOrigin(
    private val log: HookLog,
    private val packageName: String,
    private val reporter: StatusReporter,
    private val switches: RuleSwitches,
    private val read: () -> Map<String, *>,
) {
    /** What is enabled now: the matcher and the rule ids it can return. */
    class Table(val matcher: OriginMatcher, val rules: Set<String>)

    @Volatile
    private var table: Table? = null

    /** Objects decided at creation: a blocking rule id, [OWN] for the app's own, or [SEEN]. */
    private val tags = IdentityTags<String>()

    /** Whether anything was ever tagged with a rule: until then sinks skip the tag probe. */
    @Volatile
    private var anyBlocked = false

    private class Loader(val ref: WeakReference<ClassLoader>, val sdkId: String)

    private val loaders = AtomicReference<Array<Loader>>(emptyArray())
    private val reported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
    private val moduleLoader: ClassLoader? = AdOrigin::class.java.classLoader
    private val frameName: (StackWalker.StackFrame) -> String = { it.className }
    private val ownFrame: (StackWalker.StackFrame) -> Boolean = { it.declaringClass.classLoader === moduleLoader }

    /** Whether any ad SDK is enabled and protection is not paused; one volatile read. */
    val active: Boolean get() = table != null

    /**
     * Recomputes the table from [raw] (or a fresh read of the preferences): on the listener or
     * install thread, never a hooked one. Serialised, and the preferences are read inside the
     * lock, so a slow stale rebuild can never overwrite a newer one.
     */
    @Synchronized
    fun refresh(raw: Map<String, *>? = null) {
        table = log.guard("build origin table") {
            if (switches.paused) null else build(raw ?: read())
        }
    }

    /**
     * The rule id of the enabled ad SDK whose code is creating something right now, [OWN] when the
     * app's own code is, or `null`. Walks the current stack once, up to the first decisive frame.
     */
    fun attribute(): String? {
        val current = table ?: return null
        val plugins = loaders.get()
        val resolver: ((StackWalker.StackFrame) -> Int)? = if (plugins.isEmpty()) {
            null
        } else {
            { frame -> loaderIndex(current, plugins, frame.declaringClass.classLoader) }
        }
        val index = walker.walk { frames ->
            current.matcher.attribute(frames.iterator(), frameName, ownFrame, resolver)
        }
        return when {
            index == OriginMatcher.OWN -> OWN
            index < 0 -> null
            else -> ruleIdOf(current.matcher.sdkId(index))
        }
    }

    /** Rule id of the enabled SDK that class [type] belongs to (by prefix or recorded loader), or `null`. */
    fun ruleOfClass(type: Class<*>): String? {
        val current = table ?: return null
        val loader = type.classLoader
        if (loader === moduleLoader) return null
        val kind = current.matcher.classify(type.name)
        if (kind >= 0) return ruleIdOf(current.matcher.sdkId(kind))
        if (kind != OriginMatcher.UNKNOWN) return null
        val index = loaderIndex(current, loaders.get(), loader ?: return null)
        return if (index >= 0) ruleIdOf(current.matcher.sdkId(index)) else null
    }

    /** Rule id of the enabled SDK claiming class name [className] by prefix only (no loader), or `null`. */
    fun ruleOfName(className: String): String? {
        val current = table ?: return null
        val kind = current.matcher.classify(className)
        return if (kind >= 0) ruleIdOf(current.matcher.sdkId(kind)) else null
    }

    /** Records [value] (a rule id from [attribute], [OWN] or [SEEN]) for [target], by identity. */
    fun tag(target: Any, value: String) {
        tags.put(target, value)
        if (value != OWN && value != SEEN) anyBlocked = true
    }

    /** What [target] was tagged with, if anything. */
    fun tagOf(target: Any): String? = tags[target]

    /** The rule blocking [target], if it was tagged at creation and that rule is still enabled. */
    fun ruleOf(target: Any?): String? {
        if (target == null || !anyBlocked) return null
        val current = table ?: return null
        val rule = tags[target] ?: return null
        return rule.takeIf { it in current.rules }
    }

    /** Remembers [loader], created by the code of [ruleId]'s SDK: classes it defines count as that SDK. */
    fun recordLoader(loader: ClassLoader, ruleId: String) {
        val sdkId = sdkIdOf(ruleId) ?: return
        while (true) {
            val current = loaders.get()
            if (current.any { it.ref.get() === loader }) return
            val live = current.filter { it.ref.get() != null }
            if (live.size >= MAX_LOADERS) return
            if (loaders.compareAndSet(current, (live + Loader(WeakReference(loader), sdkId)).toTypedArray())) return
        }
    }

    /** Counts one block by [ruleId]; [what] is log-only. */
    fun hit(ruleId: String, what: String) {
        // Origin-only SDKs have no network rule whose state is reported at install: say it is active.
        if (reported.add(ruleId)) reporter.state(packageName, ruleId, HookState.ACTIVE)
        reporter.hit(packageName, ruleId, what)
    }

    /** The matcher index of the SDK that created [loader] (by identity), or [OriginMatcher.UNKNOWN]. */
    private fun loaderIndex(current: Table, plugins: Array<Loader>, loader: ClassLoader?): Int {
        if (loader == null || loader === moduleLoader) return OriginMatcher.UNKNOWN
        for (entry in plugins) {
            if (entry.ref.get() === loader) return current.matcher.indexOf(entry.sdkId)
        }
        return OriginMatcher.UNKNOWN
    }

    private fun build(raw: Map<String, *>): Table? {
        val detected = TrackerProtection.detected(raw, packageName)?.toSet() ?: return null
        val hints = TrackerOrigin.origin(raw, packageName)
        val enabled = detected.mapNotNull(Trackers::byId)
            .filter { it.originBlockable && TrackerProtection.isBlocked(raw, packageName, it) }
            .associateBy { it.id }
        // Derived roots never claim code inside the app's own roots, whatever an older scan stored.
        val derived = { root: String -> !OriginHints.isInsideApp(root, packageName, hints.appRoots) }
        val entries = ArrayList<Pair<String, String>>()
        enabled.values.forEach { sdk -> sdk.codePrefixes.forEach { entries += it to sdk.id } }
        hints.relocated.forEach { (root, sdkId) -> if (sdkId in enabled && derived(root)) entries += root to sdkId }
        hints.suspects.forEach { suspect ->
            if (derived(suspect.prefix) && TrackerOrigin.isSuspectBlocked(raw, packageName, suspect.prefix)) {
                entries += suspect.prefix to SUSPECT_OWNER
            }
        }
        if (entries.isEmpty()) return null
        // Consent packages of an enabled SDK (excludePrefixes) count as the app's own code.
        val exempt = enabled.values.flatMap { it.excludePrefixes }
        val matcher = OriginMatcher(entries, hints.own + hints.appRoots + "$packageName." + exempt)
        if (matcher.isEmpty) return null
        return Table(matcher, entries.mapTo(HashSet()) { ruleIdOf(it.second) })
    }

    companion object {
        /** Tag value of objects the app's own code created. */
        const val OWN = ""

        /** Tag value of web views whose loads were attributed once and claimed by nobody. */
        const val SEEN = "\u0000seen"

        private const val SUSPECT_OWNER = "suspect"
        private const val MAX_LOADERS = 16

        /** Every SDK's keep hosts, built once per process on first use. */
        private val KEEP: HostMatcher<Unit> by lazy { HostMatcher.of(Trackers.keepHosts.map { it to Unit }) }

        /** Whether [host] is a keep host of any SDK ([Trackers.keepHosts]): never blocked by origin either. */
        fun isKept(host: String?): Boolean = host != null && KEEP.matches(host)

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
