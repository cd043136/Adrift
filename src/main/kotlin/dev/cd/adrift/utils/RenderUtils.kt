package dev.cd.adrift.utils

import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.CustomRenderType
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.phys.Vec3
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

        val client = Minecraft.getInstance()
        val camera = client.gameRenderer.mainCamera.position()

        val renderType = if (depth) RenderTypes.debugFilledBox() else CustomRenderType.QUADS_ESP
        val buffer = context.bufferSource().getBuffer(renderType)

        val rgba = colour.rgba
        val pose = context.poseStack().last()

        val cx = camera.x
        val cy = camera.y
        val cz = camera.z

        fun vertex(x: Double, y: Double, z: Double) {
            buffer.addVertex(
                pose,
                (x - cx).toFloat(),
                (y - cy).toFloat(),
                (z - cz).toFloat()
            ).setColor(rgba)
        }

        vertex(start.x + px, start.y, start.z + pz)
        vertex(start.x - px, start.y, start.z - pz)
        vertex(end.x - px, end.y, end.z - pz)
        vertex(end.x + px, end.y, end.z + pz)
    }
}
