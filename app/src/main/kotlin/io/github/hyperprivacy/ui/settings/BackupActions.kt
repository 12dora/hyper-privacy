package io.github.hyperprivacy.ui.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.config.BackupSummary
import io.github.hyperprivacy.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FILE_NAME = "hyperprivacy-config.json"
private val IMPORT_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

private fun createJson() = ActivityResultContracts.CreateDocument("application/json")

/** The two Settings actions for moving the configuration to a file and back. */
internal class BackupActions(val onExport: () -> Unit, val onImport: () -> Unit)

/**
 * Export and import through the system file picker (no storage permission: the user picks the
 * file). An import is read and checked first, then shown as a short summary and applied only after
 * the user confirms. Reading, the waiting file and the import itself belong to the view model
 * ([io.github.hyperprivacy.ui.ConfigTransfer]), so rotation or navigation does not lose them.
 */
@Composable
internal fun rememberBackupActions(viewModel: MainViewModel): BackupActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending by viewModel.config.pendingImport.collectAsStateWithLifecycle()
    val importing by viewModel.config.importing.collectAsStateWithLifecycle()
    val exporter = rememberLauncherForActivityResult(createJson()) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = writeConfig(context, uri, viewModel.config.export())
                toast(context, if (saved) R.string.backup_exported else R.string.backup_export_failed)
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.config.read(uri)
    }
    pending?.let { backup ->
        ImportDialog(
            summary = backup.summary,
            onConfirm = viewModel.config::confirm,
            onDismiss = viewModel.config::dismiss,
        )
    }
    if (importing) ImportingDialog()
    return remember(exporter, importer) {
        BackupActions(onExport = { exporter.launch(FILE_NAME) }, onImport = { importer.launch(IMPORT_TYPES) })
    }
}

private suspend fun writeConfig(context: Context, uri: Uri, text: String?): Boolean = withContext(Dispatchers.IO) {
    if (text == null) return@withContext false
    runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
    }.getOrDefault(false)
}

private fun toast(context: Context, message: Int) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

@Composable
private fun ImportDialog(summary: BackupSummary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    // One item per line, each a phrase in sentence case, so differently cased items never share a line.
    val lines = listOfNotNull(
        pluralStringResource(R.plurals.backup_summary_rules, summary.rules, summary.rules).takeIf { summary.rules > 0 },
        pluralStringResource(R.plurals.backup_summary_apps, summary.apps, summary.apps).takeIf { summary.apps > 0 },
        stringResource(R.string.backup_summary_applist).takeIf { summary.hasAppList },
        stringResource(R.string.backup_summary_appearance).takeIf { summary.hasAppearance },
    )
    val bullet = stringResource(R.string.list_bullet)
    val contents = lines.joinToString("\n") { bullet + it }.ifEmpty { stringResource(R.string.backup_summary_empty) }
    val ignored = if (summary.ignored > 0) {
        "\n" + pluralStringResource(R.plurals.backup_summary_ignored, summary.ignored, summary.ignored)
    } else {
        ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_import_title)) },
        text = { Text(contents + ignored + "\n\n" + stringResource(R.string.backup_import_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.backup_import_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Shown while an import runs; it cannot be dismissed and closes by itself, followed by a toast. */
@Composable
private fun ImportingDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.backup_import_title)) },
        text = { Text(stringResource(R.string.backup_importing)) },
        confirmButton = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    )
}
