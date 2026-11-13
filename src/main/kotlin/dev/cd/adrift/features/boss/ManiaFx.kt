package dev.cd.adrift.features.boss

import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.Colors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** How the pattern's floor tint moves. */
internal enum class ManiaStyle(private val label: String) {
    STATIC("Static"),
    PULSE("Pulse"),
    HEARTBEAT("Heartbeat"),
    RADAR("Radar");

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
    /** Radar hand, radians clockwise from north. */
    private var sweep = 0.0
    /** Where the radar hand will be when coal lands, NaN when unknown. */
    private var coalAngle = Double.NaN
    /** Shockwaves still spreading: start time and colour, oldest replaced first. */
    private val waveStart = DoubleArray(MAX_WAVES) { Double.NEGATIVE_INFINITY }
    private val waveColour = IntArray(MAX_WAVES)
    private var nextWave = 0
    private var waveSession: ManiaSession? = null
    private var waveKey = -1
    /** Ripples from steps: centre, start time and strength, oldest replaced first. */
    private val rippleU = DoubleArray(MAX_RIPPLES)
    private val rippleV = DoubleArray(MAX_RIPPLES)
    private val rippleStart = DoubleArray(MAX_RIPPLES) { Double.NEGATIVE_INFINITY }
    private val rippleSize = DoubleArray(MAX_RIPPLES)
    private var nextRipple = 0
    private var stepU = Double.NaN
    private var stepV = Double.NaN
    private var wasOnGround = true
    private var playerU = 0.0
    private var playerV = 0.0

    // Light being summed for one point, premultiplied by intensity.
    private var lightR = 0.0
    private var lightG = 0.0
    private var lightB = 0.0

    val animated get() = style != ManiaStyle.STATIC

    /** Gives each block its own texture: embers on redstone, smoke over coal, caustics on the safe ring. */
    var materials = false

    /** Twinkling stars on the floor, more of them as coal gets closer. */
    var glitter = false

    /** A ring of light bursts out from the centre whenever the pattern changes. */
    var shockwaves = false

    /** Rings spread over the floor from your steps, like walking on water. */
    var ripples = false

    /** The floor near you is brighter and lit by a soft pool of light; far away it fades back. */
    var lamp = false

    /** Tint colours by block ordinal. */
    var colours: Array<Color> = emptyArray()

    /** Grid cells per block side of [ManiaLight], 0 when nothing is lit. */
    var lightDetail = 0
        private set

    /** Whether anything draws in the light layer. */
    val lit get() = lightDetail > 0 || glitter

    // The star [sparkle] found, in block coordinates within its column.
    var sparkleX = 0.0
        private set
    var sparkleZ = 0.0
        private set
    var sparkleSize = 0.0
        private set
    var sparkleAngle = 0.0
        private set
    /** Brightness, 0..1. */
    var sparkleGlow = 0.0
        private set

    /**
     * [tick]: the server tick, fractional for frames between ticks. ([playerU], [playerV]): the player's feet relative to
     * the centre column's middle.
     */
    fun begin(style: ManiaStyle, time: Double, session: ManiaSession, tick: Double, playerU: Double, playerV: Double, onGround: Boolean) {
        val dt = if (lastTime.isNaN()) 0.0 else (time - lastTime).coerceIn(0.0, MAX_FRAME)
        lastTime = time
        this.style = style
        this.time = time
        untilCoal = session.ticksUntilCoal(tick)?.div(20.0) ?: Double.NaN
        coal = session.phase == ManiaSession.Phase.COAL
        beats += dt * heartRate()
        val cycle = session.cycleProgress(tick)
        sweep = TAU * (cycle ?: fraction(time / CYCLE_SECONDS))
        coalAngle = if (cycle == null) Double.NaN else TAU * session.coalProgress
        trackWaves(session)
        trackSteps(playerU, playerV, onGround)
        this.playerU = playerU
        this.playerV = playerV
        lightDetail = when {
            style == ManiaStyle.RADAR || materials || ripplesLive() -> 4
            wavesLive() || lamp -> 2
            else -> 0
        }
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
        ManiaStyle.RADAR -> {
            val trail = exp(-behindSweep(offX.toDouble(), offZ.toDouble()) * RADAR_FADE)
            if (safe) 0.15 + 0.85 * trail else 0.35 + 0.5 * trail
        }
    }

