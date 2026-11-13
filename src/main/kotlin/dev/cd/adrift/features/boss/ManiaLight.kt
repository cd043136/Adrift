package dev.cd.adrift.features.boss

import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.render.GeometryBatch
import net.minecraft.world.phys.AABB

/**
 * The top faces of one frame's pattern tints, lit by [ManiaFx.light] in an additive layer just above the tint. Each
 * face is split into a grid and lit per grid corner, so light can have edges finer than a block.
 */
internal class ManiaLight {
    /** Per face: minX, maxX, minZ, maxZ, y. */
    private var faces = DoubleArray(5 * 256)
    /** Per face: block ordinal, tint colour. */
    private var info = IntArray(2 * 256)
    private var count = 0
    private var corners = IntArray(0)

    val isEmpty get() = count == 0

    fun begin() {
        count = 0
    }

    fun face(box: AABB, type: ManiaBlock, colour: Color) {
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
        info[count * 2] = type.ordinal
        info[count * 2 + 1] = colour.rgba
        count++
    }

    /** [originX], [originZ]: world position of the pattern's centre column's middle. */
    fun draw(batch: GeometryBatch, fx: ManiaFx, originX: Double, originZ: Double) {
        val n = fx.lightDetail
        if (n <= 0) return
        val side = n + 1
        if (corners.size < side * side) corners = IntArray(side * side)
        for (f in 0 until count) {
            val o = f * 5
            val minX = faces[o]
            val maxX = faces[o + 1]
            val minZ = faces[o + 2]
            val maxZ = faces[o + 3]
            val y = faces[o + 4]
            val type = ManiaBlock.entries[info[f * 2]]
            val colour = Color(info[f * 2 + 1])
            val stepX = (maxX - minX) / n
            val stepZ = (maxZ - minZ) / n
            for (j in 0..n) for (i in 0..n) {
                corners[j * side + i] = fx.light(minX + i * stepX - originX, minZ + j * stepZ - originZ, type, colour)
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
    }
}
