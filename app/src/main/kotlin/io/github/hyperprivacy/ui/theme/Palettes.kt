package io.github.hyperprivacy.ui.theme

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.github.hyperprivacy.R
import kotlin.math.PI

private const val DEGREES = 180.0
private const val TERTIARY_SHIFT = 60.0
private const val ERROR_HUE = 29.0
private const val ERROR_CHROMA = 0.17

/**
 * A seed for a full M3 scheme, in OKLCh chroma units (≈0.37 is the most vivid sRGB red).
 * The defaults reproduce Material's "tonal spot" proportions: a calm primary, quieter accents,
 * nearly grey neutrals with a hint of the hue.
 */
private class Seed(
    val argb: Long,
    val primaryChroma: Double = 0.11,
    val secondaryChroma: Double = 0.035,
    val tertiaryChroma: Double = 0.06,
    val neutralChroma: Double = 0.007,
    val neutralVariantChroma: Double = 0.014,
)

/**
 * The app's color themes. [DYNAMIC] follows the wallpaper; the rest are curated seeds, whose
 * hand-tuned values are design constants rather than magic numbers.
 */
@Suppress("MagicNumber")
internal enum class ThemePalette(@param:StringRes val label: Int, private val seed: Seed?) {
    DYNAMIC(R.string.palette_dynamic, null),
    SEA(R.string.palette_sea, Seed(0xFF3A6EA5)),
    JADE(R.string.palette_jade, Seed(0xFF2C8C7C, primaryChroma = 0.09)),
    AMBER(R.string.palette_amber, Seed(0xFFC4841D, primaryChroma = 0.12, tertiaryChroma = 0.05)),
    SAKURA(R.string.palette_sakura, Seed(0xFFC45C82, primaryChroma = 0.12)),
    GRAPHITE(
        R.string.palette_graphite,
        Seed(
            0xFF5C6670,
            primaryChroma = 0.02,
            secondaryChroma = 0.012,
            tertiaryChroma = 0.03,
            neutralChroma = 0.003,
            neutralVariantChroma = 0.008,
        ),
    ),
    ;

    /** Light and dark schemes; curated ones are computed once and cached. */
    fun scheme(context: Context, dark: Boolean): ColorScheme {
        val seed = seed ?: return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        return SchemeCache.get(this, dark) { buildScheme(seed, dark) }
    }
}

private object SchemeCache {
    private val schemes = HashMap<Pair<ThemePalette, Boolean>, ColorScheme>()

    @Synchronized
    fun get(palette: ThemePalette, dark: Boolean, build: () -> ColorScheme): ColorScheme =
        schemes.getOrPut(palette to dark, build)
}

private fun Double.degrees(): Double = this * PI / DEGREES

private fun buildScheme(seed: Seed, dark: Boolean): ColorScheme {
    val hue = TonalPalette.hueOf(seed.argb)
    val p = TonalPalette(hue, seed.primaryChroma)
    val s = TonalPalette(hue, seed.secondaryChroma)
    val t = TonalPalette(hue + TERTIARY_SHIFT.degrees(), seed.tertiaryChroma)
    val n = TonalPalette(hue, seed.neutralChroma)
    val nv = TonalPalette(hue, seed.neutralVariantChroma)
    val e = TonalPalette(ERROR_HUE.degrees(), ERROR_CHROMA)
    return if (dark) darkScheme(Tones(p, s, t, n, nv, e)) else lightScheme(Tones(p, s, t, n, nv, e))
}

private class Tones(
    val p: TonalPalette,
    val s: TonalPalette,
    val t: TonalPalette,
    val n: TonalPalette,
    val nv: TonalPalette,
    val e: TonalPalette,
)

// Role → tone tables from the Material 3 color spec.
@Suppress("MagicNumber")
private fun lightScheme(x: Tones): ColorScheme = with(x) {
    lightColorScheme(
        primary = p.tone(40), onPrimary = p.tone(100),
        primaryContainer = p.tone(90), onPrimaryContainer = p.tone(10),
        inversePrimary = p.tone(80),
        secondary = s.tone(40), onSecondary = s.tone(100),
        secondaryContainer = s.tone(90), onSecondaryContainer = s.tone(10),
        tertiary = t.tone(40), onTertiary = t.tone(100),
        tertiaryContainer = t.tone(90), onTertiaryContainer = t.tone(10),
        error = e.tone(40), onError = e.tone(100),
        errorContainer = e.tone(90), onErrorContainer = e.tone(10),
        background = n.tone(98), onBackground = n.tone(10),
        surface = n.tone(98), onSurface = n.tone(10),
        surfaceVariant = nv.tone(90), onSurfaceVariant = nv.tone(30),
        surfaceTint = p.tone(40),
        inverseSurface = n.tone(20), inverseOnSurface = n.tone(95),
        outline = nv.tone(50), outlineVariant = nv.tone(80), scrim = Color.Black,
        surfaceBright = n.tone(98), surfaceDim = n.tone(87),
        surfaceContainerLowest = n.tone(100), surfaceContainerLow = n.tone(96),
        surfaceContainer = n.tone(94), surfaceContainerHigh = n.tone(92),
        surfaceContainerHighest = n.tone(90),
    )
}

@Suppress("MagicNumber") // Material 3 tonal-palette steps (0–100), not tunable constants.
private fun darkScheme(x: Tones): ColorScheme = with(x) {
    darkColorScheme(
        primary = p.tone(80), onPrimary = p.tone(20),
        primaryContainer = p.tone(30), onPrimaryContainer = p.tone(90),
        inversePrimary = p.tone(40),
        secondary = s.tone(80), onSecondary = s.tone(20),
        secondaryContainer = s.tone(30), onSecondaryContainer = s.tone(90),
        tertiary = t.tone(80), onTertiary = t.tone(20),
        tertiaryContainer = t.tone(30), onTertiaryContainer = t.tone(90),
        error = e.tone(80), onError = e.tone(20),
        errorContainer = e.tone(30), onErrorContainer = e.tone(90),
        background = n.tone(6), onBackground = n.tone(90),
        surface = n.tone(6), onSurface = n.tone(90),
        surfaceVariant = nv.tone(30), onSurfaceVariant = nv.tone(80),
        surfaceTint = p.tone(80),
        inverseSurface = n.tone(90), inverseOnSurface = n.tone(20),
        outline = nv.tone(60), outlineVariant = nv.tone(30), scrim = Color.Black,
        surfaceBright = n.tone(24), surfaceDim = n.tone(6),
        surfaceContainerLowest = n.tone(4), surfaceContainerLow = n.tone(10),
        surfaceContainer = n.tone(12), surfaceContainerHigh = n.tone(17),
        surfaceContainerHighest = n.tone(22),
    )
}

/** Pure-black variant for OLED screens: the page goes black, cards keep a faint lift. */
@Suppress("MagicNumber")
internal fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = surfaceContainerLowest,
    surfaceContainer = surfaceContainerLow,
    surfaceContainerHigh = surfaceContainer,
    surfaceContainerHighest = surfaceContainerHigh,
)
