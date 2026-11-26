package io.github.hyperprivacy

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks the binder the Xposed framework hands to the module app when the module is active. */
object ModuleConnection {
    private val mutableService = MutableStateFlow<XposedService?>(null)

    /** The bound framework service, or `null` while the module is not active. */
    val service: StateFlow<XposedService?> = mutableService.asStateFlow()

    /** Must be called once, from [android.app.Application.onCreate]. */
    fun init() {
        XposedServiceHelper.registerListener(
            object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    mutableService.value = service
                }

                override fun onServiceDied(service: XposedService) {
                    mutableService.compareAndSet(service, null)
                }
            },
        )
    }
}
