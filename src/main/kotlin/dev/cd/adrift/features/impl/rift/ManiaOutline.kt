package dev.cd.adrift.features.impl.rift

//? if >=26.2 {
import com.odtheking.odin.events.RenderExtractEvent
//?} else
//import dev.cd.adrift.utils.RenderExtractEvent
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.drawLine
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** `0xRRGGBB` of [from] blended [t] of the way towards [to]. */
internal fun mixRgb(from: Int, to: Int, t: Double): Int {
    val k = t.coerceIn(0.0, 1.0)
    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + (b - a) * k + 0.5).toInt()
    }
    return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

internal fun colourOf(rgb: Int, alpha: Int) = Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, alpha / 255f)

/**
 * One frame's contour lines around the mania zones. Edges are fed in per column side and follow the terrain: an edge
 * steps mid-block where its block does (stairs), and wherever edges meeting at a block corner sit at different
 * heights the corner is closed with a vertical line, so the contour stays one unbroken line over steps, slabs and
 * around corners instead of cutting off there.
 */
internal class ManiaOutline {
    private var segments = DoubleArray(6 * 128)
    /** Per segment: block ordinal in the high bits, crest (0..255) in the low byte. */
    private var styles = IntArray(128)
    private var segmentCount = 0
    /** Edge ends per block corner, packed so that sorting groups them by corner and zone, lowest first. */
    private var joints = LongArray(128)
    private var jointCount = 0
    private var originX = 0
    private var originZ = 0
    private var originY = 0.0
    private var lineColour = Colors.WHITE
    private var lineWidth = 0f

    val isEmpty get() = segmentCount == 0

    fun begin(centreX: Int, centreZ: Int, baseY: Double) {
        segmentCount = 0
        jointCount = 0
        originX = centreX
        originZ = centreZ
        originY = baseY
    }

    /**
     * The [side] edge of the column at offset ([dx], [dz]) from the centre. [heights] holds two surface heights per
     * side, one for each half of the edge. [crest] is the pulse strength, 0..1.
     */
    fun edge(dx: Int, dz: Int, side: Int, heights: DoubleArray, type: ManiaBlock, crest: Double) {
        val style = (type.ordinal shl 8) or (crest.coerceIn(0.0, 1.0) * 255).roundToInt()
        val alongZ = side >= WEST
        val ax = dx + if (side == EAST) 1 else 0
        val az = dz + if (side == SOUTH) 1 else 0
        val bx = if (alongZ) ax else ax + 1
        val bz = if (alongZ) az + 1 else az
        val ya = heights[side * 2] + LIFT
        val yb = heights[side * 2 + 1] + LIFT
        val x0 = (originX + ax).toDouble()
        val z0 = (originZ + az).toDouble()
        val x1 = (originX + bx).toDouble()
        val z1 = (originZ + bz).toDouble()
        if (abs(ya - yb) <= STEP_EPSILON) segment(x0, ya, z0, x1, yb, z1, style)
        else {
            val mx = (x0 + x1) / 2
            val mz = (z0 + z1) / 2
            segment(x0, ya, z0, mx, ya, mz, style)
            segment(mx, ya, mz, mx, yb, mz, style)
            segment(mx, yb, mz, x1, yb, z1, style)
        }
        joint(ax, az, style, ya)
        joint(bx, bz, style, yb)
    }

    /** Emits everything as lines of [width], [colours] indexed by block ordinal. Glow layers go under brighter ones. */
    fun draw(event: RenderExtractEvent, width: Float, colours: Array<Color>) {
        closeJoints()
        for (layer in 0 until LAYERS) {
            val lift = layer * LAYER_LIFT
            for (i in 0 until segmentCount) {
                if (!pickStyle(layer, styles[i], width, colours)) continue
                val o = i * 6
                val from = Vec3(segments[o], segments[o + 1] + lift, segments[o + 2])
                val to = Vec3(segments[o + 3], segments[o + 4] + lift, segments[o + 5])
                event.drawLine(listOf(from, to), lineColour, true, lineWidth)
            }
        }
    }

