// Color-space matrices are published constants; naming each coefficient would not help.
@file:Suppress("MagicNumber")

package io.github.hyperprivacy.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * A Material 3 style tonal palette: one hue and chroma, rendered at any tone 0–100.
 *
 * Tone is CIE L*, exactly as in Material's HCT, so the M3 role→tone table keeps its contrast
 * guarantees. Hue and chroma live in OKLCh, which keeps hues stable across tones without pulling
 * in the full CAM16 machinery. Colors outside sRGB are brought in by lowering chroma, never tone.
 */
internal class TonalPalette(private val hue: Double, private val chroma: Double) {
    private val cache = HashMap<Int, Color>()

    fun tone(tone: Int): Color = cache.getOrPut(tone) { solve(tone.toDouble()) }

    private fun solve(tone: Double): Color {
        if (tone <= 0.0) return Color.Black
        if (tone >= MAX_TONE) return Color.White
        val targetY = Oklab.yFromLstar(tone)
        val full = Oklab.atLuminance(targetY, chroma, hue)
        if (full.inGamut) return full.color
        // Bisect on chroma: the largest chroma that still fits sRGB at this luminance.
        var low = 0.0
        var high = chroma
        var best = Oklab.atLuminance(targetY, 0.0, hue)
        repeat(BISECT_STEPS) {
            val mid = (low + high) / 2
            val candidate = Oklab.atLuminance(targetY, mid, hue)
            if (candidate.inGamut) {
                best = candidate
                low = mid
            } else {
                high = mid
            }
        }
        return best.color
    }

    companion object {
        private const val MAX_TONE = 100.0
        private const val BISECT_STEPS = 18

        /** OKLCh hue (radians) of an sRGB color. */
        fun hueOf(argb: Long): Double = Oklab.hueOf(argb)
    }
}

/** OKLab ↔ linear sRGB, plus the luminance/L* relations the palette needs. */
private object Oklab {
    private const val GAMUT_EPSILON = 1e-4
    private const val LUMINANCE_STEPS = 32
    private const val KAPPA = 903.2962962
    private const val EPSILON = 0.0088564517
    private const val L_OFFSET = 16.0
    private const val L_SCALE = 116.0
    private const val CHANNEL_MAX = 255.0
    private const val GAMMA_THRESHOLD = 0.0031308
    private const val GAMMA_LINEAR = 12.92
    private const val GAMMA_EXP = 2.4
    private const val GAMMA_A = 1.055
    private const val GAMMA_B = 0.055
    private const val DECODE_THRESHOLD = 0.04045

    class Solved(val r: Double, val g: Double, val b: Double) {
        val inGamut: Boolean get() = listOf(r, g, b).all { it in -GAMUT_EPSILON..1 + GAMUT_EPSILON }
        val color: Color get() = Color(encode(r), encode(g), encode(b))
    }

    fun yFromLstar(lstar: Double): Double {
        val ft = (lstar + L_OFFSET) / L_SCALE
        val ft3 = ft * ft * ft
        return if (ft3 > EPSILON) ft3 else lstar / KAPPA
    }

    /** The OKLCh color with this chroma and hue whose relative luminance is [targetY]. */
    fun atLuminance(targetY: Double, chroma: Double, hue: Double): Solved {
        val a = chroma * cos(hue)
        val b = chroma * sin(hue)
        var low = 0.0
        var high = 1.0
        repeat(LUMINANCE_STEPS) {
            val mid = (low + high) / 2
            val rgb = toLinear(mid, a, b)
            val y = LUM_R * rgb[0] + LUM_G * rgb[1] + LUM_B * rgb[2]
            if (y < targetY) low = mid else high = mid
        }
        val rgb = toLinear((low + high) / 2, a, b)
        return Solved(rgb[0], rgb[1], rgb[2])
    }

    fun hueOf(argb: Long): Double {
        val r = decode(((argb shr 16) and 0xFF).toDouble() / CHANNEL_MAX)
        val g = decode(((argb shr 8) and 0xFF).toDouble() / CHANNEL_MAX)
        val bl = decode((argb and 0xFF).toDouble() / CHANNEL_MAX)
        val l = cbrt(0.4122214708 * r + 0.5363449945 * g + 0.0514345580 * bl)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * bl)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * bl)
        val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val b = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        return if (hypot(a, b) < GAMUT_EPSILON) 0.0 else (atan2(b, a) + 2 * PI) % (2 * PI)
    }

    private fun toLinear(lightness: Double, a: Double, b: Double): DoubleArray {
        val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        return doubleArrayOf(
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
        )
    }

    private fun encode(linear: Double): Float {
        val c = linear.coerceIn(0.0, 1.0)
        val v = if (c <= GAMMA_THRESHOLD) c * GAMMA_LINEAR else GAMMA_A * c.pow(1 / GAMMA_EXP) - GAMMA_B
        return v.toFloat().coerceIn(0f, 1f)
    }

    private fun decode(v: Double): Double =
        if (abs(v) <= DECODE_THRESHOLD) v / GAMMA_LINEAR else ((v + GAMMA_B) / GAMMA_A).pow(GAMMA_EXP)

    private const val LUM_R = 0.2126
    private const val LUM_G = 0.7152
    private const val LUM_B = 0.0722
}
