package io.github.hyperprivacy.ui.apps

import androidx.compose.runtime.Immutable
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.tracker.TrackerCategory

/** Where the offline scan of one app stands. */
internal enum class ScanState { PENDING, DONE, FAILED }

/** One SDK detected in one app, with the user's choice for it. */
@Immutable
internal data class SdkUi(
    val id: String,
    val name: Text,
    val vendor: String,
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
) {
    val scanning: Boolean get() = scanned < total

    fun app(packageName: String): UserAppUi? = apps.firstOrNull { it.packageName == packageName }
}

/** Callbacks of the Apps page. */
internal class AppsActions(
    val onFilterChange: (Boolean) -> Unit,
    val onToggle: (String, Boolean) -> Unit,
    val onOpen: (String) -> Unit,
    val onBack: () -> Unit,
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
)
