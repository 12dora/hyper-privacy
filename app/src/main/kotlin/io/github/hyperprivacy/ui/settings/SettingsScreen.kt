package io.github.hyperprivacy.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
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
    backup: BackupActions,
    onDiagnosticChange: (Boolean) -> Unit,
    onSelfHideChange: (Boolean) -> Unit,
    onResetDefaults: () -> Unit,
    onOpenEndpoints: () -> Unit,
    onOpenConflicts: () -> Unit,
    onOpenAllowlist: () -> Unit,
    onRefreshObserved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val repository = stringResource(R.string.repository_url)
    val context = LocalContext.current
    val noBrowser = stringResource(R.string.settings_link_none)
    // No network access here (the app has no INTERNET permission): the browser shows the latest release.
    val checkUpdate = { openLink(context, "${repository.trimEnd('/')}/releases/latest", noBrowser) }
    // Only for the count on the endpoints row; the endpoints page keeps the list fresh.
    ObservedRefresh(polling = false, onRefresh = onRefreshObserved)
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
            privacySection(state, onSelfHideChange, onOpenAllowlist)
            backupSection(state.module.canEdit, backup) { confirmReset = true }
            val diagnostics =
                DiagnosticsActions(onOpenHealth, onExport, onDiagnosticChange, onOpenEndpoints, onOpenConflicts)
            diagnosticsSection(state, diagnostics)
            aboutSection(state, repository, checkUpdate) { openLink(context, repository, noBrowser) }
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

