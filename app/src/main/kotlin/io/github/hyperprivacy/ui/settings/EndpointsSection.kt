package io.github.hyperprivacy.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.ObservedEndpointUi
import io.github.hyperprivacy.ui.UiState
import io.github.hyperprivacy.ui.components.MonoText
import io.github.hyperprivacy.ui.components.SectionHeader
import io.github.hyperprivacy.ui.components.SingleCard
import io.github.hyperprivacy.ui.components.countString
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.sampleState

/** Upper bound on listed endpoints; the list is a lead generator, not an audit log. */
private const val MAX_ENDPOINTS = 100

private fun ObservedEndpointUi.key() = "$appLabel|$host|${path.orEmpty()}"

internal fun LazyListScope.endpointsSection(state: UiState, onClear: () -> Unit) {
    val endpoints = state.observed.distinctBy { it.key() }.take(MAX_ENDPOINTS)
    item(key = "endpoints-title") {
        SectionHeader(
            text = stringResource(R.string.settings_endpoints),
            trailing = if (endpoints.isEmpty()) {
                null
            } else {
                { TextButton(onClick = onClear) { Text(stringResource(R.string.settings_endpoints_clear)) } }
            },
        )
    }
    if (endpoints.isEmpty()) {
        item(key = "endpoints-empty") { EmptyEndpoints(state.diagnosticMode) }
    } else {
        groupedItems(endpoints, key = { "endpoint/${it.key()}" }) { EndpointRow(it) }
    }
}

@Composable
private fun EmptyEndpoints(diagnosticMode: Boolean) {
    SingleCard {
        Text(
            text = stringResource(
                if (diagnosticMode) R.string.settings_endpoints_empty else R.string.settings_endpoints_off,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical + Dimens.Tiny),
        )
    }
}

@Composable
private fun EndpointRow(endpoint: ObservedEndpointUi) {
    val colors = MaterialTheme.colorScheme
    val separator = stringResource(R.string.common_separator)
    val lastSeen = DateUtils.getRelativeTimeSpanString(
        endpoint.lastSeenMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    )
    val detail = listOf(endpoint.appLabel, countString(R.plurals.endpoint_count, endpoint.count), lastSeen.toString())
        .joinToString(separator)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowMinHeight)
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MonoText(endpoint.host + endpoint.path.orEmpty())
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(if (endpoint.blocked) R.string.endpoint_blocked else R.string.endpoint_allowed),
            style = MaterialTheme.typography.labelSmall,
            color = if (endpoint.blocked) colors.primary else colors.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EndpointRowPreview() {
    HyperPrivacyTheme {
        Column(Modifier.padding(Dimens.Gutter)) {
            sampleState().observed.forEach { EndpointRow(it) }
        }
    }
}