    private fun closeJoints() {
        joints.sort(0, jointCount)
        for (i in 1 until jointCount) {
            val low = joints[i - 1]
            val high = joints[i]
            if (low ushr GROUP_SHIFT != high ushr GROUP_SHIFT) continue
            val gap = quantY(high) - quantY(low)
            if (gap < MIN_RISER || gap > MAX_RISER) continue
            val group = (low ushr GROUP_SHIFT).toInt()
            val corner = group shr 3
            val x = (originX + corner / CORNER_SIDE - CORNER_BIAS).toDouble()
            val z = (originZ + corner % CORNER_SIDE - CORNER_BIAS).toDouble()
            val style = ((group and 7) shl 8) or (low and 0xFF).toInt()
            segment(x, worldY(low), z, x, worldY(high), z, style)
        }
    }

    private fun segment(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, style: Int) {
        if (segmentCount == styles.size) {
            styles = styles.copyOf(segmentCount * 2)
            segments = segments.copyOf(segmentCount * 12)
        }
        val o = segmentCount * 6
        segments[o] = x0
        segments[o + 1] = y0
        segments[o + 2] = z0
        segments[o + 3] = x1
        segments[o + 4] = y1
        segments[o + 5] = z1
        styles[segmentCount++] = style
    }

    private fun joint(cornerX: Int, cornerZ: Int, style: Int, y: Double) {
        if (jointCount == joints.size) joints = joints.copyOf(jointCount * 2)
        val corner = (cornerX + CORNER_BIAS) * CORNER_SIDE + cornerZ + CORNER_BIAS
        val group = ((corner shl 3) or (style shr 8)).toLong()
        val quant = ((y - originY) * Y_SCALE).roundToLong() + Y_BIAS
        joints[jointCount++] = (group shl GROUP_SHIFT) or (quant shl 8) or (style and 0xFF).toLong()
    }

    private fun quantY(joint: Long) = (joint ushr 8) and Y_MASK

    private fun worldY(joint: Long) = (quantY(joint) - Y_BIAS).toDouble() / Y_SCALE + originY

    /** Sets [lineColour] and [lineWidth] for a segment in [layer]; false if the segment has no line there. */
    private fun pickStyle(layer: Int, style: Int, width: Float, colours: Array<Color>): Boolean {
        val type = style shr 8
        val crest = (style and 0xFF) / 255.0
        val base = colours[type]
        val rgb = base.rgba and 0xFFFFFF
        if (type == ManiaBlock.GREEN.ordinal) when (layer) {
            0 -> line(rgb, (115 + 85 * crest).roundToInt().coerceIn(80, 215), width * (1.8f + 0.65f * crest.toFloat()))
            1 -> line(mixRgb(rgb, MINT, 0.55), 0xF8, width)
            else -> line(mixRgb(rgb, WHITE, 0.78), (200 + 55 * crest).roundToInt(), width * 0.45f)
        } else {
            if (layer != 0) return false
            if (type == ManiaBlock.COAL.ordinal) line(rgb, 0xEF, width * 1.15f)
            else line(rgb, (base.alpha + 90).coerceIn(50, 225), width * 0.75f)
        }
        return true
    }

    private fun line(rgb: Int, alpha: Int, width: Float) {
        lineColour = colourOf(rgb, alpha)
        lineWidth = width
    }

    companion object {
        const val NORTH = 0
        const val SOUTH = 1
        const val WEST = 2
        const val EAST = 3
        /** Column offset of the neighbour across each side. */
        val SIDE_DX = intArrayOf(0, 0, -1, 1)
        val SIDE_DZ = intArrayOf(-1, 1, 0, 0)

        private const val MINT = 0xE2FFF5
        const val WHITE = 0xFFFFFF
        private const val LAYERS = 3
        /** Above the block tint, which sits 0.002 off the block. */
        private const val LIFT = 0.01
        private const val LAYER_LIFT = 0.0022
        private const val STEP_EPSILON = 0.02

        private const val Y_SCALE = 1024
        private const val Y_BIAS = 1L shl 20
        private const val Y_MASK = (1L shl 32) - 1
        private const val GROUP_SHIFT = 40
        private const val MIN_RISER = (STEP_EPSILON * Y_SCALE).toLong()
        /** Taller gaps than this are separate floors rather than a step in one. */
        private const val MAX_RISER = (2.5 * Y_SCALE).toLong()
        private const val CORNER_BIAS = ManiaGeometry.PATTERN_RADIUS + 1
        private const val CORNER_SIDE = 2 * CORNER_BIAS + 2
    }
}