/** Loads the endpoint list when the page is shown, and with [polling] keeps it fresh. */
@Composable
internal fun ObservedRefresh(polling: Boolean, onRefresh: () -> Unit) {
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

private enum class PrivacyRow { SELF_HIDE, ALLOWLIST }

private fun LazyListScope.privacySection(
    state: UiState,
    onSelfHideChange: (Boolean) -> Unit,
    onOpenAllowlist: () -> Unit,
) {
    item(key = "privacy-title") { SectionHeader(stringResource(R.string.settings_privacy)) }
    groupedItems(PrivacyRow.entries, key = { "privacy/$it" }) { row ->
        when (row) {
            PrivacyRow.SELF_HIDE -> SwitchRow(
                title = stringResource(R.string.settings_self_hide),
                summary = stringResource(R.string.settings_self_hide_summary),
                checked = state.selfHidden,
                enabled = state.module.canEdit,
                onCheckedChange = onSelfHideChange,
            )

            PrivacyRow.ALLOWLIST -> NavRow(
                title = stringResource(R.string.apps_allow_title),
                summary = stringResource(R.string.apps_allow_summary),
                onClick = onOpenAllowlist,
            )
        }
    }
}

private enum class BackupRow { EXPORT, IMPORT, RESET }

private fun LazyListScope.backupSection(canEdit: Boolean, backup: BackupActions, onReset: () -> Unit) {
    item(key = "backup-title") { SectionHeader(stringResource(R.string.settings_backup)) }
    groupedItems(BackupRow.entries, key = { "backup/$it" }) { row ->
        when (row) {
            BackupRow.EXPORT -> NavRow(
                title = stringResource(R.string.backup_export),
                summary = stringResource(R.string.backup_export_summary),
                onClick = { if (canEdit) backup.onExport() },
                modifier = Modifier.alpha(if (canEdit) 1f else DISABLED_ALPHA),
            )

            BackupRow.IMPORT -> NavRow(
                title = stringResource(R.string.backup_import),
                summary = stringResource(R.string.backup_import_summary),
                onClick = { if (canEdit) backup.onImport() },
                modifier = Modifier.alpha(if (canEdit) 1f else DISABLED_ALPHA),
            )

            BackupRow.RESET -> TitleSummary(
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

private enum class DiagnosticsRow { HEALTH, EXPORT, MODE, ENDPOINTS, CONFLICTS }

private class DiagnosticsActions(
    val onOpenHealth: () -> Unit,
    val onExport: () -> Unit,
    val onDiagnosticChange: (Boolean) -> Unit,
    val onOpenEndpoints: () -> Unit,
    val onOpenConflicts: () -> Unit,
)

private fun LazyListScope.diagnosticsSection(state: UiState, actions: DiagnosticsActions) {
    item(key = "diag-title") { SectionHeader(stringResource(R.string.settings_diagnostics)) }
    groupedItems(DiagnosticsRow.entries, key = { "diag/$it" }) { row ->
        when (row) {
            DiagnosticsRow.HEALTH -> NavRow(
                title = stringResource(R.string.health_title),
                summary = stringResource(R.string.settings_health_summary),
                onClick = actions.onOpenHealth,
            )

            DiagnosticsRow.EXPORT -> NavRow(
                title = stringResource(R.string.health_export),
                summary = stringResource(R.string.health_export_summary),
                onClick = actions.onExport,
            )

            DiagnosticsRow.MODE -> SwitchRow(
                title = stringResource(R.string.settings_diag_mode),
                summary = stringResource(R.string.settings_diag_mode_summary),
                checked = state.diagnosticMode,
                enabled = state.module.canEdit,
                onCheckedChange = actions.onDiagnosticChange,
            )

            DiagnosticsRow.ENDPOINTS -> NavRow(
                title = stringResource(R.string.settings_endpoints),
                summary = endpointsSummary(state),
                onClick = actions.onOpenEndpoints,
            )

            // Always here, so a dismissed overlap hint can still be looked up (and shown again).
            DiagnosticsRow.CONFLICTS -> {
                val count = state.overlaps?.modules?.size ?: 0
                NavRow(
                    title = stringResource(R.string.settings_conflicts),
                    summary = if (count > 0) {
                        pluralStringResource(R.plurals.settings_conflicts_count, count, count)
                    } else {
                        stringResource(R.string.settings_conflicts_none)
                    },
                    onClick = actions.onOpenConflicts,
                )
            }
        }
    }
}

private data class AboutEntry(@StringRes val title: Int, val value: String, val onClick: (() -> Unit)? = null)

private fun LazyListScope.aboutSection(
    state: UiState,
    repository: String,
    onCheckUpdate: () -> Unit,
    onOpenRepository: () -> Unit,
) {
    val rows = listOfNotNull(
        AboutEntry(R.string.app_name, state.versionName),
        state.framework?.let { AboutEntry(R.string.about_framework, it) },
        AboutEntry(R.string.about_repository, repository.removePrefix("https://"), onOpenRepository),
    )
    item(key = "about-title") { SectionHeader(stringResource(R.string.settings_about)) }
    groupedItems(rows, key = { "about/${it.title}" }) { row ->
        if (row.title == R.string.app_name) {
            VersionRow(row.value, onCheckUpdate)
        } else {
            ValueRow(stringResource(row.title), row.value, onClick = row.onClick)
        }
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

/** The version, with "check for updates" at its end: it opens the latest release page in the browser. */
@Composable
private fun VersionRow(version: String, onCheckUpdate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowMinHeight)
            .padding(start = Dimens.Gutter, end = Dimens.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TitleSummary(
            title = stringResource(R.string.app_name),
            summary = version,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = Dimens.RowVertical)
                .semantics(mergeDescendants = true) {},
        )
        TextButton(onClick = onCheckUpdate) { Text(stringResource(R.string.settings_check_update)) }
    }
}

/** Opens [url] in whatever app handles links; without one, says so instead of failing. */
private fun openLink(context: Context, url: String, unavailable: String) {
    val opened = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess
    if (!opened) runCatching { Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show() }
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
    HyperPrivacyTheme {
        SettingsScreen(
            state = sampleState(),
            appearance = Appearance(),
            onAppearanceChange = {},
            onBack = {},
            onOpenHealth = {},
            onExport = {},
            backup = BackupActions({}, {}),
            onDiagnosticChange = {},
            onSelfHideChange = {},
            onResetDefaults = {},
            onOpenEndpoints = {},
            onOpenConflicts = {},
            onOpenAllowlist = {},
            onRefreshObserved = {},
        )
    }
}
