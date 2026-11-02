package dev.cd.adrift.hud

import dev.cd.adrift.config.Config
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A HUD element the [HudEditorScreen] can move and resize. Subclasses lay out their content at the origin in
 * [prepare] and draw it in [draw]; this class places it on screen from [layout] and applies the scale.
 */
abstract class HudModule(val name: String, val layout: Config.HudLayout) : HudElement {
    /** Unscaled size of the content, set by [prepare]. */
    var width = 0f
        protected set
    var height = 0f
        protected set

    abstract fun enabled(): Boolean

    /**
     * Updates the content and its [width]/[height]. Returns false if there is nothing to show. With [preview],
     * it should show placeholder content instead, so the element can be placed in the editor.
     */
    abstract fun prepare(preview: Boolean): Boolean

    /** Draws the content prepared by [prepare] with its top-left corner at (0, 0). */
    abstract fun draw(graphics: GuiGraphicsExtractor)

    val scaledWidth get() = width * layout.scale
    val scaledHeight get() = height * layout.scale

    fun x(screenWidth: Int) = EDGE + layout.x * free(screenWidth.toFloat(), scaledWidth)
    fun y(screenHeight: Int) = EDGE + layout.y * free(screenHeight.toFloat(), scaledHeight)

    /** Moves the top-left corner to ([x], [y]), kept on screen. */
    fun moveTo(x: Float, y: Float, screenWidth: Int, screenHeight: Int) {
        layout.x = fraction(x, screenWidth.toFloat(), scaledWidth)
        layout.y = fraction(y, screenHeight.toFloat(), scaledHeight)
    }

    fun render(graphics: GuiGraphicsExtractor) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x(graphics.guiWidth()), y(graphics.guiHeight()))
        pose.scale(layout.scale, layout.scale)
        draw(graphics)
        pose.popMatrix()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        val client = Minecraft.getInstance()
        //? if >=26.2 {
        val screen = client.gui.screen()
        //?} else
        //val screen = client.screen
        // The editor draws its own previews on top
        if (screen !is HudEditorScreen && enabled() && prepare(preview = false)) render(graphics)
    }

    private companion object {
        /** Gap kept between the element and the screen edges. */
        const val EDGE = 4f

        fun free(screen: Float, size: Float) = (screen - size - EDGE * 2).coerceAtLeast(0f)

        fun fraction(pos: Float, screen: Float, size: Float): Float {
            val free = free(screen, size)
            return if (free <= 0f) 0f else ((pos - EDGE) / free).coerceIn(0f, 1f)
        }
    }
}
