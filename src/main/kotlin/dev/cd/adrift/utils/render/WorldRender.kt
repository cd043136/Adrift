package dev.cd.adrift.utils.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.cd.adrift.events.RenderWorldEvent
import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.mc
import net.minecraft.client.Camera
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.rendertype.AdriftRenderTypes
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/** How a box is drawn. */
enum class BoxStyle { FILLED, OUTLINE, FILLED_OUTLINE }

/**
 * Collects world-space geometry, relative to the camera, and submits it in one draw per render type. Building a
 * frame's shapes into one batch instead of one submission per shape keeps big patterns (Mania Helper draws
 * hundreds of boxes) cheap. Coordinates are absolute; the camera offset is applied while recording.
 */
class GeometryBatch internal constructor(internal val lines: Boolean) {
    private var data = FloatArray(256)
    private var colors = IntArray(64)
    private var count = 0
    private var camX = 0.0
    private var camY = 0.0
    private var camZ = 0.0

    internal fun begin(camera: Vec3) {
        count = 0
        camX = camera.x
        camY = camera.y
        camZ = camera.z
    }

    val isEmpty get() = count == 0

    // Lines store x, y, z, nx, ny, nz, width per vertex; quads store x, y, z
    private val stride get() = if (lines) 7 else 3

    private fun ensure() {
        if ((count + 1) * stride > data.size) data = data.copyOf(data.size * 2)
        if (count + 1 > colors.size) colors = colors.copyOf(colors.size * 2)
    }

    private fun put(x: Double, y: Double, z: Double, rgba: Int) {
        ensure()
        val i = count * stride
        data[i] = (x - camX).toFloat()
        data[i + 1] = (y - camY).toFloat()
        data[i + 2] = (z - camZ).toFloat()
        colors[count++] = rgba
    }

    // ---- Quads ----

    fun quad(
        color: Color,
        x0: Double, y0: Double, z0: Double,
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        x3: Double, y3: Double, z3: Double,
    ) {
        check(!lines)
        val c = color.rgba
        put(x0, y0, z0, c)
        put(x1, y1, z1, c)
        put(x2, y2, z2, c)
        put(x3, y3, z3, c)
    }

    /** A quad with its own `0xAARRGGBB` colour per corner, blended across it. */
    fun shadedQuad(
        x0: Double, y0: Double, z0: Double, c0: Int,
        x1: Double, y1: Double, z1: Double, c1: Int,
        x2: Double, y2: Double, z2: Double, c2: Int,
        x3: Double, y3: Double, z3: Double, c3: Int,
    ) {
        check(!lines)
        put(x0, y0, z0, c0)
        put(x1, y1, z1, c1)
        put(x2, y2, z2, c2)
        put(x3, y3, z3, c3)
    }

    fun box(aabb: AABB, color: Color) {
        val x0 = aabb.minX
        val y0 = aabb.minY
        val z0 = aabb.minZ
        val x1 = aabb.maxX
        val y1 = aabb.maxY
        val z1 = aabb.maxZ
        quad(color, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1) // bottom
        quad(color, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0) // top
        quad(color, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0) // north
        quad(color, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1) // south
        quad(color, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0) // west
        quad(color, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1) // east
    }

    /** A horizontal strip [width] wide from [start] to [end], at [start]'s height. */
    fun flatLine(start: Vec3, end: Vec3, width: Double, color: Color) {
        val dx = start.x - end.x
        val dz = start.z - end.z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance <= 1.0e-12 || width <= 0.0) return
        val scale = (width / 2.0) / distance
        val px = -dz * scale
        val pz = dx * scale
        quad(
            color,
            start.x + px, start.y, start.z + pz,
            start.x - px, start.y, start.z - pz,
            end.x - px, end.y, end.z - pz,
            end.x + px, end.y, end.z + pz,
        )
    }

    // ---- Lines ----

    fun line(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, color: Color, width: Float) {
        check(lines)
        val nx = (x1 - x0).toFloat()
        val ny = (y1 - y0).toFloat()
        val nz = (z1 - z0).toFloat()
        lineVertex(x0, y0, z0, nx, ny, nz, color.rgba, width)
        lineVertex(x1, y1, z1, nx, ny, nz, color.rgba, width)
    }

    /** A connected line through [points]. */
    fun polyline(points: List<Vec3>, color: Color, width: Float) {
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            line(a.x, a.y, a.z, b.x, b.y, b.z, color, width)
        }
    }

    private fun lineVertex(x: Double, y: Double, z: Double, nx: Float, ny: Float, nz: Float, rgba: Int, width: Float) {
        put(x, y, z, rgba)
        val i = (count - 1) * stride
        data[i + 3] = nx
        data[i + 4] = ny
        data[i + 5] = nz
        data[i + 6] = width
    }

    fun boxEdges(aabb: AABB, color: Color, width: Float) {
        val x0 = aabb.minX
        val y0 = aabb.minY
        val z0 = aabb.minZ
        val x1 = aabb.maxX
        val y1 = aabb.maxY
        val z1 = aabb.maxZ
        // Along x
        line(x0, y0, z0, x1, y0, z0, color, width)
        line(x0, y0, z1, x1, y0, z1, color, width)
        line(x0, y1, z0, x1, y1, z0, color, width)
        line(x0, y1, z1, x1, y1, z1, color, width)
        // Along y
        line(x0, y0, z0, x0, y1, z0, color, width)
        line(x0, y0, z1, x0, y1, z1, color, width)
        line(x1, y0, z0, x1, y1, z0, color, width)
        line(x1, y0, z1, x1, y1, z1, color, width)
        // Along z
        line(x0, y0, z0, x0, y0, z1, color, width)
        line(x0, y1, z0, x0, y1, z1, color, width)
        line(x1, y0, z0, x1, y0, z1, color, width)
        line(x1, y1, z0, x1, y1, z1, color, width)
    }

    internal fun emit(pose: PoseStack.Pose, buffer: VertexConsumer) {
        for (v in 0 until count) {
            val i = v * stride
            val vertex = buffer.addVertex(pose, data[i], data[i + 1], data[i + 2]).setColor(colors[v])
            if (lines) vertex.setNormal(pose, data[i + 3], data[i + 4], data[i + 5]).setLineWidth(data[i + 6])
        }
    }

    /** Copies this batch's geometry into [target], reusing its arrays when they are big enough. */
    internal fun copyInto(target: GeometryBatch) {
        check(target.lines == lines)
        val size = count * stride
        if (target.data.size < size) target.data = FloatArray(maxOf(size, target.data.size * 2))
        if (target.colors.size < count) target.colors = IntArray(maxOf(count, target.colors.size * 2))
        System.arraycopy(data, 0, target.data, 0, size)
        System.arraycopy(colors, 0, target.colors, 0, count)
        target.count = count
    }
}

