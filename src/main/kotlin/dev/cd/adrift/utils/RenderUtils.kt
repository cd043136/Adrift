package dev.cd.adrift.utils

//? if >=26.2 {
import com.odtheking.odin.events.RenderExtractEvent
//?} else
//import dev.cd.adrift.utils.RenderExtractEvent
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.CustomRenderType
import net.minecraft.client.Minecraft
//? if <26.2
//import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.VoxelShape
import kotlin.math.sqrt

object RenderUtils {
    fun RenderExtractEvent.drawFilledQuad(
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

        drawQuads(
            listOf(
                Vec3(start.x + px, start.y, start.z + pz),
                Vec3(start.x - px, start.y, start.z - pz),
                Vec3(end.x - px, end.y, end.z - pz),
                Vec3(end.x + px, end.y, end.z + pz),
            ),
            colour, depth
        )
    }

    /**
     * Shape-accurate block tint boxes: the block's own collision boxes at `(x, y, z)`, grown by [epsilon] so they
     * don't z-fight the block faces. Draw each with a filled box so stairs/slabs/fences hug their geometry.
     */
    fun tintBoxes(shape: VoxelShape, x: Int, y: Int, z: Int, epsilon: Double = 0.002): List<AABB> =
        shape.toAabbs().map { it.move(x.toDouble(), y.toDouble(), z.toDouble()).inflate(epsilon) }

    /** Every four [vertices] make one quad. */
    fun RenderExtractEvent.drawQuads(vertices: List<Vec3>, colour: Color, depth: Boolean = false) {
        if (vertices.size < 4) return
        //? if >=26.2 {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
        val opaque = colour.alphaFloat >= 0.999f
        val renderType = when {
            depth && opaque -> CustomRenderType.QUADS_OPAQUE
            depth -> CustomRenderType.QUADS_TRANSLUCENT
            opaque -> CustomRenderType.QUADS_ESP
            else -> CustomRenderType.QUADS_TRANSLUCENT_ESP
        }
        //?} else {
        /*val camera = Minecraft.getInstance().gameRenderer.mainCamera.position()
        val renderType = if (depth) RenderTypes.debugFilledBox() else CustomRenderType.QUADS_ESP
        *///?}
        val rgba = colour.rgba
        val cx = camera.x
        val cy = camera.y
        val cz = camera.z

        //? if >=26.2 {
        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), renderType) { pose, buffer ->
        //?} else {
        /*val buffer = context.bufferSource().getBuffer(renderType)
        val pose = context.poseStack().last()
        run {
        *///?}
            for (v in vertices) {
                buffer.addVertex(pose, (v.x - cx).toFloat(), (v.y - cy).toFloat(), (v.z - cz).toFloat()).setColor(rgba)
            }
        }
    }
}
