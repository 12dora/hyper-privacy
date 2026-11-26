package io.github.hyperprivacy

import android.app.Application
import io.github.hyperprivacy.ui.theme.AppearanceStore

class HyperPrivacyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ModuleConnection.init()
        AppearanceStore.init(this)
    }
}
