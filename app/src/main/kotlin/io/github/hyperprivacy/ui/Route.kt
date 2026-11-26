package io.github.hyperprivacy.ui

import androidx.compose.runtime.saveable.Saver

/** Where the user is. Home is the root; the other two are one level deep. */
internal sealed interface Route {
    data object Home : Route

    data object Settings : Route

    data class RuleDetail(val ruleId: String) : Route

    fun encode(): String = when (this) {
        Home -> HOME
        Settings -> SETTINGS
        is RuleDetail -> RULE_PREFIX + ruleId
    }

    companion object {
        private const val HOME = "home"
        private const val SETTINGS = "settings"
        private const val RULE_PREFIX = "rule:"

        val Saver: Saver<Route, String> = Saver(save = { it.encode() }, restore = ::decode)

        private fun decode(text: String): Route = when {
            text == SETTINGS -> Settings
            text.startsWith(RULE_PREFIX) -> RuleDetail(text.removePrefix(RULE_PREFIX))
            else -> Home
        }
    }
}
