package dev.cd.adrift.features.boss

import dev.cd.adrift.features.boss.ManiaFx.Companion.TAU
import dev.cd.adrift.features.boss.ManiaFx.Companion.fraction
import dev.cd.adrift.features.boss.ManiaFx.Companion.hash
import dev.cd.adrift.features.boss.ManiaFx.Companion.smoothstep
import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.Colors
import dev.cd.adrift.utils.render.GeometryBatch
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The disco style: a mirror ball turning above the centre throws coloured spots that sweep round the floor, lasers
 * swing across it and the tiles light up on the beat. Spot, laser and ball colours leave out green so it still only
 * ever means safe. Positions are column offsets from the pattern's centre, as in [ManiaFx].
 */
internal class ManiaDisco {
    private var time = 0.0
    /** Beats so far, the index of this one, and how far through it we are. */
    private var beat = 0.0
    private var beatIndex = 0
    private var beatFraction = 0.0
    private var bar = 0
    /** Seconds since the floor went to coal, for the strobe; infinite when it hasn't. */
    private var sinceCoal = Double.POSITIVE_INFINITY

    // Spots: where they are this frame (u, v), their facing (unit radial), size, colour and brightness.
    private val spotU = DoubleArray(SPOTS)
    private val spotV = DoubleArray(SPOTS)
    private val spotSin = DoubleArray(SPOTS)
    private val spotCos = DoubleArray(SPOTS)
    private val spotScale = DoubleArray(SPOTS)
    private val spotColour = arrayOfNulls<Color>(SPOTS)
    private val spotGlow = DoubleArray(SPOTS)

    // Lasers: direction from the centre and brightness.
    private val laserSin = DoubleArray(LASERS)
    private val laserCos = DoubleArray(LASERS)
    private var laserGlow = 0.0

    private var floorWash = 0.0
    private var strobe = 0.0

    // Scratch corners for [facet].
    private val px = DoubleArray(4)
    private val py = DoubleArray(4)
    private val pz = DoubleArray(4)
    private val lats = DoubleArray(4)
    private val lons = DoubleArray(4)

    fun begin(time: Double, sinceCoal: Double) {
        this.time = time
        this.sinceCoal = sinceCoal
        beat = time * BEATS_PER_SECOND
        beatIndex = floor(beat).toInt()
        beatFraction = beat - beatIndex
        bar = Math.floorDiv(beatIndex, 4)
        val kick = exp(-beatFraction * 5)

        val turn = time * BALL_SPIN
        for (i in 0 until SPOTS) {
            val radius = SPOT_MIN_RADIUS + SPOT_SPREAD * sqrt(hash(i, 0, 101)) + 0.6 * sin(time * 0.9 + i)
            val angle = TAU * hash(i, 0, 102) + turn
            val s = sin(angle)
            val c = cos(angle)
            spotSin[i] = s
            spotCos[i] = c
            spotU[i] = radius * s
            spotV[i] = -radius * c
            // Further from the ball the light hits the floor at a lower angle and spreads.
            spotScale[i] = 0.8 + radius / 25
            spotColour[i] = PALETTE[(i + bar) % PALETTE.size]
            spotGlow[i] = SPOT_GLOW * (0.7 + 0.3 * kick) * (0.85 + 0.15 * sin(time * 11 + i * 1.7))
        }

        // Lasers swing back and forth from the centre, and only fire on every other bar.
        for (i in 0 until LASERS) {
            val angle = TAU * i / LASERS + 1.3 * sin(time * 0.8 + i * 2.1) + time * 0.25
            laserSin[i] = sin(angle)
            laserCos[i] = cos(angle)
        }
        laserGlow = if (bar % 2 == 1) LASER_GLOW * (0.6 + 0.4 * kick) else 0.0

        floorWash = if (beatIndex % 4 == 0) WASH_GLOW * exp(-beatFraction * 6) else 0.0
        // Coal hitting sets off a few strobe flashes.
        strobe = if (sinceCoal < STROBE_TIME && fraction(sinceCoal * STROBE_RATE) < 0.5) STROBE_GLOW else 0.0
    }

