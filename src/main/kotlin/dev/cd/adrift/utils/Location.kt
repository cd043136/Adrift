package dev.cd.adrift.utils

import dev.cd.adrift.events.EventBus
import dev.cd.adrift.events.LevelEvent
import net.minecraft.ChatFormatting
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket

/**
 * Which Skyblock island we're on, read from the tab list's `Area:` line as Hypixel sends it (no polling).
 * Every island change is a new level, which resets it until the tab list arrives.
 */
object Location {
    private const val AREA_PREFIX = "Area: "
    /** Cheap test on the raw name, which can have formatting codes in it, before stripping them. */
    private const val AREA_WORD = "Area"
    private const val RIFT = "The Rift"

    @Volatile
    var area: String? = null
        private set

    /** For testing in singleplayer: treat any world as the Rift. Not saved. */
    @Volatile
    var forceRift = false

    val inRift: Boolean get() = forceRift || area == RIFT

    fun register() {
        EventBus.on<LevelEvent.Load> { area = null }
        EventBus.onReceive<ClientboundPlayerInfoUpdatePacket> {
            // Known until the next level, which resets it
            if (area != null) return@onReceive
            if (!actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME)) return@onReceive
            for (entry in entries()) {
                val name = entry.displayName()?.string ?: continue
                if (!name.contains(AREA_WORD)) continue
                val plain = (ChatFormatting.stripFormatting(name) ?: name).trim()
                if (plain.startsWith(AREA_PREFIX)) area = plain.removePrefix(AREA_PREFIX).trim()
            }
        }
    }
}
