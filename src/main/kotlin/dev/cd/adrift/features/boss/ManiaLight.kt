package dev.cd.adrift.features.boss

import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.Colors
import dev.cd.adrift.utils.render.GeometryBatch
import net.minecraft.world.phys.AABB
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The top faces of one frame's pattern tints, lit by [ManiaFx.light] in an additive layer just above the tint. Each
 * face is split into a grid and lit per grid corner, so light can have edges finer than a block.
 */
internal class ManiaLight {
    /** Per face: minX, maxX, minZ, maxZ, y. */
    private var faces = DoubleArray(5 * 256)
    /** Per face: column offset x and z, block ordinal, tint colour. */
    private var info = IntArray(INFO * 256)
    private var count = 0
    private var corners = IntArray(0)

    val isEmpty get() = count == 0

    fun begin() {
        count = 0
    }

    fun face(box: AABB, offX: Int, offZ: Int, type: ManiaBlock, colour: Color) {
        if (count * 5 == faces.size) {
            faces = faces.copyOf(faces.size * 2)
            info = info.copyOf(info.size * 2)
        }
        val o = count * 5
        faces[o] = box.minX
        faces[o + 1] = box.maxX
        faces[o + 2] = box.minZ
        faces[o + 3] = box.maxZ
        faces[o + 4] = box.maxY + LIFT
        val i = count * INFO
        info[i] = offX
        info[i + 1] = offZ
        info[i + 2] = type.ordinal
        info[i + 3] = colour.rgba
        count++
    }

    /** [originX], [originZ]: world position of the pattern's centre column's middle. */
    fun draw(batch: GeometryBatch, fx: ManiaFx, originX: Double, originZ: Double) {
        if (fx.lightDetail > 0) drawLight(batch, fx, originX, originZ)
        if (fx.glitter) drawStars(batch, fx, originX, originZ)
    }

    private fun drawStars(batch: GeometryBatch, fx: ManiaFx, originX: Double, originZ: Double) {
        for (f in 0 until count) {
            val offX = info[f * INFO]
            val offZ = info[f * INFO + 1]
            if (!fx.sparkle(offX, offZ)) continue
            val x = originX + offX - 0.5 + fx.sparkleX
            val z = originZ + offZ - 0.5 + fx.sparkleZ
            val o = f * 5
            // A stair's lower box reaches under its upper one; that star is hidden by the block.
            if (x < faces[o] || x > faces[o + 1] || z < faces[o + 2] || z > faces[o + 3]) continue
            val colour = Color(info[f * INFO + 3]).mix(Colors.WHITE, 0.75)
            star(batch, x, faces[o + 4] + STAR_LIFT, z, fx.sparkleSize, fx.sparkleAngle, colour, fx.sparkleGlow)
        }
    }

    /** A flat eight-pointed star: four long arms and four short ones, bright at the middle and fading to the tips. */
    private fun star(batch: GeometryBatch, x: Double, y: Double, z: Double, size: Double, angle: Double, colour: Color, glow: Double) {
        val core = Color(((glow * 255).roundToInt().coerceIn(0, 255) shl 24) or colour.rgb).rgba
        val flank = Color(((glow * 0.3 * 255).roundToInt().coerceIn(0, 255) shl 24) or colour.rgb).rgba
        val tip = colour.rgb
        for (arm in 0 until 8) {
            val a = angle + arm * PI / 4
            val length = if (arm % 2 == 0) size else size * 0.45
            val width = length * 0.16
            val dx = cos(a)
            val dz = sin(a)
            batch.shadedQuad(
                x, y, z, core,
                x + dx * width - dz * width, y, z + dz * width + dx * width, flank,
                x + dx * length, y, z + dz * length, tip,
                x + dx * width + dz * width, y, z + dz * width - dx * width, flank,
            )
        }
    }

    private fun drawLight(batch: GeometryBatch, fx: ManiaFx, originX: Double, originZ: Double) {
        val n = fx.lightDetail
        val side = n + 1
        if (corners.size < side * side) corners = IntArray(side * side)
        for (f in 0 until count) {
            val o = f * 5
            val minX = faces[o]
            val maxX = faces[o + 1]
            val minZ = faces[o + 2]
            val maxZ = faces[o + 3]
            val y = faces[o + 4]
            val offX = info[f * INFO]
            val offZ = info[f * INFO + 1]
            val type = ManiaBlock.entries[info[f * INFO + 2]]
            val colour = Color(info[f * INFO + 3])
            val stepX = (maxX - minX) / n
            val stepZ = (maxZ - minZ) / n
            for (j in 0..n) for (i in 0..n) {
                corners[j * side + i] = fx.light(minX + i * stepX - originX, minZ + j * stepZ - originZ, offX, offZ, type, colour)
            }
            for (j in 0 until n) for (i in 0 until n) {
                val c00 = corners[j * side + i]
                val c10 = corners[j * side + i + 1]
                val c01 = corners[(j + 1) * side + i]
                val c11 = corners[(j + 1) * side + i + 1]
                if ((c00 or c10 or c01 or c11) ushr 24 == 0) continue
                val x0 = minX + i * stepX
                val z0 = minZ + j * stepZ
                val x1 = x0 + stepX
                val z1 = z0 + stepZ
                batch.shadedQuad(x0, y, z0, c00, x0, y, z1, c01, x1, y, z1, c11, x1, y, z0, c10)
            }
        }
    }

    private companion object {
        /** Above the tint, below the outline. */
        const val LIFT = 0.003
        const val INFO = 4
        const val STAR_LIFT = 0.002
    }
}
