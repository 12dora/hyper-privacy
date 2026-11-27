package io.github.hyperprivacy.xposed.fp

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.system.VendorTrust
import io.github.hyperprivacy.xposed.GuardEnv
import io.github.hyperprivacy.xposed.SinkStats
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** One family of readings the rule replaces; also the sink name and the log-only hit target. */
internal class TraitGroup(val name: String) {
    private val reported = AtomicBoolean()

    /** True exactly once, for the first replaced reading of this group in the process. */
    fun firstHit(): Boolean = !reported.get() && reported.compareAndSet(false, true)
}

/**
 * What every hook of one per-app perturbation rule (`fingerprint.hardware`, `sensor.data`) shares in
 * one process: the live switch and the per-app constants [derive]d once from the module secret.
 *
 * Hot path (one hooked call): a volatile read of [enabled], a volatile read of the cached
 * constants, and a volatile read to decide whether this group was already counted. Nothing is
 * allocated except the boxed value the hook API requires, and nothing touches IPC, disk or the
 * preferences. The switch is mirrored into [enabled] by [refresh] (called on every switch change)
 * rather than looked up in a map per call.
 *
 * Hits are counted once per group (sensor, file system, battery …) per process, not per call: the
 * reporter's counter allocates a key per hit, which these call sites must not do on every read.
 */
internal class FingerprintState<T : Any>(
    private val env: GuardEnv,
    val ruleId: String,
    private val derive: (packageName: String, secret: ByteArray) -> T,
) {
    private class Cached<T>(val secret: ByteArray, val value: T)

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    private var cached: Cached<T>? = null

    private val warnedNoSecret = AtomicBoolean()

    /**
     * Re-reads the switch and drops constants derived from a secret that has since changed.
     * Serialized, so concurrent switch changes cannot leave [enabled] older than the last one; reads
     * stay lock-free.
     */
    @Synchronized
    fun refresh() {
        enabled = env.switches.isEnabled(ruleId)
        val current = cached ?: return
        val secret = env.switches.secret
        if (secret == null || !current.secret.contentEquals(secret)) cached = null
    }

    /** The constants of this app, derived on first use; `null` while the module secret is missing. */
    fun value(): T? {
        cached?.let { return it.value }
        val secret = env.switches.secret ?: run {
            if (warnedNoSecret.compareAndSet(false, true)) env.log.warn("$ruleId: module secret missing; untouched")
            return null
        }
        val fresh = Cached(secret, derive(env.packageName, secret))
        cached = fresh
        return fresh.value
    }

    /**
     * Replaces [real] by `map(constants, real)` when that differs; returns `null` to keep [real]
     * (also while the secret is missing), reporting the first replacement of [group].
     */
    fun <R : Any> replace(group: TraitGroup, real: R, map: (T, R) -> R): R? {
        val constants = value() ?: return null
        val out = map(constants, real)
        if (out == real) return null
        hit(group)
        return out
    }

    /** Reports that [group] changed a reading; only the first per process reaches the reporter. */
    fun hit(group: TraitGroup) {
        if (group.firstHit()) env.reporter.hit(env.packageName, ruleId, group.name)
    }
}

/**
 * Installs a per-app perturbation rule that ships off: [hookAll] runs at process start when the
 * switch is already on, otherwise the first time it is switched on (later reads are then covered;
 * values read earlier are not retrofitted). Once installed, switching off makes every hook a
 * pass-through after one volatile read. Refuses vendor-catalog and vendor-namespace packages.
 *
 * Returns the number of methods hooked, or a positive value when hooks are armed for a later
 * switch-on, since an off rule is not a failure.
 */
internal fun GuardEnv.installWhenOn(state: FingerprintState<*>, hookAll: () -> Int): Int {
    if (packageName in Catalog.allPackages || VendorTrust.isVendorNamespace(packageName)) {
        log.warn("${state.ruleId}: $packageName is a vendor package; left untouched")
        return 0
    }
    val installed = AtomicBoolean()
    val once = {
        if (installed.compareAndSet(false, true)) hookAll() else ARMED
    }
    state.refresh()
    switches.addListener {
        state.refresh()
        if (state.enabled && !installed.get() && once() == 0) {
            reporter.state(packageName, state.ruleId, HookState.MISSED)
        }
    }
    return if (state.enabled) once() else ARMED
}

/** Runs [install] (a failure counts as zero hooks) and records the group's sink state. */
internal fun GuardEnv.reportSink(sink: String, install: () -> Int): Int {
    val hooked = log.guard(sink) { install() } ?: 0
    reporter.sink(packageName, sink, if (hooked > 0) HookState.ACTIVE else HookState.MISSED)
    return hooked
}

private const val ARMED = 1

/**
 * After the original ran: [body] maps its result to a replacement, or returns `null` to keep it.
 * Installed with [ExceptionMode.PROTECTIVE], so a failure in [body] returns the real result, while
 * the original's own exceptions still reach the caller. In diagnostics mode the cost of [body] is
 * recorded under [sink] (one volatile read otherwise).
 */
internal class ResultHook(private val state: FingerprintState<*>, sink: String, private val body: (Any?) -> Any?) :
    Hooker {
    private val cell = SinkStats.cell(sink)

    override fun intercept(chain: Chain): Any? {
        val real = chain.proceed()
        if (!state.enabled) return real
        if (!SinkStats.enabled) return body(real) ?: real
        val start = System.nanoTime()
        try {
            return body(real) ?: real
        } finally {
            cell.record(System.nanoTime() - start)
        }
    }
}

/**
 * Before the original runs: [body] maps the first argument to a replacement, or returns `null` to
 * proceed with the arguments unchanged (nothing is copied then). The call's argument array is
 * never written to. Fail-open and measured like [ResultHook].
 */
internal class ArgumentHook(private val state: FingerprintState<*>, sink: String, private val body: (Any?) -> Any?) :
    Hooker {
    private val cell = SinkStats.cell(sink)

    override fun intercept(chain: Chain): Any? {
        if (!state.enabled) return chain.proceed()
        val replacement = if (SinkStats.enabled) measured(chain) else body(chain.getArg(0))
        if (replacement == null) return chain.proceed()
        val args = chain.args.toTypedArray()
        args[0] = replacement
        return chain.proceed(args)
    }

    private fun measured(chain: Chain): Any? {
        val start = System.nanoTime()
        try {
            return body(chain.getArg(0))
        } finally {
            cell.record(System.nanoTime() - start)
        }
    }
}

/** Hooks [methods] with [hooker] (fail-open) and returns how many were hooked. */
internal fun GuardEnv.hookAll(methods: List<Method>, hooker: Hooker): Int {
    methods.forEach { xposed.hook(it).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(hooker) }
    return methods.size
}

/** The declared method of [type] named [name] taking [parameters], or `null` (logged) when absent. */
internal fun GuardEnv.declared(type: Class<*>, name: String, vararg parameters: Class<*>): Method? = try {
    type.getDeclaredMethod(name, *parameters)
} catch (_: NoSuchMethodException) {
    log.warn("${type.simpleName}#$name not found in $packageName")
    null
}
