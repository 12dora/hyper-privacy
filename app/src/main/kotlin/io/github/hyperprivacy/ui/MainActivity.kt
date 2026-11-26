package io.github.hyperprivacy.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hyperprivacy.BuildConfig
import io.github.hyperprivacy.ui.theme.AppearanceStore

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Debug builds only: sample data for screenshots, without the module, LSPosed or the preferences.
        val demo = if (BuildConfig.DEBUG) demoFactory?.create(this) else null
        val model: UiModel = demo ?: viewModel.also(::watchPackages)
        setContent {
            val appearance by AppearanceStore.appearance.collectAsStateWithLifecycle()
            // Loading takes a frame or two; showing nothing beats flashing the wrong palette.
            val current = appearance ?: return@setContent
            val dark = current.isDark()
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            HyperPrivacyTheme(current) {
                AppNavigation(model)
            }
        }
    }

    private fun watchPackages(live: MainViewModel) {
        val onPackagesChanged = {
            live.device.refresh()
            live.trackers.refresh()
            live.conflicts.refresh()
        }
        lifecycle.addObserver(PackageWatcher(applicationContext, onPackagesChanged))
    }
}
