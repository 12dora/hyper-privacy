package io.github.hyperprivacy.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** BCP 47 tag of the UI language, used to pick the right [io.github.hyperprivacy.core.rule.Text]. */
internal val LocalLanguageTag = compositionLocalOf { "en" }

/** Material 3 with wallpaper-derived dynamic color (always available on minSdk 34). */
@Composable
internal fun HyperPrivacyTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    val tag = currentLanguageTag()
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalLanguageTag provides tag, content = content)
    }
}

@Composable
@ReadOnlyComposable
private fun currentLanguageTag(): String = LocalConfiguration.current.locales[0].toLanguageTag()
