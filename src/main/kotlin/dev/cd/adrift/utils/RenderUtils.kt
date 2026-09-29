package dev.cd.adrift.utils

import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.CustomRenderType
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.VoxelShape
import kotlin.math.sqrt

object RenderUtils {
    fun RenderEvent.Extract.drawFilledQuad(
        start: Vec3,
        end: Vec3,
        width: Double,
        colour: Color,
        depth: Boolean = false
    ) {
        val dx = start.x - end.x
        val dz = start.z - end.z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance <= 1.0e-12 || width <= 0.0) return

        val scale = (width / 2.0) / distance
        val px = -dz * scale
        val pz = dx * scale

        emitQuad(
            colour, depth,
            start.x + px, start.y, start.z + pz,
            start.x - px, start.y, start.z - pz,
            end.x - px, end.y, end.z - pz,
            end.x + px, end.y, end.z + pz
        )
    }

    fun RenderEvent.Extract.drawFlatSquare(
        centerX: Double,
        centerZ: Double,
        y: Double,
        size: Double,
        colour: Color,
        depth: Boolean = false
    ) {
        if (size <= 0.0) return
        val half = size / 2.0
        val x0 = centerX - half
        val x1 = centerX + half
        val z0 = centerZ - half
        val z1 = centerZ + half
        emitQuad(colour, depth, x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0)
    }

    /**
     * Shape-accurate block tint boxes: the block's own collision boxes at `(x, y, z)`, grown by [epsilon] so they
     * don't z-fight the block faces. Draw each with a filled box so stairs/slabs/fences hug their geometry.
     */
    fun tintBoxes(shape: VoxelShape, x: Int, y: Int, z: Int, epsilon: Double = 0.002): List<AABB> =
        shape.toAabbs().map { it.move(x.toDouble(), y.toDouble(), z.toDouble()).inflate(epsilon) }

    private fun RenderEvent.Extract.emitQuad(
        colour: Color,
        depth: Boolean,
        x0: Double, y0: Double, z0: Double,
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        x3: Double, y3: Double, z3: Double
    ) {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera.position()
        val renderType = if (depth) RenderTypes.debugFilledBox() else CustomRenderType.QUADS_ESP
        val buffer = context.bufferSource().getBuffer(renderType)
        val pose = context.poseStack().last()
        val rgba = colour.rgba
        val cx = camera.x
        val cy = camera.y
        val cz = camera.z

        fun vertex(x: Double, y: Double, z: Double) {
            buffer.addVertex(pose, (x - cx).toFloat(), (y - cy).toFloat(), (z - cz).toFloat()).setColor(rgba)
        }

        vertex(x0, y0, z0)
        vertex(x1, y1, z1)
        vertex(x2, y2, z2)
        vertex(x3, y3, z3)
    }
}