    /** Tint of column ([offX], [offZ]) showing [type] in [base] colour, lit to [crest]. */
    fun tint(base: Color, type: ManiaBlock, crest: Double, offX: Int, offZ: Int): Color {
        val safe = type == ManiaBlock.GREEN
        var colour = base
        if (animated) {
            val hot = base.mix(Colors.WHITE, if (safe) 0.78 else 0.45)
            val alpha = (base.alpha * (0.48 + 0.68 * crest)).roundToInt().coerceIn(0, 245)
            colour = Color((alpha shl 24) or base.mix(hot, crest * if (safe) 0.65 else 0.2).rgb)
        }
        if (materials && type == ManiaBlock.REDSTONE) colour = smoulder(colour, offX, offZ)
        if (lamp) colour = lamplit(colour, offX, offZ)
        return colour
    }

    private fun lamplit(colour: Color, offX: Int, offZ: Int): Color {
        val d2 = sq(offX - playerU, offZ - playerV)
        val near = exp(-d2 / (2 * LAMP_REACH * LAMP_REACH))
        val far = smoothstep(LAMP_FADE_START, LAMP_FADE_END, sqrt(d2))
        val alpha = (colour.alpha * (1 - 0.45 * far) * (1 + 0.3 * near)).roundToInt().coerceIn(0, 245)
        return Color((alpha shl 24) or colour.mix(Colors.WHITE, 0.2 * near).rgb)
    }

    /**
     * Light added on top of the tint at ([u], [v]) in column ([offX], [offZ]), which shows [type] tinted [colour].
     * `0xAARRGGBB` for an additive blend, so colour scaled by alpha; 0 for none.
     */
    fun light(u: Double, v: Double, offX: Int, offZ: Int, type: ManiaBlock, colour: Color): Int {
        lightR = 0.0
        lightG = 0.0
        lightB = 0.0
        if (style == ManiaStyle.RADAR) radarLight(u, v, colour)
        if (shockwaves) shockwaveLight(u, v)
        if (ripples) rippleLight(u, v, colour)
        if (lamp) add(colour.mix(Colors.WHITE, 0.5), LAMP_GLOW * exp(-sq(u - playerU, v - playerV) / (2 * LAMP_POOL * LAMP_POOL)))
        if (materials) when (type) {
            ManiaBlock.GREEN -> caustics(u, v, colour)
            ManiaBlock.REDSTONE -> add(EMBER, EMBER_GLOW * ember(offX, offZ) * exp(-EMBER_FOCUS * sq(u - offX, v - offZ)))
            ManiaBlock.COAL -> smoke(u, v)
            else -> Unit
        }
        return packLight()
    }

    /** Starts a shockwave when the session moves to a new step or learns its safe ring. */
    private fun trackWaves(session: ManiaSession) {
        val key = (session.cycleIndex shl 8) or (session.phase.ordinal shl 4) or session.greenRing
        val changed = session === waveSession && key != waveKey
        waveSession = session
        waveKey = key
        if (!changed || !shockwaves) return
        val colour = when (session.phase) {
            ManiaSession.Phase.TERRACOTTA -> colours[ManiaBlock.GREEN.ordinal].mix(Colors.WHITE, 0.3)
            ManiaSession.Phase.REDSTONE -> colours[ManiaBlock.REDSTONE.ordinal].mix(EMBER, 0.25)
            ManiaSession.Phase.COAL -> COAL_WAVE
            ManiaSession.Phase.WAITING -> return
        }
        waveStart[nextWave] = time
        waveColour[nextWave] = colour.rgba
        nextWave = (nextWave + 1) % MAX_WAVES
    }

