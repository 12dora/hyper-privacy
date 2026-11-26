package io.github.hyperprivacy.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.hyperprivacy.R
import kotlinx.coroutines.launch

/**
 * The "export diagnostic report" action: builds the report and hands it to the share sheet. When
 * the report would list user-installed apps, the user is asked first; leaving them out is the
 * default, so nothing about those apps is shared without an explicit choice for this export.
 */
@Composable
internal fun rememberReportExport(viewModel: MainViewModel): () -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var asking by remember { mutableStateOf(false) }
    val run: (Boolean?) -> Unit = { includeUserApps ->
        scope.launch {
            when (val outcome = viewModel.diagnosticReport(includeUserApps)) {
                is ReportOutcome.Ready -> share(context, outcome.text)
                ReportOutcome.AskUserApps -> asking = true
                ReportOutcome.Failed -> toast(context, R.string.health_export_failed)
            }
        }
    }
    if (asking) {
        ExportUserAppsDialog(
            onLeaveOut = {
                asking = false
                run(false)
            },
            onInclude = {
                asking = false
                run(true)
            },
            onDismiss = { asking = false },
        )
    }
    return { run(null) }
}

/** Asks whether this export may list user-installed apps; leaving them out is the default action. */
@Composable
private fun ExportUserAppsDialog(onLeaveOut: () -> Unit, onInclude: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.health_export_apps_title)) },
        text = { Text(stringResource(R.string.health_export_apps_body)) },
        confirmButton = {
            TextButton(onClick = onLeaveOut) { Text(stringResource(R.string.health_export_apps_omit)) }
        },
        dismissButton = {
            TextButton(onClick = onInclude) { Text(stringResource(R.string.health_export_apps_include)) }
        },
    )
}

private fun share(context: Context, report: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.health_report_subject))
        putExtra(Intent.EXTRA_TEXT, report)
    }
    runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.health_export))) }
        .onFailure { toast(context, R.string.health_export_failed) }
}

private fun toast(context: Context, message: Int) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
