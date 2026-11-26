package io.github.hyperprivacy.ui

import androidx.compose.runtime.saveable.Saver

/** Where the user is. Home is the root of a small back stack. */
internal sealed interface Route {
    data object Home : Route

    data object Settings : Route

    data object Search : Route

    data object Health : Route

    data class RuleDetail(val ruleId: String) : Route

    data class App(val packageName: String) : Route

    /** User-installed apps and tracker protection. */
    data object Apps : Route

    data class UserApp(val packageName: String) : Route

    fun encode(): String = when (this) {
        Home -> HOME
        Settings -> SETTINGS
        Search -> SEARCH
        Health -> HEALTH
        is RuleDetail -> RULE_PREFIX + ruleId
        is App -> APP_PREFIX + packageName
        Apps -> APPS
        is UserApp -> USER_APP_PREFIX + packageName
    }

    companion object {
        private const val HOME = "home"
        private const val SETTINGS = "settings"
        private const val SEARCH = "search"
        private const val HEALTH = "health"
        private const val RULE_PREFIX = "rule:"
        private const val APP_PREFIX = "app:"
        private const val APPS = "apps"
        private const val USER_APP_PREFIX = "user-app:"

        /** Saves the whole back stack, root first. */
        val StackSaver: Saver<List<Route>, ArrayList<String>> = Saver(
            save = { stack -> ArrayList(stack.map { it.encode() }) },
            restore = { saved -> saved.map(::decode).ifEmpty { listOf(Home) } },
        )

        private fun decode(text: String): Route = when {
            text == SETTINGS -> Settings
            text == SEARCH -> Search
            text == HEALTH -> Health
            text.startsWith(RULE_PREFIX) -> RuleDetail(text.removePrefix(RULE_PREFIX))
            text.startsWith(APP_PREFIX) -> App(text.removePrefix(APP_PREFIX))
            text == APPS -> Apps
            text.startsWith(USER_APP_PREFIX) -> UserApp(text.removePrefix(USER_APP_PREFIX))
            else -> Home
        }
    }
}
