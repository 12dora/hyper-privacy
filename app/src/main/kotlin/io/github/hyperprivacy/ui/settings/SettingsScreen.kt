package io.github.hyperprivacy.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.UiState
import io.github.hyperprivacy.ui.components.BackButton
import io.github.hyperprivacy.ui.components.NavRow
import io.github.hyperprivacy.ui.components.SectionHeader
import io.github.hyperprivacy.ui.components.SingleCard
import io.github.hyperprivacy.ui.components.SwitchRow
import io.github.hyperprivacy.ui.components.TitleSummary
import io.github.hyperprivacy.ui.components.ValueRow
import io.github.hyperprivacy.ui.components.groupedItems
import io.github.hyperprivacy.ui.components.listPadding
import io.github.hyperprivacy.ui.sampleState
import io.github.hyperprivacy.ui.theme.Appearance
import kotlinx.coroutines.delay

private const val OBSERVED_REFRESH_MS = 3_000L
private const val DISABLED_ALPHA = 0.38f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    state: UiState,
    appearance: Appearance,
    onAppearanceChange: (Appearance) -> Unit,
    onBack: () -> Unit,
    onOpenHealth: () -> Unit,
    onExport: () -> Unit,
    onDiagnosticChange: (Boolean) -> Unit,
    onResetDefaults: () -> Unit,
    onRefreshObserved: () -> Unit,
    onClearObserved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val repository = stringResource(R.string.repository_url)
    ObservedRefresh(polling = state.diagnosticMode, onRefresh = onRefreshObserved)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding(inner)) {
            appearanceSection(appearance, onAppearanceChange)
            diagnosticsSection(state, onOpenHealth, onExport, onDiagnosticChange)
            endpointsSection(state, onClearObserved)
            dataSection(state.module.canEdit) { confirmReset = true }
            aboutSection(state, repository, onOpenRepository = { runCatching { uriHandler.openUri(repository) } })
        }
    }
    if (confirmReset) {
        ResetDialog(
            onConfirm = {
                confirmReset = false
                onResetDefaults()
            },
            onDismiss = { confirmReset = false },
        )
    }
}

/** Loads the endpoint list when the page is shown, and keeps it fresh while diagnostic mode is on. */
@Composable
private fun ObservedRefresh(polling: Boolean, onRefresh: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentRefresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(lifecycle, polling) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            currentRefresh()
            while (polling) {
                delay(OBSERVED_REFRESH_MS)
                currentRefresh()
            }
        }
    }
}

private enum class DiagnosticsRow { HEALTH, EXPORT, MODE }

private fun LazyListScope.diagnosticsSection(
    state: UiState,
    onOpenHealth: () -> Unit,
    onExport: () -> Unit,
    onDiagnosticChange: (Boolean) -> Unit,
) {
    item(key = "diag-title") { SectionHeader(stringResource(R.string.settings_diagnostics)) }
    groupedItems(DiagnosticsRow.entries, key = { "diag/$it" }) { row ->
        when (row) {
            DiagnosticsRow.HEALTH -> NavRow(
                title = stringResource(R.string.health_title),
                summary = stringResource(R.string.settings_health_summary),
                onClick = onOpenHealth,
            )

            DiagnosticsRow.EXPORT -> NavRow(
                title = stringResource(R.string.health_export),
                summary = stringResource(R.string.health_export_summary),
                onClick = onExport,
            )

            DiagnosticsRow.MODE -> SwitchRow(
                title = stringResource(R.string.settings_diag_mode),
                summary = stringResource(R.string.settings_diag_mode_summary),
                checked = state.diagnosticMode,
                enabled = state.module.canEdit,
                onCheckedChange = onDiagnosticChange,
            )
        }
    }
}

private fun LazyListScope.dataSection(canEdit: Boolean, onReset: () -> Unit) {
    item(key = "data-title") { SectionHeader(stringResource(R.string.settings_data)) }
    item(key = "data") {
        SingleCard {
            TitleSummary(
                title = stringResource(R.string.settings_reset),
                summary = stringResource(R.string.settings_reset_summary),
                modifier = Modifier
                    .alpha(if (canEdit) 1f else DISABLED_ALPHA)
                    .fillMaxWidth()
                    .heightIn(min = Dimens.RowMinHeight)
                    .clickable(enabled = canEdit, role = Role.Button, onClick = onReset)
                    .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical),
            )
        }
    }
}

private data class AboutEntry(@StringRes val title: Int, val value: String, val onClick: (() -> Unit)? = null)

private fun LazyListScope.aboutSection(state: UiState, repository: String, onOpenRepository: () -> Unit) {
    val rows = listOfNotNull(
        AboutEntry(R.string.app_name, state.versionName),
        state.framework?.let { AboutEntry(R.string.about_framework, it) },
        AboutEntry(R.string.about_repository, repository.removePrefix("https://"), onOpenRepository),
    )
    item(key = "about-title") { SectionHeader(stringResource(R.string.settings_about)) }
    groupedItems(rows, key = { "about/${it.title}" }) { row ->
        ValueRow(stringResource(row.title), row.value, onClick = row.onClick)
    }
    item(key = "about-note") {
        Text(
            text = stringResource(R.string.xposed_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = Dimens.Small),
        )
    }
}

@Composable
private fun ResetDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_reset_title)) },
        text = { Text(stringResource(R.string.settings_reset_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_reset_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun SettingsPreview() {
    HyperPrivacyTheme { SettingsScreen(sampleState(), Appearance(), {}, {}, {}, {}, {}, {}, {}, {}) }
}
