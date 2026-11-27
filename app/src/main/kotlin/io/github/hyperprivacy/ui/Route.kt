package io.github.hyperprivacy.ui

import androidx.compose.runtime.saveable.Saver

/** The three destinations of the bottom navigation bar, all shown by [Route.Tabs]. */
internal enum class Tab { OVERVIEW, SYSTEM, APPS }

/** Where the user is. [Tabs] is the root of a small back stack; every other page pushes over it. */
internal sealed interface Route {
    /** The bottom-bar destinations ([Tab]); which one is shown is kept apart from the stack. */
    data object Tabs : Route

    data object Settings : Route

    data object Search : Route

    data object Health : Route

    /** Endpoints recorded in diagnostic mode. */
    data object Endpoints : Route

    data class RuleDetail(val ruleId: String) : Route

    /** A system app the catalog covers. */
    data class App(val packageName: String) : Route

    /** What a newly protected user-installed app starts with. */
    data object AppDefaults : Route

    /** Apps left visible to apps that have app-list hiding on. */
    data object Allowlist : Route

    /** A user-installed app and its protection. */
    data class UserApp(val packageName: String) : Route

    fun encode(): String = when (this) {
        Tabs -> TABS
        Settings -> SETTINGS
        Search -> SEARCH
        Health -> HEALTH
        Endpoints -> ENDPOINTS
        AppDefaults -> APP_DEFAULTS
        Allowlist -> ALLOWLIST
        is RuleDetail -> RULE_PREFIX + ruleId
        is App -> APP_PREFIX + packageName
        is UserApp -> USER_APP_PREFIX + packageName
    }

    companion object {
        private const val TABS = "tabs"
        private const val SETTINGS = "settings"
        private const val SEARCH = "search"
        private const val HEALTH = "health"
        private const val ENDPOINTS = "endpoints"
        private const val APP_DEFAULTS = "app-defaults"
        private const val ALLOWLIST = "allowlist"
        private const val RULE_PREFIX = "rule:"
        private const val APP_PREFIX = "app:"
        private const val USER_APP_PREFIX = "user-app:"

        /** Saves the whole back stack, root first; unknown entries are dropped and the root is always [Tabs]. */
        val StackSaver: Saver<List<Route>, ArrayList<String>> = Saver(
            save = { stack -> ArrayList(stack.map { it.encode() }) },
            restore = { saved -> listOf(Tabs) + saved.mapNotNull(::decode).filter { it != Tabs } },
        )

        private fun decode(text: String): Route? = when {
            text == TABS -> Tabs
            text == SETTINGS -> Settings
            text == SEARCH -> Search
            text == HEALTH -> Health
            text == ENDPOINTS -> Endpoints
            text == APP_DEFAULTS -> AppDefaults
            text == ALLOWLIST -> Allowlist
            text.startsWith(RULE_PREFIX) -> RuleDetail(text.removePrefix(RULE_PREFIX))
            text.startsWith(APP_PREFIX) -> App(text.removePrefix(APP_PREFIX))
            text.startsWith(USER_APP_PREFIX) -> UserApp(text.removePrefix(USER_APP_PREFIX))
            else -> null
        }
    }
}