    /** Dance-floor tiles: about half light up on each beat and fade until the next. Safe tiles never go fully dark. */
    fun crest(offX: Int, offZ: Int, safe: Boolean): Double {
        val lit = hash(offX, offZ, beatIndex * 17 + 3) < TILE_CHANCE
        return when {
            safe -> if (lit) 1 - 0.4 * beatFraction else 0.5
            lit -> 0.85 - 0.4 * beatFraction
            else -> 0.22
        }
    }

    fun light(u: Double, v: Double, fx: ManiaFx) {
        for (i in 0 until SPOTS) {
            val du = u - spotU[i]
            val dv = v - spotV[i]
            if (du * du + dv * dv > SPOT_REACH) continue
            val scale = spotScale[i]
            val radial = (du * spotSin[i] - dv * spotCos[i]) / (SPOT_RADIAL * scale)
            val tangent = (du * spotCos[i] + dv * spotSin[i]) / (SPOT_TANGENT * scale)
            val q = radial * radial + tangent * tangent
            val core = 1 - smoothstep(0.55, 1.0, q)
            fx.add(spotColour[i]!!, spotGlow[i] * (0.85 * core + 0.3 * exp(-q * 0.8)))
        }
        if (laserGlow > 0) {
            for (i in 0 until LASERS) {
                val along = u * laserSin[i] - v * laserCos[i]
                if (along < 1) continue
                val across = abs(u * laserCos[i] + v * laserSin[i]) / LASER_WIDTH
                fx.add(LASER_COLOURS[i], laserGlow * smoothstep(1.0, 3.0, along) * exp(-across * across))
            }
        }
        evenLight(fx)
    }

    /** The light that falls on the whole floor alike, added in the same order as in [light]. */
    fun evenLight(fx: ManiaFx) {
        if (floorWash > 0) fx.add(PALETTE[bar % PALETTE.size], floorWash)
        if (strobe > 0) fx.add(Colors.WHITE, strobe)
    }

    /**
     * Whether a spot or laser reaches anywhere in [u0]..[u1] x [v0]..[v1]; if not, [light] there is only
     * [evenLight]. Conservative: a laser counts out to [LASER_REACH] across, where it adds under 1e-31, far too
     * little to change the sum it's added to.
     */
    fun lightVaries(u0: Double, u1: Double, v0: Double, v1: Double): Boolean {
        val uMin = u0 - EDGE_SLACK
        val uMax = u1 + EDGE_SLACK
        val vMin = v0 - EDGE_SLACK
        val vMax = v1 + EDGE_SLACK
        for (i in 0 until SPOTS) {
            val du = maxOf(uMin - spotU[i], 0.0, spotU[i] - uMax)
            val dv = maxOf(vMin - spotV[i], 0.0, spotV[i] - vMax)
            if (du * du + dv * dv <= SPOT_REACH) return true
        }
        if (laserGlow > 0) {
            for (i in 0 until LASERS) {
                val s = laserSin[i]
                val c = laserCos[i]
                // Both are linear, so their extremes over the rectangle are at its corners.
                val along = maxOf(maxOf(uMin * s - vMin * c, uMax * s - vMin * c), maxOf(uMin * s - vMax * c, uMax * s - vMax * c))
                if (along <= 1) continue
                val a00 = uMin * c + vMin * s
                val a10 = uMax * c + vMin * s
                val a01 = uMin * c + vMax * s
                val a11 = uMax * c + vMax * s
                val nearest = minOf(minOf(a00, a10), minOf(a01, a11))
                val farthest = maxOf(maxOf(a00, a10), maxOf(a01, a11))
                if (nearest <= LASER_REACH && farthest >= -LASER_REACH) return true
            }
        }
        return false
    }

