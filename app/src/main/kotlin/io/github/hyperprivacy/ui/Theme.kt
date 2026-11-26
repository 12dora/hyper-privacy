package io.github.hyperprivacy.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.github.hyperprivacy.ui.theme.Appearance
import io.github.hyperprivacy.ui.theme.DarkMode
import io.github.hyperprivacy.ui.theme.pureBlack

/** BCP 47 tag of the UI language, used to pick the right [io.github.hyperprivacy.core.rule.Text]. */
internal val LocalLanguageTag = compositionLocalOf { "en" }

/** Whether [appearance] resolves to dark right now. */
@Composable
@ReadOnlyComposable
internal fun Appearance.isDark(): Boolean = when (mode) {
    DarkMode.SYSTEM -> isSystemInDarkTheme()
    DarkMode.LIGHT -> false
    DarkMode.DARK -> true
}

/** Material 3 with the chosen palette (wallpaper-derived dynamic color by default). */
@Composable
internal fun HyperPrivacyTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = appearance.isDark()
    val colors = remember(context, appearance.palette, dark, appearance.pureBlack) {
        val base = appearance.palette.scheme(context, dark)
        if (dark && appearance.pureBlack) base.pureBlack() else base
    }
    val tag = currentLanguageTag()
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalLanguageTag provides tag, content = content)
    }
}

@Composable
@ReadOnlyComposable
private fun currentLanguageTag(): String =
    LocalConfiguration.current.locales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag() ?: "en"
