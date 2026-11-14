package dev.cd.adrift.features.boss

import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.Colors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** How the pattern's floor looks. */
internal enum class ManiaStyle(private val label: String) {
    STATIC("Static"),
    DISCO("Disco");

    override fun toString() = label
}

/**
 * Animation state of one frame of [ManiaHelper]'s pattern. Positions are column offsets from the pattern's centre,
 * continuous, so column (offX, offZ) spans offX - 0.5 to offX + 0.5.
 */
internal class ManiaFx {
    var style = ManiaStyle.STATIC
        private set
    /** Seconds, only for animation. */
    var time = 0.0
        private set
    private var session: ManiaSession? = null
    private var stepKey = -1
    private var coalTime = Double.NEGATIVE_INFINITY
    val disco = ManiaDisco()

    // Light being summed for one point, premultiplied by intensity.
    private var lightR = 0.0
    private var lightG = 0.0
    private var lightB = 0.0

    /** Waves ripple out from the centre; works on its own or over disco. */
    var pulse = false

    val animated get() = pulse || style == ManiaStyle.DISCO

    /** A shimmering curtain of light rises off the safe ring's outline. */
    var curtain = false

    /** Grid cells per block side of [ManiaLight], 0 when nothing is lit. */
    var lightDetail = 0
        private set

    /** Whether anything draws in the light layer. */
    val lit get() = lightDetail > 0

    fun begin(style: ManiaStyle, time: Double, session: ManiaSession) {
        this.style = style
        this.time = time
        trackCoal(session)
        if (style == ManiaStyle.DISCO) disco.begin(time, time - coalTime)
        lightDetail = if (style == ManiaStyle.DISCO) 4 else 0
    }

    /** Glow strength of the column, 0..1. The safe ring swings over the whole range, danger only around the middle. */
    fun crest(offX: Int, offZ: Int, safe: Boolean): Double {
        val wave = if (pulse) pulseCrest(offX, offZ, safe) else 0.0
        return when (style) {
            ManiaStyle.STATIC -> if (pulse) wave else STATIC_CREST
            ManiaStyle.DISCO -> max(disco.crest(offX, offZ, safe), wave)
        }
    }

    private fun pulseCrest(offX: Int, offZ: Int, safe: Boolean): Double {
        val wave = 0.5 + 0.5 * sin(distance(offX, offZ) * PULSE_WAVE_NUMBER - time * PULSE_SPEED)
        return if (safe) wave.pow(1.75) else 0.4 + 0.35 * wave
    }

    /** Tint of a column showing [type] in [base] colour, lit to [crest]. */
    fun tint(base: Color, type: ManiaBlock, crest: Double): Color {
        if (!animated) return base
        val safe = type == ManiaBlock.GREEN
        val hot = base.mix(Colors.WHITE, if (safe) 0.78 else 0.45)
        val alpha = (base.alpha * (0.48 + 0.68 * crest)).roundToInt().coerceIn(0, 245)
        return Color((alpha shl 24) or base.mix(hot, crest * if (safe) 0.65 else 0.2).rgb)
    }

    /**
     * Light added on top of the tint at ([u], [v]). `0xAARRGGBB` for an additive blend, so colour scaled by alpha;
     * 0 for none.
     */
    fun light(u: Double, v: Double): Int {
        lightR = 0.0
        lightG = 0.0
        lightB = 0.0
        if (style == ManiaStyle.DISCO) disco.light(u, v, this)
        return packLight()
    }

    /** Notes when the session moves on to coal, for the disco strobe. */
    private fun trackCoal(session: ManiaSession) {
        val key = (session.cycleIndex shl 8) or (session.phase.ordinal shl 4) or session.greenRing
        val changed = session === this.session && key != stepKey
        this.session = session
        stepKey = key
        if (changed && session.phase == ManiaSession.Phase.COAL) coalTime = time
    }

    /**
     * Strength, 0..1, of the light curtain at ([u], [v]) on the outline, [height] of the way up. Brightest at the
     * floor, with bands rising through it and a shimmer travelling round the ring.
     */
    fun curtainGlow(u: Double, v: Double, height: Double): Double {
        val angle = angleOf(u, v)
        val shimmer = 0.55 + 0.45 * sin(angle * 12 + time * 1.7) * sin(angle * 7 - time * 1.1)
        val rise = 0.6 + 0.4 * sin(height * 9 - time * 5 + angle * 5)
        return CURTAIN_GLOW * (1 - height).pow(1.6) * shimmer * rise
    }

    fun add(colour: Color, amount: Double) {
        if (amount <= 0.0) return
        lightR += colour.redFloat * amount
        lightG += colour.greenFloat * amount
        lightB += colour.blueFloat * amount
    }

    /** The summed light as a colour with its strength in alpha, so an additive blend adds exactly the sum. */
    private fun packLight(): Int {
        val strength = max(lightR, max(lightG, lightB))
        if (strength < 1.0 / 255) return 0
        val a = (strength.coerceAtMost(1.0) * 255).roundToInt()
        val r = (lightR / strength * 255).roundToInt()
        val g = (lightG / strength * 255).roundToInt()
        val b = (lightB / strength * 255).roundToInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        /** Outline glow strength while the floor doesn't move. */
        const val STATIC_CREST = 0.5
        /** The wave is about seven blocks long and passes a column roughly once a second. */
        private const val PULSE_WAVE_NUMBER = 0.88
        private const val PULSE_SPEED = 6.4
        const val TAU = 2 * PI
        private const val CURTAIN_GLOW = 0.55

        fun distance(x: Int, z: Int) = sqrt((x * x + z * z).toDouble())
        fun fraction(x: Double) = x - floor(x)

        fun smoothstep(from: Double, to: Double, x: Double): Double {
            val t = ((x - from) / (to - from)).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }

        /** Repeatable pseudo-random 0..1 for a lattice point. */
        fun hash(x: Int, z: Int, seed: Int): Double {
            var h = x * 374761393 + z * 668265263 + seed * 1442695041
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xFFFFFF) / 16777216.0
        }

        /** Radians clockwise from north (-z) seen from above, 0 until 2π. */
        fun angleOf(u: Double, v: Double): Double {
            val a = atan2(u, -v)
            return if (a < 0) a + TAU else a
        }
    }
}
