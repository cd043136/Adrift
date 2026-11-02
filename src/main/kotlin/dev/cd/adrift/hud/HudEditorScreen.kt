package dev.cd.adrift.hud

import com.mojang.blaze3d.platform.InputConstants
import dev.cd.adrift.config.Config
import dev.cd.adrift.config.gui.ConfigScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Shows every [HudModule] (with placeholder content) so it can be dragged around and resized by scrolling over
 * it. Right-click resets one. Layouts are saved when the screen closes.
 */
class HudEditorScreen(private val returnToConfig: Boolean = false) : Screen(Component.literal("Edit HUD")) {
    private companion object {
        const val SCALE_STEP = 0.1f
        const val SCRIM = 0x30000000
        const val OUTLINE = 0x60FFFFFF
        const val OUTLINE_ACTIVE = 0xFFC77DFF.toInt()
        const val TEXT = 0xFFF4F2F8.toInt()
        const val MUTED = 0xFFA9A5B8.toInt()
    }

    private var dragging: HudModule? = null
    private var grabX = 0f
    private var grabY = 0f

    private fun moduleAt(mouseX: Double, mouseY: Double): HudModule? = HudManager.all.lastOrNull {
        val x = it.x(width)
        val y = it.y(height)
        mouseX >= x && mouseX < x + it.scaledWidth && mouseY >= y && mouseY < y + it.scaledHeight
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        graphics.fill(0, 0, width, height, SCRIM)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        graphics.centeredText(font, "Drag to move · Scroll to resize · Right-click to reset · Esc to save", width / 2, height / 2 - 4, MUTED)

        val hovered = dragging ?: moduleAt(mouseX.toDouble(), mouseY.toDouble())
        for (module in HudManager.all) {
            module.prepare(preview = true)
            module.render(graphics)

            val x = module.x(width).toInt()
            val y = module.y(height).toInt()
            val w = module.scaledWidth.roundToInt()
            val h = module.scaledHeight.roundToInt()
            graphics.outline(x - 1, y - 1, w + 2, h + 2, if (module === hovered) OUTLINE_ACTIVE else OUTLINE)

            val label = module.name + (if (module.enabled()) "" else " (off)") + " · ${(module.layout.scale * 100).roundToInt()}%"
            // Above the element, or below it when it's against the top edge
            val labelY = if (y >= 12) y - 11 else y + h + 3
            graphics.text(font, label, x, labelY, TEXT)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, isDoubleClick: Boolean): Boolean {
        val module = moduleAt(event.x(), event.y()) ?: return super.mouseClicked(event, isDoubleClick)
        // Mojang's constants, since the raw button codes differ per version (GLFW vs SDL)
        when (event.button()) {
            InputConstants.MOUSE_BUTTON_LEFT -> {
                dragging = module
                grabX = event.x().toFloat() - module.x(width)
                grabY = event.y().toFloat() - module.y(height)
            }
            InputConstants.MOUSE_BUTTON_RIGHT -> module.layout.reset()
        }
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val module = dragging ?: return super.mouseDragged(event, dx, dy)
        module.moveTo(event.x().toFloat() - grabX, event.y().toFloat() - grabY, width, height)
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (dragging == null) return super.mouseReleased(event)
        dragging = null
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val module = dragging ?: moduleAt(mouseX, mouseY) ?: return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        if (scrollY == 0.0) return true
        // Scale around the element's centre so it doesn't drift towards a corner
        val centreX = module.x(width) + module.scaledWidth / 2
        val centreY = module.y(height) + module.scaledHeight / 2
        module.layout.scale = ((module.layout.scale + SCALE_STEP * sign(scrollY).toFloat()) * 10).roundToInt() / 10f
        module.moveTo(centreX - module.scaledWidth / 2, centreY - module.scaledHeight / 2, width, height)
        if (dragging === module) {
            grabX = mouseX.toFloat() - module.x(width)
            grabY = mouseY.toFloat() - module.y(height)
        }
        return true
    }

    /** Opens on the next tick, like [foo.starred.cascade.screen.CascadeScreen.open], so a closing chat screen doesn't replace it. */
    fun open() {
        val client = Minecraft.getInstance()
        client.schedule {
            //? if >=26.2 {
            client.gui.setScreen(this)
            //?} else
            //client.setScreen(this)
        }
    }

    override fun isPauseScreen() = false

    override fun removed() {
        Config.save()
        Config.tick(force = true)
        super.removed()
    }

    override fun onClose() {
        super.onClose()
        if (returnToConfig) ConfigScreen().open()
    }
}
