package io.github.hyperprivacy.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.ObservedEndpointUi
import io.github.hyperprivacy.ui.UiState
import io.github.hyperprivacy.ui.components.BackButton
import io.github.hyperprivacy.ui.components.MonoText
import io.github.hyperprivacy.ui.components.SingleCard
import io.github.hyperprivacy.ui.components.countString
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.components.listPadding
import io.github.hyperprivacy.ui.sampleState

/** Upper bound on listed endpoints; the list is a lead generator, not an audit log. */
private const val MAX_ENDPOINTS = 100

private fun ObservedEndpointUi.key() = "$appLabel|$host|${path.orEmpty()}"

private fun UiState.endpoints(): List<ObservedEndpointUi> = observed.distinctBy { it.key() }.take(MAX_ENDPOINTS)

/** The endpoints row's summary in Settings: how many were recorded, or how to start recording. */
@Composable
internal fun endpointsSummary(state: UiState): String {
    val count = remember(state.observed) { state.endpoints() }.size
    return when {
        count > 0 -> pluralStringResource(R.plurals.settings_endpoints_count, count, count)
        state.diagnosticMode -> stringResource(R.string.settings_endpoints_empty)
        else -> stringResource(R.string.settings_endpoints_off)
    }
}

/** Endpoints recorded in diagnostic mode, newest first; kept fresh while diagnostic mode is on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EndpointsScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ObservedRefresh(polling = state.diagnosticMode, onRefresh = onRefresh)
    val endpoints = remember(state.observed) { state.endpoints() }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_endpoints)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (endpoints.isNotEmpty()) {
                        TextButton(onClick = onClear) { Text(stringResource(R.string.settings_endpoints_clear)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding(inner)) {
            if (endpoints.isEmpty()) {
                item(key = "endpoints-empty") { EmptyEndpoints(state.diagnosticMode) }
            } else {
                groupedItems(endpoints, key = { "endpoint/${it.key()}" }) { EndpointRow(it) }
            }
        }
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
