package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
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
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
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
    private val twinclawsAlert by BooleanSetting(
        "Twinclaws Alert",
        default = false,
        desc = "Title when Twinclaws spawn"
    )
    private val twinclawsDelay by NumberSetting(
        "Delay",
        default = 0,
        range = 0..1000,
        increment = 10,
        desc = "Delay before the alert",
        unit = "ms"
    ).withDependency { twinclawsAlert }

    const val ICHOR_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTYxNTg4ODAwMDU1MywKICAicHJvZmlsZUlkIiA6ICI5ZDIyZGRhOTVmZGI0MjFmOGZhNjAzNTI1YThkZmE4ZCIsCiAgInByb2ZpbGVOYW1lIiA6ICJTYWZlRHJpZnQ0OCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jMDM0MDkyM2E2ZGU0ODI1YTE3NjgxM2QxMzM1MDNlZmYxODZkYjA4OTZlMzJiNjcwNDkyOGMyYTJiZjY4NDIyIgogICAgfQogIH0KfQ=="
    private const val TWINCLAWS_ALERT_MS = 1500L
    private val ichors: ConcurrentHashMap<Entity, CopyOnWriteArraySet<ArmorStand>> = ConcurrentHashMap()

    @Volatile private var wasTwinclaws = false
    @Volatile private var pendingAlertAt = 0L
    @Volatile private var twinclawsAlertUntil = 0L

    init {
        // TODO: check ichor spawn timing based on armourstand timer (ICHOR 0.1s etc)
        ClientEntityEvents.ENTITY_LOAD.register { entity, _ -> onEntityLoad(entity) }
        ClientEntityEvents.ENTITY_UNLOAD.register { entity, _ -> onEntityUnload(entity) }

        on<RenderExtractEvent> {
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
            resetTwinclawsState()
            if (!announceSpawn) return@on

            val pos = boss.blockPosition()
            sendCommand("pc x: ${pos.x}, y: ${pos.y}, z: ${pos.z} ; Boss Spawned")
        }

        on<LevelEvent.Load> { resetTwinclawsState() }
        on<LevelEvent.Unload> { resetTwinclawsState() }

        on<TickEvent.Server> { pollTwinclaws() }

        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath("adrift", "twinclaws_alert"),
            ::renderTwinclawsAlert
        )
    }

    private fun onEntityLoad(entity: Entity) {
        if (LocationUtils.currentArea != Island.Rift) return

        val boss = bossPriority() ?: return
        if (entity !is ArmorStand || entity.distanceTo(boss) > 6) return

        schedule(1, serverTick = true) {
            val its = entity.getItemBySlot(EquipmentSlot.HEAD)
            if (its.texture != ICHOR_TEXTURE) return@schedule

            ichors.computeIfAbsent(boss) { CopyOnWriteArraySet() }.add(entity)
        }
    }

    private fun bossPriority(): Entity? {
        return SlayerUtils.boss ?: SlayerUtils.lootshareBoss?.takeIf { otherBoss }
    }

    private fun pollTwinclaws() {
        if (!twinclawsAlert) {
            resetTwinclawsState()
            return
        }

        val boss = SlayerUtils.boss?.takeIf { it.isAlive } ?: run {
            resetTwinclawsState()
            return
        }
        val timerName = mc.level?.getEntity(boss.id + 2)?.plainTextName
        if (timerName == null || !timerName.contains("TWINCLAWS")) {
            wasTwinclaws = false
            pendingAlertAt = 0L
            return
        }

        val now = System.currentTimeMillis()
        if (!wasTwinclaws) {
            wasTwinclaws = true
            val delayMs = twinclawsDelay.toLong()
            
            if (delayMs <= 0L) {
                pendingAlertAt = 0L
                fireTwinclawsAlert()
            } 
            else pendingAlertAt = now + delayMs
            
        } else if (pendingAlertAt != 0L && now >= pendingAlertAt) {
            pendingAlertAt = 0L
            fireTwinclawsAlert()
        }
    }

    private fun fireTwinclawsAlert() {
        twinclawsAlertUntil = System.currentTimeMillis() + TWINCLAWS_ALERT_MS
    }

    private fun renderTwinclawsAlert(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (!enabled || !twinclawsAlert) return
        if (twinclawsAlertUntil - System.currentTimeMillis() <= 0) return

        val font = mc.font
        val text = Component.literal("§6§lTWINCLAWS!")
        val titleWidth = font.width(text)
        graphics.pose().pushMatrix()
        graphics.pose().translate(graphics.guiWidth() / 2f, graphics.guiHeight() / 2f)
        graphics.pose().scale(3f, 3f)
        graphics.textWithBackdrop(font, text, -titleWidth / 2, -10, titleWidth, ARGB.white(255))
        graphics.pose().popMatrix()
    }

    private fun resetTwinclawsState() {
        wasTwinclaws = false
        pendingAlertAt = 0L
        twinclawsAlertUntil = 0L
    }

    override fun onDisable() {
        super.onDisable()
        resetTwinclawsState()
    }

    private fun onEntityUnload(entity: Entity) {
        ichors.remove(entity)
        ichors.values.forEach { it.remove(entity) }
    }
}