    /**
     * The mirror ball, hanging [BALL_HEIGHT] above the floor at ([centreX], [floorY], [centreZ]): facets on a dark
     * core, a few catching the light each moment. Only facets facing [camera] are drawn, so the batch's sorting
     * never has to untangle front from back.
     */
    fun ball(batch: GeometryBatch, centreX: Double, floorY: Double, centreZ: Double, camera: Vec3) {
        val cy = floorY + BALL_HEIGHT
        val turn = time * BALL_SPIN
        val sparkleSlot = floor(time * 10).toInt()
        for (la in 0 until BALL_BANDS) {
            val lat0 = -Math.PI / 2 + Math.PI * la / BALL_BANDS
            val lat1 = -Math.PI / 2 + Math.PI * (la + 1) / BALL_BANDS
            val latMid = (lat0 + lat1) / 2
            for (lo in 0 until BALL_SEGMENTS) {
                val lon0 = TAU * lo / BALL_SEGMENTS + turn
                val lon1 = TAU * (lo + 1) / BALL_SEGMENTS + turn
                val lonMid = (lon0 + lon1) / 2
                val nx = cos(latMid) * sin(lonMid)
                val ny = sin(latMid)
                val nz = cos(latMid) * cos(lonMid)
                if ((camera.x - centreX) * nx + (camera.y - cy) * ny + (camera.z - centreZ) * nz < 0) continue

                val light = 0.4 + 0.5 * (nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z).coerceAtLeast(0.0)
                var colour = Color(0xFFFFFFFF.toInt()).mix(Color(0xFF000000.toInt()), 1 - light).mix(SILVER, 0.35)
                if (hash(la, lo, bar + 900) < 0.22) colour = colour.mix(PALETTE[(la + lo + bar) % PALETTE.size], 0.55)
                if (hash(la, lo, sparkleSlot) > 0.93) colour = Colors.WHITE

                facet(batch, centreX, cy, centreZ, BALL_RADIUS * 0.995, lat0, lat1, lon0, lon1, 1.0, GROUT)
                facet(batch, centreX, cy, centreZ, BALL_RADIUS, lat0, lat1, lon0, lon1, FACET_FILL, colour)
            }
        }
    }

    /** The chain the ball hangs from. */
    fun chain(batch: GeometryBatch, centreX: Double, floorY: Double, centreZ: Double) {
        val top = floorY + BALL_HEIGHT + BALL_RADIUS
        batch.line(centreX, top, centreZ, centreX, top + CHAIN_LENGTH, centreZ, CHAIN, 2f)
    }

    /** Beams of light from the ball to each spot, tapering up to it and facing [camera]. Additive, for a glow batch. */
    fun beams(batch: GeometryBatch, centreX: Double, floorY: Double, centreZ: Double, camera: Vec3) {
        val sx = centreX
        val sy = floorY + BALL_HEIGHT
        val sz = centreZ
        for (i in 0 until SPOTS) {
            val ex = centreX + spotU[i]
            val ey = floorY + 0.05
            val ez = centreZ + spotV[i]
            var dx = ex - sx
            var dy = ey - sy
            var dz = ez - sz
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            dx /= length
            dy /= length
            dz /= length
            // Across the beam, square to both it and the line of sight.
            val wx = camera.x - (sx + ex) / 2
            val wy = camera.y - (sy + ey) / 2
            val wz = camera.z - (sz + ez) / 2
            var ax = dy * wz - dz * wy
            var ay = dz * wx - dx * wz
            var az = dx * wy - dy * wx
            val across = sqrt(ax * ax + ay * ay + az * az)
            if (across < 1.0e-6) continue
            ax /= across
            ay /= across
            az /= across
            val startX = sx + dx * BALL_RADIUS
            val startY = sy + dy * BALL_RADIUS
            val startZ = sz + dz * BALL_RADIUS
            val near = BEAM_START_WIDTH / 2
            val far = BEAM_END_WIDTH * spotScale[i] / 2
            val rgb = spotColour[i]!!.rgb
            val glow = spotGlow[i] / SPOT_GLOW
            val bright = ((BEAM_START_GLOW * glow * 255).roundToInt().coerceIn(0, 255) shl 24) or rgb
            val dim = ((BEAM_END_GLOW * glow * 255).roundToInt().coerceIn(0, 255) shl 24) or rgb
            batch.shadedQuad(
                startX - ax * near, startY - ay * near, startZ - az * near, bright,
                startX + ax * near, startY + ay * near, startZ + az * near, bright,
                ex + ax * far, ey + ay * far, ez + az * far, dim,
                ex - ax * far, ey - ay * far, ez - az * far, dim,
            )
        }
    }

