package io.github.hyperprivacy

import android.app.Application

class HyperPrivacyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ModuleConnection.init()
    }
}
