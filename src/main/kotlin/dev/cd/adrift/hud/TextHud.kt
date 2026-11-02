package dev.cd.adrift.hud

import dev.cd.adrift.config.Config
import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * Text drawn by a [TextHud], recorded while the content is laid out so the HUD knows its size before it is placed.
 * Coordinates are relative to the element's top-left corner.
 */
class HudCanvas internal constructor() {
    private class Line(val text: Component, val x: Int, val y: Int, val color: Int)

    private val lines = ArrayList<Line>()

    fun text(text: String, x: Int, y: Int, color: Color = dev.cd.adrift.utils.Colors.WHITE) = text(Component.literal(text), x, y, color)

    fun text(text: Component, x: Int, y: Int, color: Color = dev.cd.adrift.utils.Colors.WHITE) {
        lines += Line(text, x, y, color.rgba)
    }

    fun width(text: String) = mc.font.width(text)

    fun width(text: Component) = mc.font.width(text)

    internal fun clear() = lines.clear()

    internal fun draw(graphics: GuiGraphicsExtractor) {
        for (line in lines) graphics.text(mc.font, line.text, line.x, line.y, line.color, true)
    }
}

/**
 * A HUD element made of text. [content] draws into the canvas and returns its width and height; (0, 0) hides it.
 * With `preview` it should draw example content for the HUD editor.
 */
class TextHud(
    name: String,
    layout: Config.HudLayout,
    private val shown: () -> Boolean,
    private val content: HudCanvas.(preview: Boolean) -> Pair<Int, Int>,
) : HudModule(name, layout) {
    private val canvas = HudCanvas()

    override fun enabled() = shown()

    override fun prepare(preview: Boolean): Boolean {
        canvas.clear()
        val (w, h) = canvas.content(preview)
        width = w.toFloat()
        height = h.toFloat()
        return w > 0 && h > 0
    }

    override fun draw(graphics: GuiGraphicsExtractor) = canvas.draw(graphics)
}
