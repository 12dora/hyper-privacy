package io.github.hyperprivacy.ui

import androidx.compose.runtime.Immutable
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.status.StatusStore

/** A reporting endpoint seen while diagnostic mode was on. */
@Immutable
internal data class ObservedEndpointUi(
    val appLabel: String,
    val host: String,
    val path: String?,
    val count: Long,
    val lastSeenMillis: Long,
    val blocked: Boolean,
)

/** Source of the endpoints listed under Settings. Implementations may block; callers use a worker thread. */
internal interface ObservedEndpointsSource {
    fun observed(): List<ObservedEndpointUi>
}

/** Endpoints recorded by the hooks, newest first, labelled with the owning app's name. */
internal class StatusStoreEndpoints(private val store: StatusStore, private val apps: InstalledApps) :
    ObservedEndpointsSource {
    override fun observed(): List<ObservedEndpointUi> {
        val newestFirst = store.observedEndpoints().sortedByDescending { it.lastSeen }
        return newestFirst.map { it.toUi(apps) }
    }
}

/** Labels [ObservedEndpoint] with the owning app's display name. */
internal fun ObservedEndpoint.toUi(apps: InstalledApps) = ObservedEndpointUi(
    appLabel = apps.labelOf(packageName),
    host = host,
    path = pathPrefix,
    count = count,
    lastSeenMillis = lastSeen,
    blocked = blocked,
)
