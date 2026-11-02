package dev.cd.adrift.config.gui

import com.mojang.blaze3d.platform.InputConstants
import foo.starred.cascade.events.impl.MouseEvent
import foo.starred.cascade.graphics.geometry.CascadeGeometricColor
import foo.starred.cascade.primitives.base.impl.IPrimitiveElement
import foo.starred.cascade.primitives.impl.TextPrimitive
import foo.starred.cascade.primitives.impl.TextPrimitive.Companion.text
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

fun label(string: String, font: Montserrat, size: Float, color: Int) = text {
    text = Component.literal(string)
    wrapper = font
    textSize = size
    shadow = false
    this.color = CascadeGeometricColor(color)
}

fun TextPrimitive.setText(string: String) {
    text = Component.literal(string)
}

/**
 * Where to put Cascade text so it lines up with CSS text whose line box starts at [lineTop]: CSS lays out from
 * the line box (Montserrat's ascender is 0.968 em above the baseline), Cascade from a baseline 0.8585 em down.
 */
fun textTop(lineTop: Float, size: Float) = lineTop + 0.11f * size

fun Montserrat.width(string: String, size: Float) = width(Component.literal(string).visualOrderText, size)

fun IPrimitiveElement<*>.onLeftClick(sound: Boolean = true, action: (x: Float, y: Float) -> Unit) {
    on<MouseEvent.Press> {
        // Value differs per version (GLFW vs SDL), so use Mojang's constant rather than a hardcoded one
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return@on
        action(x.toFloat(), y.toFloat())
        if (sound) Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
        cancel()
    }
}
