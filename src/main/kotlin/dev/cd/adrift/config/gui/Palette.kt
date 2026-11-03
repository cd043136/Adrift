package dev.cd.adrift.config.gui

import dev.cd.adrift.config.Config
import foo.starred.cascade.graphics.geometry.CascadeGeometricColor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Colors of the config screen. Fixed ones are constants; the rest are mixed from [Config.Theme] by [update],
 * the same way the mockup derives its CSS variables from one accent color.
 */
object Palette {
    const val TEXT = 0xFFF4F2F8.toInt()
    const val MUTED = 0xFFA9A5B8.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val LINE = 0x1AFFFFFF
    const val TRACK = 0xFF6D6A7A.toInt()
    const val SWITCH_OFF = 0xFF4A475A.toInt()
    const val DESCRIPTION = 0xFFCFCBDB.toInt()
    const val POPUP = 0xFF16151F.toInt()
    const val SCROLL_THUMB = 0xFF0B0A12.toInt()

    var accent = 0
        private set
    var accentDeep = 0
        private set
    /** Hovered row: the accent darkened, darker still for light accents so white text stays readable. */
    var accentRow = 0
        private set
    var accentHi = 0
        private set
    /** Rules under the tabs and above the description. */
    var rule = 0
        private set
    var panel = 0
        private set
    /** ClickGUI panel headers: opaque whatever the menu opacity, with a hint of the accent. */
    var header = 0
        private set
    var headerHover = 0
        private set
    /** Text on an accent fill: white, or near-black when the accent is too light for white to read. */
    var onAccent = 0
        private set

    init {
        update()
    }

    fun update() {
        val (h, s, v) = hsv(Config.Theme.accent)
        // Keeps it visible on the dark panel
        accent = rgb(h, s, max(v, 0.4f)) or 0xFF000000.toInt()
        val lum = (0.299f * red(accent) + 0.587f * green(accent) + 0.114f * blue(accent)) / 255f
        val rowMix = (0.74f * if (lum > 0.55f) 0.55f / lum else 1f).coerceIn(0.38f, 0.74f)
        accentDeep = mix(accent, 0xFF000000.toInt(), 0.65f)
        accentRow = mix(accent, 0xFF000000.toInt(), rowMix).withAlpha(0.88f)
        accentHi = mix(accent, WHITE, 0.7f)
        rule = mix(accent, LINE, 0.36f)
        panel = 0x0F0E16.withAlpha(Config.Theme.opacity)
        header = mix(accent, 0xFF16151F.toInt(), 0.1f)
        headerHover = mix(accent, 0xFF1F1E2A.toInt(), 0.14f)
        onAccent = if (lum > 0.6f) 0xFF0F0E16.toInt() else WHITE
    }

    fun color(argb: Int) = CascadeGeometricColor(argb)

    /** [a] with [amount] of it and the rest [b], like CSS `color-mix(in srgb, a amount, b)` (alpha premultiplied). */
    fun mix(a: Int, b: Int, amount: Float): Int {
        val alphaA = alpha(a) / 255f * amount
        val alphaB = alpha(b) / 255f * (1f - amount)
        val alpha = alphaA + alphaB
        if (alpha <= 0f) return 0
        fun channel(ca: Int, cb: Int) = ((ca * alphaA + cb * alphaB) / alpha).roundToInt().coerceIn(0, 255)
        return argb(alpha, channel(red(a), red(b)), channel(green(a), green(b)), channel(blue(a), blue(b)))
    }

    /** Straight interpolation from [a] to [b], for animating between two colors. */
    fun lerp(a: Int, b: Int, t: Float): Int {
        fun channel(ca: Int, cb: Int) = (ca + (cb - ca) * t).roundToInt().coerceIn(0, 255)
        return (channel(alpha(a), alpha(b)) shl 24) or (channel(red(a), red(b)) shl 16) or
            (channel(green(a), green(b)) shl 8) or channel(blue(a), blue(b))
    }

    /** Multiplies the alpha. */
    fun Int.fade(by: Float) = (((this ushr 24) * by.coerceIn(0f, 1f)).roundToInt() shl 24) or (this and 0xFFFFFF)

    /** Replaces the alpha. */
    fun Int.withAlpha(alpha: Float) = ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (this and 0xFFFFFF)

    /** Hue (0-360), saturation and value (0-1) of an `0xRRGGBB` color. */
    fun hsv(rgb: Int): Triple<Float, Float, Float> {
        val r = red(rgb) / 255f
        val g = green(rgb) / 255f
        val b = blue(rgb) / 255f
        val max = max(r, max(g, b))
        val d = max - min(r, min(g, b))
        var h = 0f
        if (d > 0f) {
            h = when (max) {
                r -> ((g - b) / d) % 6f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
            if (h < 0f) h += 360f
        }
        return Triple(h, if (max > 0f) d / max else 0f, max)
    }

    /** `0xRRGGBB` from hue (0-360), saturation and value (0-1). */
    fun rgb(h: Float, s: Float, v: Float): Int {
        fun f(n: Int): Int {
            val k = (n + h / 60f) % 6f
            return ((v - v * s * max(0f, min(k, min(4f - k, 1f)))) * 255).roundToInt()
        }
        return (f(5) shl 16) or (f(3) shl 8) or f(1)
    }

    fun hex(rgb: Int) = "%06X".format(rgb and 0xFFFFFF)

    private fun alpha(c: Int) = c ushr 24
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
    private fun argb(alpha: Float, r: Int, g: Int, b: Int) =
        ((alpha * 255).roundToInt().coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b
}
