package io.github.hyperprivacy.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Calls [onChange] on every resume and, while resumed, whenever a package is added, updated or
 * removed, so such changes while HyperPrivacy is open show up without a restart. Package
 * broadcasts only arrive for packages this app can see, which the generated `<queries>` covers.
 */
internal class PackageWatcher(private val context: Context, private val onChange: () -> Unit) :
    DefaultLifecycleObserver {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onChange()
    }
    private var registered = false

    override fun onResume(owner: LifecycleOwner) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme(PACKAGE_SCHEME)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        onChange()
    }

    override fun onPause(owner: LifecycleOwner) {
        if (registered) context.unregisterReceiver(receiver)
        registered = false
    }

    private companion object {
        const val PACKAGE_SCHEME = "package"
    }
}
