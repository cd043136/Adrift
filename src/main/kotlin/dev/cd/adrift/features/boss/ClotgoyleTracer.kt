package dev.cd.adrift.features.boss

import dev.cd.adrift.events.RenderWorldEvent
import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module
import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.Location
import dev.cd.adrift.utils.render.lines
import dev.cd.adrift.utils.renderBoundingBox
import dev.cd.adrift.utils.renderPos
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.RemotePlayer
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

object ClotgoyleTracer : Module(
    "Clotgoyle Tracer",
    Category.BOSS,
    "Draws a tracer from your crosshair to nearby clotgoyles.",
    toggle = "Clotgoyle Tracer",
) {
    private val colour by color("Line Colour", Color("ff0000ff"), "Colour of the tracer line.")
    private val lineWidth by double("Line Width", 3.0, 1.0..8.0, 0.5, "Thickness of the tracer line.")
    private val maxDistance by int("Max Distance", 10, 3..16, 1, "Only draws tracers to clotgoyles this close.", " blocks")

    private const val CLOTGOYLE_NAME = "Clotgoyle "

    init {
        on<RenderWorldEvent> {
            if (!Location.inRift) return@on
            val player = mc.player ?: return@on
            val level = mc.level ?: return@on
            //? if >=26.2 {
            val camera = mc.gameRenderer.mainCamera()
            //?} else
            //val camera = mc.gameRenderer.mainCamera
            // A vertex exactly at the camera gets clipped and the whole line vanishes, so start just in front of it
            val forward = camera.forwardVector()
            val cam = camera.position().add(forward.x() * 0.5, forward.y() * 0.5, forward.z() * 0.5)
            val eye = player.eyePosition
            val range = maxDistance.toDouble()
            val width = lineWidth.toFloat()

            val targets = level.getEntities(player, player.boundingBox.inflate(range)).filter {
                it is RemotePlayer && it.isAlive && it.plainTextName == CLOTGOYLE_NAME &&
                    player.distanceTo(it) <= range && hasClearLineOfSight(level, eye, it)
            }
            if (targets.isEmpty()) return@on

            lines {
                for (target in targets) {
                    val end = target.renderPos.add(0.0, target.bbHeight * 0.5, 0.0)
                    line(cam.x, cam.y, cam.z, end.x, end.y, end.z, colour, width)
                }
            }
        }
    }

    private fun hasClearLineOfSight(level: ClientLevel, from: Vec3, target: RemotePlayer): Boolean {
        val to = target.renderBoundingBox.center
        return hasClearLineOfSight(level, from, to)
    }

    /**
     * Block-only line of sight: walks the voxels between [from] and [to] (Amanatides & Woo) and reports whether no
     * collision shape touches the segment. Entities are deliberately ignored: their visible models differ from their
     * bounding boxes, and in F5 you can see behind most of them anyway. Only walls hide the tracer.
     */
    private fun hasClearLineOfSight(level: ClientLevel, from: Vec3, to: Vec3): Boolean {
        var x = Mth.floor(from.x)
        var y = Mth.floor(from.y)
        var z = Mth.floor(from.z)
        val tx = Mth.floor(to.x)
        val ty = Mth.floor(to.y)
        val tz = Mth.floor(to.z)
        if (x == tx && y == ty && z == tz) return true

        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z

        val stepX = if (dx > 0.0) 1 else -1
        val stepY = if (dy > 0.0) 1 else -1
        val stepZ = if (dz > 0.0) 1 else -1

        var tMaxX = if (dx > 0.0) (x + 1 - from.x) / dx else if (dx < 0.0) (from.x - x) / -dx else Double.POSITIVE_INFINITY
        var tMaxY = if (dy > 0.0) (y + 1 - from.y) / dy else if (dy < 0.0) (from.y - y) / -dy else Double.POSITIVE_INFINITY
        var tMaxZ = if (dz > 0.0) (z + 1 - from.z) / dz else if (dz < 0.0) (from.z - z) / -dz else Double.POSITIVE_INFINITY

        val tDeltaX = if (dx != 0.0) abs(1.0 / dx) else Double.POSITIVE_INFINITY
        val tDeltaY = if (dy != 0.0) abs(1.0 / dy) else Double.POSITIVE_INFINITY
        val tDeltaZ = if (dz != 0.0) abs(1.0 / dz) else Double.POSITIVE_INFINITY

        val pos = BlockPos.MutableBlockPos()
        var guard = 64
        while (guard-- > 0) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX
                tMaxX += tDeltaX
            } else if (tMaxY < tMaxZ) {
                y += stepY
                tMaxY += tDeltaY
            } else {
                z += stepZ
                tMaxZ += tDeltaZ
            }

            pos.set(x, y, z)
            val shape = level.getBlockState(pos).getCollisionShape(level, pos)
            if (shape.isEmpty) {
                if (x == tx && y == ty && z == tz) return true
                continue
            }
            for (box in shape.toAabbs()) {
                if (segmentHitsBox(from, dx, dy, dz, box.move(x.toDouble(), y.toDouble(), z.toDouble()))) return false
            }
            if (x == tx && y == ty && z == tz) return true
        }
        return true
    }

    /** Whether the segment [from] + [dx, dy, dz] * t, t in [0, 1], touches [box]. */
    private fun segmentHitsBox(from: Vec3, dx: Double, dy: Double, dz: Double, box: AABB): Boolean {
        var tMin = 0.0
        var tMax = 1.0
        if (abs(dx) < 1.0e-12) {
            if (from.x < box.minX || from.x > box.maxX) return false
        } else {
            var t1 = (box.minX - from.x) / dx
            var t2 = (box.maxX - from.x) / dx
            if (t1 > t2) {
                val t = t1
                t1 = t2
                t2 = t
            }
            tMin = maxOf(tMin, t1)
            tMax = minOf(tMax, t2)
            if (tMin > tMax) return false
        }
        if (abs(dy) < 1.0e-12) {
            if (from.y < box.minY || from.y > box.maxY) return false
        } else {
            var t1 = (box.minY - from.y) / dy
            var t2 = (box.maxY - from.y) / dy
            if (t1 > t2) {
                val t = t1
                t1 = t2
                t2 = t
            }
            tMin = maxOf(tMin, t1)
            tMax = minOf(tMax, t2)
            if (tMin > tMax) return false
        }
        if (abs(dz) < 1.0e-12) {
            if (from.z < box.minZ || from.z > box.maxZ) return false
        } else {
            var t1 = (box.minZ - from.z) / dz
            var t2 = (box.maxZ - from.z) / dz
            if (t1 > t2) {
                val t = t1
                t1 = t2
                t2 = t
            }
            tMin = maxOf(tMin, t1)
            tMax = minOf(tMax, t2)
            if (tMin > tMax) return false
        }
        return true
    }
}
