package io.github.hyperprivacy.ui.apps

import androidx.compose.runtime.Immutable
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.system.AppListConfig
import io.github.hyperprivacy.core.tracker.AppDefaults
import io.github.hyperprivacy.core.tracker.TrackerCategory

/** Where the offline scan of one app stands. */
internal enum class ScanState { PENDING, DONE, FAILED }

/** One SDK detected in one app, with the user's choice for it. */
@Immutable
internal data class SdkUi(
    val id: String,
    val name: Text,
    val category: TrackerCategory,
    /**
     * False for detection-only SDKs (push delivery, unverified endpoints): nothing to switch. Ad SDKs
     * are always switchable (blocked by origin, whatever hosts they use).
     */
    val blockable: Boolean,
    /** False for SDKs whose blocking can affect sign-in, payment or updates; they need an opt-in. */
    val defaultBlock: Boolean,
    val blocked: Boolean,
    val hits: Long,
)

/**
 * One suspected ad package in one app: the package [root] of manifest components that look like
 * ad pages but belong to no known SDK, with the matching component names. Off unless enabled.
 */
@Immutable
internal data class SuspectUi(val root: String, val components: List<String>, val blocked: Boolean)

/** One app-level protection ([io.github.hyperprivacy.core.rule.Catalog.protectedAppRules]) in one app. */
@Immutable
internal data class AppRuleUi(val id: String, val title: Text, val summary: Text, val enabled: Boolean, val hits: Long)

/** A user-installed app on the Apps page. */
@Immutable
internal data class UserAppUi(
    val packageName: String,
    val label: String,
    val scan: ScanState,
    val sdks: List<SdkUi>,
    val appRules: List<AppRuleUi>,
    val protected: Boolean,
    /** The module is in this app's LSPosed scope (required for protection to take effect). */
    val scoped: Boolean,
    val requesting: Boolean,
    val hideApps: Boolean,
    val strict: Boolean,
    val suspects: List<SuspectUi> = emptyList(),
    /** Requests and pages blocked in enabled [suspects], all packages together. */
    val suspectHits: Long = 0,
) {
    val sdkCount: Int get() = sdks.size

    /** Everything blocked in this app: SDKs, app-level protections and suspected ad packages. */
    val hits: Long get() = sdks.sumOf { it.hits } + appRules.sumOf { it.hits } + suspectHits

    /** Every detected SDK can only be detected, not blocked. */
    val detectionOnly: Boolean get() = sdks.isNotEmpty() && sdks.none { it.blockable }

    /** Protection is on but LSPosed has not (yet) added the app to the scope. */
    val awaitingScope: Boolean get() = protected && !scoped
}

@Immutable
internal data class AppsUi(
    val loaded: Boolean = false,
    val apps: List<UserAppUi> = emptyList(),
    val scanned: Int = 0,
    val total: Int = 0,
    /** What a newly protected app starts with. */
    val defaults: AppDefaults = AppDefaults.BUILT_IN,
    /** Apps restricted apps still see unless strict ([AppListConfig.ALLOW_KEY]). */
    val allowlist: Set<String> = AppListConfig.DEFAULT_ALLOWLIST,
    /** [allowlist] is the user's own, not the built-in one. */
    val allowlistCustom: Boolean = false,
) {
    val scanning: Boolean get() = scanned < total

    /** What the protected apps have blocked so far (only apps listed so far count). */
    val protectedHits: Long get() = apps.sumOf { if (it.protected) it.hits else 0L }

    fun app(packageName: String): UserAppUi? = apps.firstOrNull { it.packageName == packageName }
}

/** The search of the Apps page: whether the search bar is open, and what is typed in it. */
@Immutable
internal data class AppsSearch(val active: Boolean = false, val query: String = "") {
    private val needle: String = query.trim()

    /** Whether the typed text narrows the list. */
    val filtering: Boolean = active && needle.isNotEmpty()

    /** True when [app]'s label or package name contains the typed text (case-insensitive). */
    fun matches(app: UserAppUi): Boolean = !filtering ||
        app.label.contains(needle, ignoreCase = true) ||
        app.packageName.contains(needle, ignoreCase = true)
}

/** Callbacks of the Apps page. */
internal class AppsActions(
    val onFilterChange: (Boolean) -> Unit,
    val onSearchChange: (Boolean) -> Unit,
    val onQueryChange: (String) -> Unit,
    val onToggle: (String, Boolean) -> Unit,
    val onOpen: (String) -> Unit,
    val onOpenDefaults: () -> Unit = {},
)

/** Callbacks of the app detail page. */
internal class UserAppActions(
    val onProtect: (Boolean) -> Unit,
    val onRetryScope: () -> Unit,
    val onSdk: (String, Boolean) -> Unit,
    val onAppRule: (String, Boolean) -> Unit,
    val onSuspect: (String, Boolean) -> Unit,
    val onHideApps: (Boolean) -> Unit,
    val onStrict: (Boolean) -> Unit,
    val onBack: () -> Unit,
    val onOpenAllowlist: () -> Unit = {},
)

/** Callbacks of the default-settings page. */
internal class AppDefaultsActions(
    val onSdk: (TrackerCategory, Boolean) -> Unit,
    val onRule: (String, Boolean) -> Unit,
    val onHideApps: (Boolean) -> Unit,
    val onStrict: (Boolean) -> Unit,
    val onBack: () -> Unit,
)

/** Callbacks of the allowlist page. */
internal class AllowlistActions(
    val onToggle: (String, Boolean) -> Unit,
    val onReset: () -> Unit,
    val onBack: () -> Unit,
)