    private fun wavesLive() = shockwaves && waveStart.any { time - it < WAVE_LIFE }

    private fun shockwaveLight(u: Double, v: Double) {
        val r = distance(u, v)
        for (w in 0 until MAX_WAVES) {
            val age = time - waveStart[w]
            if (age < 0 || age >= WAVE_LIFE) continue
            val fade = (1 - age / WAVE_LIFE).pow(2)
            val front = WAVE_SPEED * age
            val width = 0.6 + 1.2 * age
            val off = (r - front) / width
            var glow = WAVE_GLOW * exp(-off * off)
            // A fainter wash left behind the front.
            if (r < front) glow += WAVE_WAKE * exp(-(front - r) / 3)
            add(Color(waveColour[w]), glow * fade)
        }
    }

    /** A ripple every [STEP] blocks walked, and a bigger one on landing. */
    private fun trackSteps(u: Double, v: Double, onGround: Boolean) {
        if (!ripples) {
            stepU = Double.NaN
            return
        }
        if (onGround) {
            if (!wasOnGround) ripple(u, v, LANDING)
            else if (stepU.isNaN() || sq(u - stepU, v - stepV) > STEP * STEP) ripple(u, v, 1.0)
        }
        wasOnGround = onGround
    }

    private fun ripple(u: Double, v: Double, size: Double) {
        stepU = u
        stepV = v
        rippleU[nextRipple] = u
        rippleV[nextRipple] = v
        rippleStart[nextRipple] = time
        rippleSize[nextRipple] = size
        nextRipple = (nextRipple + 1) % MAX_RIPPLES
    }

    private fun ripplesLive() = ripples && (0 until MAX_RIPPLES).any { time - rippleStart[it] < RIPPLE_LIFE * rippleSize[it] }

    /** Two rings per ripple, the second trailing the first, thinning and fading as they spread. */
    private fun rippleLight(u: Double, v: Double, colour: Color) {
        var glow = 0.0
        for (i in 0 until MAX_RIPPLES) {
            val size = rippleSize[i]
            val life = RIPPLE_LIFE * size
            val age = time - rippleStart[i]
            if (age < 0 || age >= life) continue
            val r = distance(u - rippleU[i], v - rippleV[i])
            if (r > RIPPLE_SPEED * life + 1) continue
            val fade = (1 - age / life).pow(2) * size
            for (ring in 0 until 2) {
                val ringAge = age - ring * RIPPLE_GAP
                if (ringAge < 0) continue
                val width = 0.12 + 0.18 * ringAge
                val off = (r - (0.15 + RIPPLE_SPEED * ringAge)) / width
                glow += fade * (if (ring == 0) RIPPLE_GLOW else RIPPLE_GLOW * 0.55) * exp(-off * off)
            }
        }
        add(colour.mix(Colors.WHITE, 0.65), glow)
    }

    /** Whether column ([offX], [offZ]) has a star right now, which is then described by the sparkle fields. */
    fun sparkle(offX: Int, offZ: Int): Boolean {
        if (!glitter) return false
        val clock = time * GLITTER_RATE + hash(offX, offZ, 3)
        val slot = floor(clock).toInt()
        val chance = GLITTER_CHANCE + GLITTER_RUSH * urgency().pow(2)
        if (hash(offX, offZ, slot * 31 + 5) >= chance) return false
        val life = clock - slot
        sparkleGlow = sin(PI * life)
        sparkleX = 0.15 + 0.7 * hash(offX, offZ, slot * 7 + 11)
        sparkleZ = 0.15 + 0.7 * hash(offX, offZ, slot * 13 + 17)
        sparkleSize = GLITTER_SIZE * (0.45 + 0.55 * sparkleGlow)
        sparkleAngle = hash(offX, offZ, slot * 3 + 23) * PI / 2 + life * 1.2
        return true
    }

