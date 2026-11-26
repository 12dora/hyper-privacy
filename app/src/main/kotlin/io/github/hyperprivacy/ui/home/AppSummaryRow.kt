package io.github.hyperprivacy.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.AppSummary
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.components.AppIcon
import io.github.hyperprivacy.ui.components.NavRow

/** An app in the by-app list: icon, name, "3 rules" (plus what is off or needs a look), chevron. */
@Composable
internal fun AppSummaryRow(app: AppSummary, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val separator = stringResource(R.string.common_separator)
    val count = pluralStringResource(R.plurals.app_rule_count, app.ruleCount, app.ruleCount)
    val off = app.ruleCount - app.enabledCount
    val summary = when {
        app.attentionCount > 0 ->
            pluralStringResource(R.plurals.app_attention, app.attentionCount, app.attentionCount)

        off > 0 -> count + separator + pluralStringResource(R.plurals.app_rules_off, off, off)

        else -> count
    }
    NavRow(
        title = app.label,
        summary = summary,
        summaryColor = if (app.attentionCount > 0) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
        onClick = onOpen,
        modifier = modifier,
        leading = { AppIcon(app.packageName, Dimens.ListIcon) },
    )
}