    /** One facet of the ball at [radius], shrunk to [fill] of its size about its middle. */
    private fun facet(
        batch: GeometryBatch, cx: Double, cy: Double, cz: Double, radius: Double,
        lat0: Double, lat1: Double, lon0: Double, lon1: Double, fill: Double, colour: Color,
    ) {
        lats[0] = lat0
        lats[1] = lat0
        lats[2] = lat1
        lats[3] = lat1
        lons[0] = lon0
        lons[1] = lon1
        lons[2] = lon1
        lons[3] = lon0
        var mx = 0.0
        var my = 0.0
        var mz = 0.0
        for (k in 0 until 4) {
            px[k] = radius * cos(lats[k]) * sin(lons[k])
            py[k] = radius * sin(lats[k])
            pz[k] = radius * cos(lats[k]) * cos(lons[k])
            mx += px[k] / 4
            my += py[k] / 4
            mz += pz[k] / 4
        }
        for (k in 0 until 4) {
            px[k] = cx + mx + (px[k] - mx) * fill
            py[k] = cy + my + (py[k] - my) * fill
            pz[k] = cz + mz + (pz[k] - mz) * fill
        }
        batch.quad(colour, px[0], py[0], pz[0], px[1], py[1], pz[1], px[2], py[2], pz[2], px[3], py[3], pz[3])
    }

    companion object {
        /** About 128 bpm. */
        const val BEATS_PER_SECOND = 128 / 60.0
        /** Radians per second the ball, and so its spots, turns. */
        private const val BALL_SPIN = 0.45
        private const val SPOTS = 20
        private const val SPOT_MIN_RADIUS = 2.0
        private const val SPOT_SPREAD = 12.5
        /** Half sizes of a spot across and along its path, in blocks. */
        private const val SPOT_RADIAL = 0.65
        private const val SPOT_TANGENT = 1.0
        /** Squared distance beyond which a spot adds nothing. */
        private const val SPOT_REACH = 6.0
        private const val SPOT_GLOW = 0.75
        private const val TILE_CHANCE = 0.45
        private const val LASERS = 3
        private const val LASER_WIDTH = 0.14
        private const val LASER_GLOW = 0.7
        /** Distance across a laser beyond which it adds under exp(-72) of its glow. */
        private const val LASER_REACH = LASER_WIDTH * 8.5
        /** Widens [lightVaries]'s rectangle against rounding in the light grid's corner positions. */
        private const val EDGE_SLACK = 1.0e-6
        private val LASER_COLOURS = arrayOf(Color(0xFF2DE1FF.toInt()), Color(0xFFFF2DD2.toInt()), Color(0xFFFFE08A.toInt()))
        private const val WASH_GLOW = 0.1
        private const val STROBE_TIME = 0.45
        private const val STROBE_RATE = 14.0
        private const val STROBE_GLOW = 0.3

        private const val BALL_HEIGHT = 5.5
        private const val BALL_RADIUS = 0.75
        private const val BALL_BANDS = 10
        private const val BALL_SEGMENTS = 20
        private const val FACET_FILL = 0.86
        private const val CHAIN_LENGTH = 3.0
        // Light on the ball comes from above and a little to the side.
        private const val LIGHT_X = 0.28
        private const val LIGHT_Y = 0.92
        private const val LIGHT_Z = 0.27
        private val SILVER = Color(0xFFC8D2E6.toInt())
        private val GROUT = Color(0xFF1A1A22.toInt())
        private val CHAIN = Color(0xC8505058.toInt())
        private const val BEAM_START_WIDTH = 0.05
        private const val BEAM_END_WIDTH = 1.1
        private const val BEAM_START_GLOW = 0.35
        private const val BEAM_END_GLOW = 0.06

        /** Party colours, with no green so green still only means safe. */
        private val PALETTE = arrayOf(
            Color(0xFFFF2DD2.toInt()), // magenta
            Color(0xFF2DE1FF.toInt()), // cyan
            Color(0xFFFFC83C.toInt()), // gold
            Color(0xFF9B5BFF.toInt()), // violet
            Color(0xFFFF5A8C.toInt()), // pink
            Color(0xFF3C78FF.toInt()), // blue
            Color(0xFFFFFFFF.toInt()), // white
        )
    }
}