    /** Redstone flickers like embers, now and then flaring orange. */
    private fun smoulder(colour: Color, offX: Int, offZ: Int): Color {
        val seed = hash(offX, offZ, 1)
        val flicker = 0.5 + 0.5 * sin(time * 9.0 + seed * 40) * sin(time * 5.3 + seed * 23)
        val flare = ember(offX, offZ)
        val alpha = (colour.alpha * (0.8 + 0.35 * flicker + 0.35 * flare)).roundToInt().coerceIn(0, 245)
        return Color((alpha shl 24) or colour.mix(EMBER, 0.12 * flicker + 0.55 * flare).rgb)
    }

    /** How hard column ([offX], [offZ]) is flaring right now, 0..1; a few at a time flare up and die down. */
    private fun ember(offX: Int, offZ: Int): Double {
        val clock = time * EMBER_RATE + hash(offX, offZ, 2)
        val slot = floor(clock)
        if (hash(offX, offZ, slot.toInt()) < 1 - EMBER_CHANCE) return 0.0
        val life = clock - slot
        return (1 - life).pow(2) * (life * 8).coerceAtMost(1.0)
    }

    /** Wavy lines of light, like sun through shallow water. */
    private fun caustics(u: Double, v: Double, colour: Color) {
        val t = time
        val waves = sin(u * 1.6 + t * 1.2) + sin(v * 1.8 - t * 0.9) +
            sin((u * 0.8 + v * 1.3) * 1.4 + t * 1.5) + sin((u * 1.2 - v * 0.7) * 1.5 - t * 1.1)
        val line = (1 - abs(waves) / 4).pow(6)
        add(colour.mix(Colors.WHITE, 0.6), CAUSTIC * ((line - 0.2) / 0.8))
    }

    /** Pale wisps drifting over coal. */
    private fun smoke(u: Double, v: Double) {
        val t = time
        val n = 0.65 * noise(u * 0.45 + t * 0.35, v * 0.45 + t * 0.15) +
            0.35 * noise(u * 1.1 - t * 0.5 + 17.3, v * 1.1 + t * 0.3)
        add(SMOKE, SMOKE_GLOW * smoothstep(0.45, 0.85, n))
    }

    /** Coal getting closer, 0 (a whole terracotta step away, or unknown) to 1 (now). */
    fun urgency(): Double = if (untilCoal.isNaN()) 0.0 else (1 - untilCoal / FULL_WARNING).coerceIn(0.0, 1.0)

    private fun heartRate(): Double = when {
        coal -> RESTING_RATE * 0.8
        untilCoal.isNaN() -> RESTING_RATE
        else -> RESTING_RATE + RACING_RATE * urgency().pow(2)
    }

    private fun radarLight(u: Double, v: Double, colour: Color) {
        val r = distance(u, v)
        if (r < 0.5) return
        val near = ((r - 0.5) / 1.5).coerceAtMost(1.0)
        // The trail is about as long on every ring, so the hand reads as one straight line.
        add(colour.mix(Colors.WHITE, 0.5), RADAR_BEAM * near * exp(-behindSweep(u, v) * r * RADAR_TRAIL))
        if (coalAngle.isNaN()) return
        var off = angleOf(u, v) - coalAngle
        off -= TAU * floor((off + PI) / TAU)
        if (abs(off) > PI / 2) return
        val across = r * abs(sin(off)) / COAL_MARK_WIDTH
        add(COAL_MARK, near * (0.25 + 0.5 * urgency()) * exp(-across * across))
    }

    /** Radians from the radar hand back to ([u], [v]): 0 just behind it, up to 2π just ahead. */
    private fun behindSweep(u: Double, v: Double): Double {
        val behind = sweep - angleOf(u, v)
        return behind - TAU * floor(behind / TAU)
    }

