package dev.cd.adrift.utils

import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

val mc: Minecraft get() = Minecraft.getInstance()

const val CHAT_PREFIX = "§5Adrift §8»§r "

/** Shows [message] in chat, only to us. Safe from any thread. */
fun modMessage(message: Any?, prefix: String = CHAT_PREFIX) = modMessage(Component.literal(message.toString()), prefix)

fun modMessage(message: Component, prefix: String = CHAT_PREFIX) {
    val line = Component.literal(prefix).append(message)
    mc.execute {
        //? if >=26.2 {
        mc.gui.hud.chat.addClientSystemMessage(line)
        //?} else
        //mc.gui.chat.addClientSystemMessage(line)
    }
}

/** Sends `/[command]` (without the slash) to the server. Safe from any thread. */
fun sendCommand(command: String) {
    mc.execute { mc.player?.connection?.sendCommand(command) }
}

/** Position interpolated to this frame. */
val Entity.renderPos: Vec3
    get() {
        val t = mc.deltaTracker.getGameTimeDeltaPartialTick(true).toDouble()
        return Vec3(xo + (x - xo) * t, yo + (y - yo) * t, zo + (z - zo) * t)
    }

/** Bounding box interpolated to this frame. */
val Entity.renderBoundingBox: AABB
    get() {
        val t = mc.deltaTracker.getGameTimeDeltaPartialTick(true).toDouble()
        return boundingBox.move((xo - x) * (1 - t), (yo - y) * (1 - t), (zo - z) * (1 - t))
    }

/** The base64 skin texture of a player head, if this is one. */
val ItemStack.texture: String?
    get() = get(DataComponents.PROFILE)?.partialProfile()?.properties()?.get("textures")?.firstOrNull()?.value()
