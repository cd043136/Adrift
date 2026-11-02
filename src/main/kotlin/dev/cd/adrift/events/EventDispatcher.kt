package dev.cd.adrift.events

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel

/** Turns Fabric callbacks into [EventBus] events. */
object EventDispatcher {
    private var level: ClientLevel? = null

    fun register() {
        ClientTickEvents.END_LEVEL_TICK.register { _ -> TickEvent.End.post() }

        // Server switches swap the level without reconnecting, so compare the level itself
        ClientTickEvents.END_CLIENT_TICK.register { client -> checkLevel(client) }

        //? if >=26.2 {
        LevelRenderEvents.COLLECT_SUBMITS.register { context -> RenderWorldEvent(context).post() }
        //?} else
        //LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register { context -> RenderWorldEvent(context).post() }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            if (overlay) return@register true
            val plain = ChatFormatting.stripFormatting(message.string) ?: message.string
            !ChatEvent(plain, message).post()
        }
    }

    private fun checkLevel(client: Minecraft) {
        val current = client.level
        if (current === level) return
        if (level != null) LevelEvent.Unload.post()
        level = current
        if (current != null) LevelEvent.Load.post()
    }
}