    private fun add(colour: Color, amount: Double) {
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
        private const val MAX_FRAME = 0.1
        /** Beats per second. */
        private const val RESTING_RATE = 1.1
        private const val RACING_RATE = 2.4
        /** Beats a column lags per block from the centre, so each thump ripples outwards. */
        private const val HEART_LAG = 0.025
        /** Terracotta and redstone together, the longest coal is ever away. */
        private const val FULL_WARNING = 2.25
        private const val CYCLE_SECONDS = 3.25
        private const val TAU = 2 * PI
        /** How fast the floor dims behind the radar hand, per radian. */
        private const val RADAR_FADE = 1.6
        private const val RADAR_BEAM = 0.55
        /** How fast the beam's trail fades, per block of arc. */
        private const val RADAR_TRAIL = 0.45
        private const val COAL_MARK_WIDTH = 0.16
        private val COAL_MARK = Color(0xFFFFE6C8.toInt())
        private val EMBER = Color(0xFFFF9A3C.toInt())
        /** Flare slots per second per column, and the share of slots that flare. */
        private const val EMBER_RATE = 2.5
        private const val EMBER_CHANCE = 0.12
        private const val EMBER_GLOW = 0.45
        private const val EMBER_FOCUS = 3.0
        private const val CAUSTIC = 0.32
        private val SMOKE = Color(0xFF9C90B0.toInt())
        private const val SMOKE_GLOW = 0.2
        private const val MAX_WAVES = 4
        private const val WAVE_LIFE = 1.0
        /** Blocks per second; the front reaches the pattern's edge in about two thirds of a second. */
        private const val WAVE_SPEED = 22.0
        private const val WAVE_GLOW = 0.85
        private const val WAVE_WAKE = 0.15
        private val COAL_WAVE = Color(0xFFD8D0FF.toInt())
        private const val MAX_RIPPLES = 12
        /** Blocks walked between ripples. */
        private const val STEP = 1.3
        private const val LANDING = 1.6
        private const val RIPPLE_LIFE = 1.1
        private const val RIPPLE_SPEED = 2.6
        /** Seconds the second ring trails the first. */
        private const val RIPPLE_GAP = 0.22
        private const val RIPPLE_GLOW = 0.5
        /** Blocks: how far the brighter floor reaches, the pool of light's size, and where the far floor fades. */
        private const val LAMP_REACH = 2.2
        private const val LAMP_POOL = 1.5
        private const val LAMP_FADE_START = 4.0
        private const val LAMP_FADE_END = 10.0
        private const val LAMP_GLOW = 0.25
        /** Star slots per second per column; each star lasts a slot. */
        private const val GLITTER_RATE = 2.0
        /** Share of columns with a star, at rest and added just before coal. */
        private const val GLITTER_CHANCE = 0.03
        private const val GLITTER_RUSH = 0.3
        private const val GLITTER_SIZE = 0.42

        fun distance(x: Double, z: Double) = sqrt(x * x + z * z)
        fun distance(x: Int, z: Int) = sqrt((x * x + z * z).toDouble())
        fun fraction(x: Double) = x - floor(x)
        fun sq(x: Double, z: Double) = x * x + z * z

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

        /** Smooth value noise, 0..1. */
        fun noise(x: Double, z: Double): Double {
            val ix = floor(x)
            val iz = floor(z)
            val fx = x - ix
            val fz = z - iz
            val sx = fx * fx * (3 - 2 * fx)
            val sz = fz * fz * (3 - 2 * fz)
            val x0 = ix.toInt()
            val z0 = iz.toInt()
            val top = hash(x0, z0, 7) + (hash(x0 + 1, z0, 7) - hash(x0, z0, 7)) * sx
            val bottom = hash(x0, z0 + 1, 7) + (hash(x0 + 1, z0 + 1, 7) - hash(x0, z0 + 1, 7)) * sx
            return top + (bottom - top) * sz
        }

        /** Radians clockwise from north (-z) seen from above, 0 until 2π. */
        fun angleOf(u: Double, v: Double): Double {
            val a = atan2(u, -v)
            return if (a < 0) a + TAU else a
        }

        /** Lub-dub: a strong thump at the start of the beat and a softer one just after, 0..1. */
        private fun heartbeat(p: Double): Double {
            var v = exp(-p * 16)
            if (p >= 0.22) v += 0.6 * exp(-(p - 0.22) * 16)
            return v.coerceAtMost(1.0)
        }
    }
}
