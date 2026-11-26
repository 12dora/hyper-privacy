package io.github.hyperprivacy.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyperprivacy.R
import io.github.hyperprivacy.ui.Dimens
import io.github.hyperprivacy.ui.HyperPrivacyTheme
import io.github.hyperprivacy.ui.ScopeUi
import io.github.hyperprivacy.ui.components.AppIcons
import io.github.hyperprivacy.ui.components.IconBadge

@Composable
private fun scopeTitle(scope: ScopeUi): String = if (scope.missingApps > 0) {
    pluralStringResource(R.plurals.scope_missing_apps, scope.missingApps, scope.missingApps)
} else {
    stringResource(R.string.scope_missing_system)
}

@Composable
private fun scopeSummary(scope: ScopeUi): String = stringResource(
    when {
        scope.requesting -> R.string.scope_requesting
        scope.systemMissing && scope.missingApps > 0 -> R.string.scope_missing_system_hint
        else -> R.string.scope_missing_hint
    },
)

/**
 * Installed catalog apps the module is not injected into (LSPosed never adds apps installed later),
 * with an explicit action: the request is only ever sent on a tap.
 */
@Composable
internal fun ScopeRow(scope: ScopeUi, onEnable: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val actionDescription = stringResource(R.string.scope_enable_description)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.TallRowMinHeight)
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowVertical),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(AppIcons.ShieldAlert, colors.tertiaryContainer, colors.onTertiaryContainer)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(
                text = scopeTitle(scope),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = scopeSummary(scope),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledTonalButton(
            onClick = onEnable,
            enabled = !scope.requesting,
            modifier = Modifier.semantics { contentDescription = actionDescription },
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Kept for layout so the button does not change width while the spinner shows.
                Text(stringResource(R.string.scope_enable), Modifier.alpha(if (scope.requesting) 0f else 1f))
                if (scope.requesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.StatusIcon),
                        strokeWidth = Dimens.RowGap,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ScopeRowPreview() {
    HyperPrivacyTheme {
        Column {
            ScopeRow(ScopeUi(missingApps = 2), onEnable = {})
            ScopeRow(ScopeUi(missingApps = 1, systemMissing = true, requesting = true), onEnable = {})
        }
    }
}
