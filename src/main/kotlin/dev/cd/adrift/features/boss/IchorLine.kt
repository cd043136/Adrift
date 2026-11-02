package dev.cd.adrift.features.boss

import dev.cd.adrift.events.LevelEvent
import dev.cd.adrift.events.RenderWorldEvent
import dev.cd.adrift.events.SlayerEvent
import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module
import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.Location
import dev.cd.adrift.utils.SlayerUtils
import dev.cd.adrift.utils.render.quads
import dev.cd.adrift.utils.renderPos
import dev.cd.adrift.utils.schedule
import dev.cd.adrift.utils.texture
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

object IchorLine : Module(
    "Ichor Line",
    Category.BOSS,
    "Draws a line from the boss through its ichor so you don't miss it."
) {
    private val lineColour by color("Line Colour", Color("ffffff66"), "Colour of the line.")
    private val otherBoss by bool("On Other Boss", false, "Also draws it for a boss you're helping someone with.")

    const val ICHOR_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTYxNTg4ODAwMDU1MywKICAicHJvZmlsZUlkIiA6ICI5ZDIyZGRhOTVmZGI0MjFmOGZhNjAzNTI1YThkZmE4ZCIsCiAgInByb2ZpbGVOYW1lIiA6ICJTYWZlRHJpZnQ0OCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jMDM0MDkyM2E2ZGU0ODI1YTE3NjgxM2QxMzM1MDNlZmYxODZkYjA4OTZlMzJiNjcwNDkyOGMyYTJiZjY4NDIyIgogICAgfQogIH0KfQ=="

    // Main thread only: entity load/unload, render and the scheduled texture check all run there
    private val ichors = HashMap<Entity, MutableSet<ArmorStand>>()

    init {
        // TODO: check ichor spawn timing based on armourstand timer (ICHOR 0.1s etc)
        ClientEntityEvents.ENTITY_LOAD.register { entity, _ -> if (enabled) onEntityLoad(entity) }
        ClientEntityEvents.ENTITY_UNLOAD.register { entity, _ -> onEntityUnload(entity) }

        on<RenderWorldEvent> {
            if (ichors.isEmpty()) return@on
            val boss = bossPriority() ?: return@on
            val chosen = ichors[boss]?.takeIf { it.isNotEmpty() } ?: return@on
            val player = mc.player ?: return@on

            val bossPos = boss.renderPos
            val playerPos = player.renderPos
            val playerDx = playerPos.x - bossPos.x
            val playerDz = playerPos.z - bossPos.z
            val playerDistance = sqrt(playerDx * playerDx + playerDz * playerDz) + 2.0

            quads(depth = false) {
                for (ichor in chosen) {
                    // ts happened once somehow so
                    if (!ichor.isAlive) continue

                    val ichorPos = ichor.renderPos
                    val ichorDx = ichorPos.x - bossPos.x
                    val ichorDz = ichorPos.z - bossPos.z
                    val ichorDistanceSq = ichorDx * ichorDx + ichorDz * ichorDz
                    if (ichorDistanceSq <= 1.0e-12) continue

                    val ichorDistance = sqrt(ichorDistanceSq)
                    val lineLength = maxOf(playerDistance, ichorDistance)
                    val ux = ichorDx / ichorDistance
                    val uz = ichorDz / ichorDistance
                    val y = minOf(playerPos.y, ichorPos.y) - 0.2

                    val width = when {
                        ichorDistance <= 2.0 -> 0.9
                        ichorDistance >= 8.0 -> 0.3
                        else -> 0.9 - (ichorDistance - 2.0) / 6.0 * 0.6
                    }
                    flatLine(
                        Vec3(bossPos.x + ux * lineLength, y, bossPos.z + uz * lineLength),
                        Vec3(bossPos.x - ux * lineLength, y, bossPos.z - uz * lineLength),
                        width,
                        lineColour,
                    )
                }
            }
        }

        on<SlayerEvent.Spawn> { ichors.clear() }
        on<LevelEvent.Unload> { ichors.clear() }
    }

    override fun onDisable() {
        ichors.clear()
    }

    private fun onEntityLoad(entity: Entity) {
        if (!Location.inRift || entity !is ArmorStand) return
        val boss = bossPriority() ?: return
        if (entity.distanceTo(boss) > 6) return

        // The head arrives in the equipment packet after the spawn
        schedule(1, serverTick = true) {
            if (entity.getItemBySlot(EquipmentSlot.HEAD).texture != ICHOR_TEXTURE) return@schedule
            ichors.getOrPut(boss) { HashSet() }.add(entity)
        }
    }

    private fun bossPriority(): Entity? = SlayerUtils.boss ?: SlayerUtils.lootshareBoss?.takeIf { otherBoss }

    private fun onEntityUnload(entity: Entity) {
        if (ichors.isEmpty()) return
        ichors.remove(entity)
        ichors.values.forEach { it.remove(entity) }
    }
}
