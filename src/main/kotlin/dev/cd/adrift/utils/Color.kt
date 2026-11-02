package dev.cd.adrift.utils

/** An immutable `0xAARRGGBB` colour. */
@JvmInline
value class Color(val rgba: Int) {
    /** From `RRGGBBAA` hex, the format Odin used for colour settings. */
    constructor(hex: String) : this(parseHex(hex))

    val red get() = (rgba shr 16) and 0xFF
    val green get() = (rgba shr 8) and 0xFF
    val blue get() = rgba and 0xFF
    val alpha get() = (rgba ushr 24) and 0xFF

    val redFloat get() = red / 255f
    val greenFloat get() = green / 255f
    val blueFloat get() = blue / 255f
    val alphaFloat get() = alpha / 255f

    /** `0xRRGGBB`, without alpha. */
    val rgb get() = rgba and 0xFFFFFF

    fun withAlpha(alpha: Float) = Color(((alpha.coerceIn(0f, 1f) * 255 + 0.5f).toInt() shl 24) or rgb)

    fun multiplyAlpha(by: Float) = withAlpha(alphaFloat * by)

    /** `RRGGBBAA`, or `RRGGBB` without [alpha]. */
    fun hex(alpha: Boolean = true) = if (alpha) "%06X%02X".format(rgb, this.alpha) else "%06X".format(rgb)

    companion object {
        private fun parseHex(hex: String): Int {
            val digits = hex.removePrefix("#")
            val rgb = digits.take(6).toInt(16)
            val alpha = if (digits.length >= 8) digits.substring(6, 8).toInt(16) else 0xFF
            return (alpha shl 24) or rgb
        }

        fun fromHexOrNull(hex: String): Color? = runCatching { Color(hex) }.getOrNull()
    }
}

object Colors {
    val WHITE = Color(0xFFFFFFFF.toInt())
    val MINECRAFT_GREEN = Color(0xFF55FF55.toInt())
    val MINECRAFT_RED = Color(0xFFFF5555.toInt())
}
