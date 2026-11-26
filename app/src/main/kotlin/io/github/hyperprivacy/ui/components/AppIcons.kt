package io.github.hyperprivacy.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import io.github.hyperprivacy.core.rule.Category

private const val VIEWPORT = 24f
private const val STROKE = 1.8f

/** A 24dp outline icon drawn from stroke-only path data, matching Material Symbols' weight. */
private fun outline(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, VIEWPORT, VIEWPORT).apply {
        paths.forEach { data ->
            addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

/** The handful of glyphs Material's core icon set lacks. */
internal object AppIcons {
    val Apps: ImageVector by lazy {
        outline(
            "Apps",
            "M5 4 H9 A1 1 0 0 1 10 5 V9 A1 1 0 0 1 9 10 H5 A1 1 0 0 1 4 9 V5 A1 1 0 0 1 5 4 Z",
            "M15 4 H19 A1 1 0 0 1 20 5 V9 A1 1 0 0 1 19 10 H15 A1 1 0 0 1 14 9 V5 A1 1 0 0 1 15 4 Z",
            "M5 14 H9 A1 1 0 0 1 10 15 V19 A1 1 0 0 1 9 20 H5 A1 1 0 0 1 4 19 V15 A1 1 0 0 1 5 14 Z",
            "M15 14 H19 A1 1 0 0 1 20 15 V19 A1 1 0 0 1 19 20 H15 A1 1 0 0 1 14 19 V15 A1 1 0 0 1 15 14 Z",
        )
    }

    /** A chip: the system apps the built-in rules cover. */
    val System: ImageVector by lazy {
        outline(
            "System",
            "M8 6 H16 A2 2 0 0 1 18 8 V16 A2 2 0 0 1 16 18 H8 A2 2 0 0 1 6 16 V8 A2 2 0 0 1 8 6 Z",
            "M10 10 H14 V14 H10 Z",
            "M10 3 V6",
            "M14 3 V6",
            "M10 18 V21",
            "M14 18 V21",
            "M3 10 H6",
            "M3 14 H6",
            "M18 10 H21",
            "M18 14 H21",
        )
    }
    val Shield: ImageVector by lazy {
        outline(
            "Shield",
            "M12 3 L19 6 V11.5 C19 15.8 16 19.4 12 21 C8 19.4 5 15.8 5 11.5 V6 Z",
            "M9 12 L11.2 14.2 L15.2 10",
        )
    }
    val ShieldAlert: ImageVector by lazy {
        outline(
            "ShieldAlert",
            "M12 3 L19 6 V11.5 C19 15.8 16 19.4 12 21 C8 19.4 5 15.8 5 11.5 V6 Z",
            "M12 8.5 V12.5",
            "M12 15.6 V15.7",
        )
    }
    val ShieldPaused: ImageVector by lazy {
        outline(
            "ShieldPaused",
            "M12 3 L19 6 V11.5 C19 15.8 16 19.4 12 21 C8 19.4 5 15.8 5 11.5 V6 Z",
            "M10 9.5 V14.5",
            "M14 9.5 V14.5",
        )
    }
    val Analytics: ImageVector by lazy { outline("Analytics", "M6 19 V13", "M12 19 V5", "M18 19 V10") }
    val Diagnostics: ImageVector by lazy { outline("Diagnostics", "M3 12 H7 L9.5 6 L14.5 18 L17 12 H21") }
    val Ads: ImageVector by lazy {
        outline(
            "Ads",
            "M4 10 V14 H7 L14 18 V6 L7 10 Z",
            "M17.5 9.5 C18.7 10.7 18.7 13.3 17.5 14.5",
            "M7.5 14.5 L8.8 19",
        )
    }
    val DeviceId: ImageVector by lazy {
        outline(
            "DeviceId",
            "M8 3 H16 A2 2 0 0 1 18 5 V19 A2 2 0 0 1 16 21 H8 A2 2 0 0 1 6 19 V5 A2 2 0 0 1 8 3 Z",
            "M11 18 H13",
        )
    }

    /** A checklist: the app-list rules (distinct from the Apps tab's grid). */
    val AppList: ImageVector by lazy {
        outline(
            "AppList",
            "M4 6.5 L5.5 8 L8 5",
            "M11 6.5 H20",
            "M4 12 L5.5 13.5 L8 10.5",
            "M11 12 H20",
            "M4 17.5 L5.5 19 L8 16",
            "M11 17.5 H20",
        )
    }

    /** A circle with a slash: what was blocked. */
    val Block: ImageVector by lazy {
        outline(
            "Block",
            "M12 4 A8 8 0 1 0 12 20 A8 8 0 1 0 12 4 Z",
            "M6.3 6.3 L17.7 17.7",
        )
    }
    val PersonalData: ImageVector by lazy {
        outline(
            "PersonalData",
            "M12 11.5 A3.5 3.5 0 1 0 12 4.5 A3.5 3.5 0 1 0 12 11.5 Z",
            "M5 20 C5 16.7 8.1 14.8 12 14.8 C15.9 14.8 19 16.7 19 20",
        )
    }
    val Palette: ImageVector by lazy {
        outline(
            "Palette",
            "M12 3 A9 9 0 0 0 12 21 C13.2 21 13.8 20.2 13.4 19.2 C12.9 18 13.7 16.8 15 16.8 H17 " +
                "A4 4 0 0 0 21 12.8 C21 7.4 17 3 12 3 Z",
            "M7.5 12 V12.1",
            "M9.5 7.8 V7.9",
            "M14.5 7.8 V7.9",
        )
    }
    val Report: ImageVector by lazy {
        outline(
            "Report",
            "M7 3 H14 L19 8 V19 A2 2 0 0 1 17 21 H7 A2 2 0 0 1 5 19 V5 A2 2 0 0 1 7 3 Z",
            "M14 3 V8 H19",
            "M9 13 H15",
            "M9 17 H13",
        )
    }
}

internal fun Category.icon(): ImageVector = when (this) {
    Category.ANALYTICS -> AppIcons.Analytics
    Category.DIAGNOSTICS -> AppIcons.Diagnostics
    Category.ADS -> AppIcons.Ads
    Category.DEVICE_ID -> AppIcons.DeviceId
    Category.APP_LIST -> AppIcons.AppList
    Category.PERSONAL_DATA -> AppIcons.PersonalData
}
