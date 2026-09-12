package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.handlers.schedule
import com.odtheking.odin.utils.renderPos
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.texture
import dev.cd.adrift.events.SlayerEvent
import dev.cd.adrift.utils.Category
import dev.cd.adrift.utils.RenderUtils.drawFilledQuad
import dev.cd.adrift.utils.SlayerUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

object VampireHelper : Module(
    "Vampire Helper",
    category = Category.RIFT,
    description = "T5 vampire slayer qol"
) {
    private val ichorLine by BooleanSetting(
        "Ichor Line",
        default = false,
        desc = "Draws line from boss to ichor"
    )
    private val ichorLineColour by ColorSetting(
        "Ichor Line Colour",
        default = Color("ffffff66"),
        allowAlpha = true,
        desc = "Ichor line colour"
    )
    private val otherBoss by BooleanSetting(
        "On Other Boss",
        default = false,
        desc = "Also does highlight on someone else's vampire"
    )
    private val announceSpawn by BooleanSetting(
        "Announce Spawn",
        default = false,
        desc = "Send coords when your boss spawns"
    )

    const val ICHOR_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTYxNTg4ODAwMDU1MywKICAicHJvZmlsZUlkIiA6ICI5ZDIyZGRhOTVmZGI0MjFmOGZhNjAzNTI1YThkZmE4ZCIsCiAgInByb2ZpbGVOYW1lIiA6ICJTYWZlRHJpZnQ0OCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jMDM0MDkyM2E2ZGU0ODI1YTE3NjgxM2QxMzM1MDNlZmYxODZkYjA4OTZlMzJiNjcwNDkyOGMyYTJiZjY4NDIyIgogICAgfQogIH0KfQ=="
    private val ichors: MutableMap<Entity, MutableSet<ArmorStand>> = mutableMapOf()

    init {
        // TODO: check ichor spawn timing based on armourstand timer (ICHOR 0.1s etc)
        ClientEntityEvents.ENTITY_LOAD.register { entity, _ -> onEntityLoad(entity) }
        ClientEntityEvents.ENTITY_UNLOAD.register { entity, _ -> onEntityUnload(entity) }

        on<RenderEvent.Extract> {
            if (!ichorLine || ichors.isEmpty()) return@on

            val boss = bossPriority() ?: return@on
            val chosenIchor = ichors[boss]?.takeIf { it.isNotEmpty() } ?: return@on
            val player = mc.player ?: return@on

            val bossPos = boss.renderPos
            val playerPos = player.renderPos

            val playerDx = playerPos.x - bossPos.x
            val playerDz = playerPos.z - bossPos.z
            val playerDistanceSq = playerDx * playerDx + playerDz * playerDz

            for (ichor in chosenIchor) {
                // ts happened once somehow so
                if (!ichor.isAlive) continue

                val ichorPos = ichor.renderPos
                val ichorDx = ichorPos.x - bossPos.x
                val ichorDz = ichorPos.z - bossPos.z
                val ichorDistanceSq = ichorDx * ichorDx + ichorDz * ichorDz

                if (ichorDistanceSq <= 1.0e-12) continue

                val ichorDistance = sqrt(ichorDistanceSq)
                val playerDistance = sqrt(playerDistanceSq) + 2.0
                val lineLength = maxOf(playerDistance, ichorDistance)
                val ux = ichorDx / ichorDistance
                val uz = ichorDz / ichorDistance
                val y = minOf(playerPos.y, ichorPos.y) - 0.2

                val width = when {
                    ichorDistance <= 2.0 -> 0.9
                    ichorDistance >= 8.0 -> 0.3
                    else -> 0.9 - (ichorDistance - 2.0) / 6.0 * 0.6
                }
                val endA = Vec3(
                    bossPos.x + ux * lineLength,
                    y,
                    bossPos.z + uz * lineLength
                )
                val endB = Vec3(
                    bossPos.x - ux * lineLength,
                    y,
                    bossPos.z - uz * lineLength
                )

                drawFilledQuad(
                    endA,
                    endB,
                    width,
                    ichorLineColour
                )
            }
        }

        on<SlayerEvent.Spawn> {
            if (LocationUtils.currentArea != Island.Rift) return@on

            ichors.clear()
            if (!announceSpawn) return@on

            val pos = boss.blockPosition()
            sendCommand("pc x: ${pos.x}, y: ${pos.y}, z: ${pos.z} ; Boss Spawned")
        }
    }

    private fun onEntityLoad(entity: Entity) {
        if (LocationUtils.currentArea != Island.Rift) return

        val boss = bossPriority() ?: return
        if (entity !is ArmorStand || entity.distanceTo(boss) > 6) return

        schedule(1, serverTick = true) {
            val its = entity.getItemBySlot(EquipmentSlot.HEAD)
            if (its.texture != ICHOR_TEXTURE) return@schedule

            ichors.getOrPut(boss) { mutableSetOf() }.add(entity)
        }
    }

    private fun bossPriority(): Entity? {
        return SlayerUtils.boss ?: SlayerUtils.lootshareBoss?.takeIf { otherBoss }
    }

    private fun onEntityUnload(entity: Entity) {
        ichors.remove(entity)
        ichors.values.forEach { it.remove(entity) }
    }
}
