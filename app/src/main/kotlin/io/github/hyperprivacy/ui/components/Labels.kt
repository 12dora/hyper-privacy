package io.github.hyperprivacy.ui.components

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import io.github.hyperprivacy.R
import io.github.hyperprivacy.core.health.Health
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.RuleKind
import java.text.NumberFormat

@StringRes
internal fun Category.labelRes(): Int = when (this) {
    Category.ANALYTICS -> R.string.category_analytics
    Category.DIAGNOSTICS -> R.string.category_diagnostics
    Category.ADS -> R.string.category_ads
    Category.DEVICE_ID -> R.string.category_device_id
    Category.APP_LIST -> R.string.category_app_list
    Category.PERSONAL_DATA -> R.string.category_personal_data
}

/** How a rule blocks, in plain words. */
@StringRes
internal fun RuleKind.titleRes(): Int = when (this) {
    RuleKind.FIREWALL -> R.string.kind_firewall
    RuleKind.DOMAIN -> R.string.kind_domain
    RuleKind.METHOD -> R.string.kind_method
    RuleKind.URL -> R.string.kind_url
    RuleKind.IDENTIFIER -> R.string.kind_identifier
}

@StringRes
internal fun RuleKind.descriptionRes(): Int = when (this) {
    RuleKind.FIREWALL -> R.string.kind_firewall_desc
    RuleKind.DOMAIN -> R.string.kind_domain_desc
    RuleKind.METHOD -> R.string.kind_method_desc
    RuleKind.URL -> R.string.kind_url_desc
    RuleKind.IDENTIFIER -> R.string.kind_identifier_desc
}

/** A plural string whose single argument is the locale-formatted [count]. */
@Composable
internal fun countString(@PluralsRes id: Int, count: Long): String {
    val quantity = count.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    return pluralStringResource(id, quantity, NumberFormat.getIntegerInstance().format(count))
}

/** Plural for small counts such as app counts, formatted by the resource itself. */
@Composable
internal fun appCountString(count: Int): String = pluralStringResource(R.plurals.meta_apps, count, count)

@StringRes
internal fun Health.labelRes(): Int = when (this) {
    Health.BROKEN -> R.string.health_broken
    Health.NEEDS_REVIEW -> R.string.health_review
    Health.IDLE -> R.string.health_idle
    Health.OK -> R.string.health_ok
    Health.NOT_RUN -> R.string.health_not_run
    Health.NOT_APPLICABLE -> R.string.health_not_applicable
    Health.NOT_INSTALLED -> R.string.health_not_installed
}

/** One-sentence explanation for the states that need attention; `null` for the others. */
@StringRes
internal fun Health.descriptionRes(): Int? = when (this) {
    Health.BROKEN -> R.string.health_broken_desc
    Health.NEEDS_REVIEW -> R.string.health_review_desc
    Health.IDLE -> R.string.health_idle_desc
    else -> null
}

/** Error red only for a rule that stopped working; the softer states use the tertiary accent. */
@Composable
internal fun Health.tint(): Color = if (this == Health.BROKEN) {
    MaterialTheme.colorScheme.error
} else {
    MaterialTheme.colorScheme.tertiary
}
