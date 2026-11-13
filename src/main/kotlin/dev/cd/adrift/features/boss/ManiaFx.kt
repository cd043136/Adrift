package dev.cd.adrift.features.boss

import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.Colors
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** How the pattern's floor tint moves. */
internal enum class ManiaStyle(private val label: String) {
    STATIC("Static"),
    PULSE("Pulse"),
    HEARTBEAT("Heartbeat");

    override fun toString() = label
}

/**
 * Animation state of one frame of [ManiaHelper]'s pattern. Positions are column offsets from the pattern's centre,
 * continuous, so column (offX, offZ) spans offX - 0.5 to offX + 0.5.
 */
internal class ManiaFx {
    var style = ManiaStyle.PULSE
        private set
    /** Seconds, only for animation. */
    var time = 0.0
        private set
    /** Seconds until coal, NaN outside the terracotta and redstone steps. */
    private var untilCoal = Double.NaN
    private var coal = false
    private var lastTime = Double.NaN
    /** Heartbeats so far; the rate changes, so this is integrated rather than derived from [time]. */
    private var beats = 0.0

    val animated get() = style != ManiaStyle.STATIC

    fun begin(style: ManiaStyle, time: Double, untilCoal: Double?, coal: Boolean) {
        val dt = if (lastTime.isNaN()) 0.0 else (time - lastTime).coerceIn(0.0, MAX_FRAME)
        lastTime = time
        this.style = style
        this.time = time
        this.untilCoal = untilCoal ?: Double.NaN
        this.coal = coal
        beats += dt * heartRate()
    }

    /** Glow strength of the column, 0..1. The safe ring swings over the whole range, danger only around the middle. */
    fun crest(offX: Int, offZ: Int, safe: Boolean): Double = when (style) {
        ManiaStyle.STATIC -> STATIC_CREST
        ManiaStyle.PULSE -> {
            val wave = 0.5 + 0.5 * sin(distance(offX, offZ) * PULSE_WAVE_NUMBER - time * PULSE_SPEED)
            if (safe) wave.pow(1.75) else 0.4 + 0.35 * wave
        }
        ManiaStyle.HEARTBEAT -> {
            val beat = heartbeat(fraction(beats - distance(offX, offZ) * HEART_LAG))
            if (safe) beat else 0.35 + 0.5 * beat
        }
    }

    /** [base] lit to [crest]. */
    fun tint(base: Color, crest: Double, safe: Boolean): Color {
        if (!animated) return base
        val hot = base.mix(Colors.WHITE, if (safe) 0.78 else 0.45)
        val alpha = (base.alpha * (0.48 + 0.68 * crest)).roundToInt().coerceIn(0, 245)
        return Color((alpha shl 24) or base.mix(hot, crest * if (safe) 0.65 else 0.2).rgb)
    }

    /** Coal getting closer, 0 (a whole terracotta step away, or unknown) to 1 (now). */
    fun urgency(): Double = if (untilCoal.isNaN()) 0.0 else (1 - untilCoal / FULL_WARNING).coerceIn(0.0, 1.0)

    private fun heartRate(): Double = when {
        coal -> RESTING_RATE * 0.8
        untilCoal.isNaN() -> RESTING_RATE
        else -> RESTING_RATE + RACING_RATE * urgency().pow(2)
    }

    companion object {
        /** Outline glow strength while the floor doesn't move. */
        const val STATIC_CREST = 0.5
        /** The wave is about seven blocks long and passes a column roughly once a second. */
        private const val PULSE_WAVE_NUMBER = 0.88
        private const val PULSE_SPEED = 6.4
        private const val MAX_FRAME = 0.1
        /** Beats per second. */
        private const val RESTING_RATE = 1.1
        private const val RACING_RATE = 2.4
        /** Beats a column lags per block from the centre, so each thump ripples outwards. */
        private const val HEART_LAG = 0.025
        /** Terracotta and redstone together, the longest coal is ever away. */
        private const val FULL_WARNING = 2.25

        fun distance(x: Double, z: Double) = sqrt(x * x + z * z)
        fun distance(x: Int, z: Int) = sqrt((x * x + z * z).toDouble())
        fun fraction(x: Double) = x - floor(x)

        /** Lub-dub: a strong thump at the start of the beat and a softer one just after, 0..1. */
        private fun heartbeat(p: Double): Double {
            var v = exp(-p * 16)
            if (p >= 0.22) v += 0.6 * exp(-(p - 0.22) * 16)
            return v.coerceAtMost(1.0)
        }
    }
}
