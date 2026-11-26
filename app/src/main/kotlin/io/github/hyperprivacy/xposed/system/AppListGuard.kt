package io.github.hyperprivacy.xposed.system

import io.github.hyperprivacy.core.firewall.UidPolicy
import io.github.hyperprivacy.core.system.AppIndex
import io.github.hyperprivacy.xposed.FailOpenHooker
import io.github.hyperprivacy.xposed.HookLog
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.Sinks
import io.github.hyperprivacy.xposed.Verdict
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Hides installed third-party apps from the user's restricted apps ([SystemState.appList]).
 *
 * Hooks `com.android.server.pm.AppsFilterBase#shouldFilterApplication(PackageDataSnapshot, int
 * callingUid, Object callingSetting, PackageStateInternal targetPkgSetting, int userId)`, through
 * which every package-visibility decision of `ComputerEngine` passes: `getPackageInfo` /
 * `getApplicationInfo` / `getPackageUid` probing, `getInstalled{Packages,Applications}`,
 * `queryIntent*` / `resolve*` (and so the client's `getLaunchIntentForPackage`), `pm list
 * packages` (run as the caller's uid), and `getVisibilityAllowList`, which also keeps package
 * broadcasts about hidden apps from reaching the restricted app.
 *
 * Decided before the original runs: a hidden pair returns `true` (filtered); everything else
 * proceeds untouched. This is a PackageManager hot path, so with no restricted app the cost is
 * a volatile read and a couple of [io.github.hyperprivacy.core.system.IntSet] probes; for callers
 * that may be denied something (restricted apps; any third-party app while the module hides
 * itself) one int field read of the target. Exempt: the caller itself, vendor-trusted apps
 * ([io.github.hyperprivacy.core.system.VendorTrust]), non-application uids, allowlisted partner apps
 * (unless strict) and apps the caller has legitimately interacted with (`isImplicitlyQueryable`,
 * e.g. an app that shared content into it). An app id missing from the snapshot counts as
 * third-party and triggers a rebuild. SDK-sandbox callers need nothing here: AppsFilter already
 * hides almost everything from them.
 */
internal class AppListGuard(
    private val xposed: XposedInterface,
    private val log: HookLog,
    private val state: SystemState,
) {
    private var getAppId: Method? = null
    private var implicitlyQueryable: Method? = null
    private var retainedImplicitlyQueryable: Method? = null

    @Volatile
    private var appIdReader: AppIdReader? = null
    private val hidden = AtomicLong()
    private val loggedCallers: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    @Volatile
    var hooked: Boolean = false
        private set

    /** Total calls answered "filtered" by this guard since boot. */
    val hiddenCount: Long get() = hidden.get()

    /** [classLoader] is system_server's (services.jar). */
    fun install(classLoader: ClassLoader): Boolean {
        val base = Reflection.loadClass(FILTER_CLASS, classLoader)
        val target = base?.let(::findTarget)
        val appId = Reflection.loadClass(PACKAGE_STATE_CLASS, classLoader)
            ?.let { runCatching { it.getMethod(GET_APP_ID) }.getOrNull() }
        if (base == null || target == null || appId == null) {
            log.warn("app-list: $FILTER_CLASS#$SHOULD_FILTER or $PACKAGE_STATE_CLASS#$GET_APP_ID not found")
            return false
        }
        getAppId = appId
        implicitlyQueryable = intIntPredicate(base, IMPLICITLY_QUERYABLE)
        retainedImplicitlyQueryable = intIntPredicate(base, RETAINED_IMPLICITLY_QUERYABLE)
        if (implicitlyQueryable == null) log.warn("app-list: $IMPLICITLY_QUERYABLE missing; no interaction exemption")
        // PROTECTIVE: the hook returns a value or proceeds, it never throws on purpose.
        xposed.hook(target)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept(FailOpenHooker(log, Sinks.APP_LIST_FILTER, ::decide))
        hooked = true
        log.info("app-list: hooked $FILTER_CLASS#$SHOULD_FILTER")
        return true
    }

    /** The exact 5-argument overload; matched by shape so a renamed parameter type does not lose it. */
    private fun findTarget(base: Class<*>): Method? = base.declaredMethods.firstOrNull { method ->
        val params = method.parameterTypes
        method.name == SHOULD_FILTER && method.returnType == Boolean::class.javaPrimitiveType &&
            !Modifier.isAbstract(method.modifiers) && params.size == ARG_COUNT &&
            params[ARG_CALLING_UID] == Int::class.javaPrimitiveType &&
            params[ARG_USER_ID] == Int::class.javaPrimitiveType &&
            params[ARG_TARGET].name == PACKAGE_STATE_INTERNAL_CLASS
    }

    private fun intIntPredicate(base: Class<*>, name: String): Method? = runCatching {
        val int = Int::class.javaPrimitiveType
        base.getDeclaredMethod(name, int, int).apply { isAccessible = true }
    }.getOrNull()

    private fun decide(chain: Chain): Verdict {
        val policy = state.appList
        if (policy.isInactive) return Verdict.Proceed
        val callingUid = chain.getArg(ARG_CALLING_UID) as? Int ?: return Verdict.Proceed
        val caller = AppIndex.appIdOf(callingUid)
        if (!policy.mayHideFrom(caller)) return Verdict.Proceed
        val targetSetting = chain.getArg(ARG_TARGET) ?: return Verdict.Proceed
        val target = targetAppId(targetSetting)
        if (target < 0 || !policy.shouldHide(caller, target)) return Verdict.Proceed
        val userId = chain.getArg(ARG_USER_ID) as? Int ?: return Verdict.Proceed
        if (interacted(chain.thisObject, callingUid, userId * UidPolicy.PER_USER_RANGE + target)) {
            return Verdict.Proceed
        }
        hidden.incrementAndGet()
        val index = state.index
        if (!index.isKnown(caller)) state.requestIndexRefresh(caller)
        if (!index.isKnown(target)) state.requestIndexRefresh(target)
        if (loggedCallers.size < MAX_LOGGED_CALLERS && loggedCallers.add(caller)) {
            val pair = "${index.packageOf(target)} from ${index.packageOf(caller)}"
            log.info("app-list: hiding $pair (logged once per caller)")
        }
        return Verdict.Return(true)
    }

    /**
     * The target's app id. Third-party callers reach this on every visibility check (module
     * self-hiding), so it reads the `PackageSetting.mAppId` int field (no boxing) when the class
     * has one, and falls back to the interface getter otherwise.
     */
    private fun targetAppId(target: Any): Int {
        val type = target.javaClass
        val reader = appIdReader?.takeIf { it.type === type } ?: AppIdReader(type, intField(type, APP_ID_FIELD))
            .also { appIdReader = it }
        reader.field?.let { return it.getInt(target) }
        return getAppId?.invoke(target) as? Int ?: -1
    }

    private fun intField(type: Class<*>, name: String): Field? = generateSequence(type) { it.superclass }
        .firstNotNullOfOrNull { cls -> runCatching { cls.getDeclaredField(name) }.getOrNull() }
        ?.takeIf { it.type == Int::class.javaPrimitiveType }
        ?.apply { isAccessible = true }

    private class AppIdReader(val type: Class<*>, val field: Field?)

    private fun interacted(filter: Any?, callingUid: Int, targetUid: Int): Boolean =
        implicitlyQueryable?.invoke(filter, callingUid, targetUid) == true ||
            retainedImplicitlyQueryable?.invoke(filter, callingUid, targetUid) == true

    private companion object {
        const val FILTER_CLASS = "com.android.server.pm.AppsFilterBase"
        const val PACKAGE_STATE_CLASS = "com.android.server.pm.pkg.PackageState"
        const val PACKAGE_STATE_INTERNAL_CLASS = "com.android.server.pm.pkg.PackageStateInternal"
        const val SHOULD_FILTER = "shouldFilterApplication"
        const val GET_APP_ID = "getAppId"
        const val APP_ID_FIELD = "mAppId"
        const val IMPLICITLY_QUERYABLE = "isImplicitlyQueryable"
        const val RETAINED_IMPLICITLY_QUERYABLE = "isRetainedImplicitlyQueryable"
        const val ARG_COUNT = 5
        const val ARG_CALLING_UID = 1
        const val ARG_TARGET = 3
        const val ARG_USER_ID = 4
        const val MAX_LOGGED_CALLERS = 64
    }
}
