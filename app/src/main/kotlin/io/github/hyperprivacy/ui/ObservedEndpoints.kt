package io.github.hyperprivacy.ui

import androidx.compose.runtime.Immutable

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

/** Used until diagnostic data is wired in. */
internal object EmptyObservedEndpointsSource : ObservedEndpointsSource {
    override fun observed(): List<ObservedEndpointUi> = emptyList()
}