private val scratchQuads = GeometryBatch(lines = false)
private val scratchLines = GeometryBatch(lines = true)

internal val camera: Camera
    //? if >=26.2 {
    get() = mc.gameRenderer.mainCamera()
    //?} else
    //get() = mc.gameRenderer.mainCamera

internal val cameraPos: Vec3 get() = camera.position()

//? if >=26.2 {
/** Copies handed to submissions, back here once drawn, so a busy frame doesn't allocate its geometry afresh. */
private val frozenQuads = ArrayDeque<GeometryBatch>()
private val frozenLines = ArrayDeque<GeometryBatch>()
private const val MAX_FROZEN = 16
//?}

private fun RenderWorldEvent.submit(type: RenderType, batch: GeometryBatch) {
    if (batch.isEmpty) return
    //? if >=26.2 {
    // Submissions are drawn later in the frame, so hand over a copy rather than the reused scratch batch. Each is
    // drawn once, after which its copy can take the next submission's geometry.
    val pool = if (batch.lines) frozenLines else frozenQuads
    val frozen = pool.removeLastOrNull() ?: GeometryBatch(batch.lines)
    batch.copyInto(frozen)
    context.submitNodeCollector().submitCustomGeometry(context.poseStack(), type) { pose, buffer ->
        frozen.emit(pose, buffer)
        if (pool.size < MAX_FROZEN) pool.addLast(frozen)
    }
    //?} else
    //batch.emit(context.poseStack().last(), context.bufferSource().getBuffer(type))
}

/** Translucent quads, hidden behind blocks with [depth], drawn through them without. */
fun RenderWorldEvent.quads(depth: Boolean, build: GeometryBatch.() -> Unit) {
    scratchQuads.begin(cameraPos)
    scratchQuads.build()
    submit(if (depth) AdriftRenderTypes.FILL else AdriftRenderTypes.FILL_ESP, scratchQuads)
}

/** Opaque quads that write depth, hidden behind blocks; alpha is ignored and nothing needs sorting. */
fun RenderWorldEvent.opaqueQuads(build: GeometryBatch.() -> Unit) {
    scratchQuads.begin(cameraPos)
    scratchQuads.build()
    submit(AdriftRenderTypes.SOLID, scratchQuads)
}

/** Additive quads, hidden behind blocks: each adds its colour, scaled by its alpha, to what's behind it. */
fun RenderWorldEvent.glow(build: GeometryBatch.() -> Unit) {
    scratchQuads.begin(cameraPos)
    scratchQuads.build()
    submit(AdriftRenderTypes.GLOW, scratchQuads)
}

/** Depth-tested lines. */
fun RenderWorldEvent.lines(build: GeometryBatch.() -> Unit) {
    scratchLines.begin(cameraPos)
    scratchLines.build()
    submit(RenderTypes.linesTranslucent(), scratchLines)
}

fun RenderWorldEvent.drawStyledBoxes(boxes: List<Pair<AABB, Color>>, style: BoxStyle, depth: Boolean = true) {
    if (boxes.isEmpty()) return
    if (style != BoxStyle.OUTLINE) quads(depth) {
        for ((aabb, color) in boxes) {
            if (style == BoxStyle.FILLED) box(aabb, color) else box(aabb.inflate(5.0e-5), color.multiplyAlpha(0.5f))
        }
    }
    if (style != BoxStyle.FILLED) lines { for ((aabb, color) in boxes) boxEdges(aabb, color, 3f) }
}

private const val FULL_BRIGHT = 0xF000F0

/** Depth-tested [text] centred over [pos] and facing the camera; [scale] 1 is name tag size. */
fun RenderWorldEvent.text(text: String, pos: Vec3, scale: Float = 1f) {
    val line = Component.literal(text).visualOrderText
    val x = -mc.font.width(line) / 2f
    val size = 0.025f * scale
    val offset = pos.subtract(cameraPos)

    val stack = context.poseStack()
    stack.pushPose()
    stack.translate(offset.x, offset.y, offset.z)
    stack.last().rotate(camera.rotation())
    stack.scale(size, -size, size)
    val mode = Font.DisplayMode.POLYGON_OFFSET
    //? if >=26.2 {
    context.submitNodeCollector().submitText(stack, x, 0f, line, true, mode, FULL_BRIGHT, -1, 0, 0)
    //?} else
    //mc.font.drawInBatch(line, x, 0f, -1, true, stack.last().pose(), context.bufferSource(), mode, 0, FULL_BRIGHT)
    stack.popPose()
}
