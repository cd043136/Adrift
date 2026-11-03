package dev.cd.adrift.features.impl.rift

//? if >=26.2 {
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
//?} else
//import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
//? if >=26.2 {
import com.odtheking.odin.events.RenderExtractEvent
//?} else
//import dev.cd.adrift.utils.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
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
import net.minecraft.network.chat.Component
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
    private val twinclawsAlertText = Component.literal("§6§lTWINCLAWS!")

    // defaults put it where the old fixed title was on a 1080p screen at gui scale 4
    private val twinclawsAlert by HUD("Twinclaws Alert", desc = "Alert when Twinclaws spawn", x = 138, y = 99, scale = 3f) { example ->
        if (!example && twinclawsAlertUntil <= System.currentTimeMillis()) return@HUD 0 to 0

        val width = mc.font.width(twinclawsAlertText)
        // textWithBackdrop draws its box 2px past the text
        textWithBackdrop(mc.font, twinclawsAlertText, 2, 2, width, ARGB.white(255))
        width + 4 to mc.font.lineHeight + 4
    }
    private val twinclawsDelay by NumberSetting(
        "Delay",
        default = 0,
        //? if >=26.2 {
        range = 0..1000,
        //?} else {
        /*min = 0,
        max = 1000,
        *///?}
        increment = 10,
        desc = "Delay before the alert",
        unit = "ms"
    ).withDependency { twinclawsAlert.enabled }

    private val bossHudExample = listOf(
        Component.literal("§c02:41 §5§lMANIA §b14.8s §6§lTWINCLAWS §b1.6s"),
        Component.literal("§4☠ §cBloodfiend V §a2,356§c❤")
    )

    private val bossHud by HUD("Boss HUD", desc = "Shows your boss's timer and nametag") { example ->
        val lines = if (example) bossHudExample
        else {
            val boss = SlayerUtils.boss?.takeIf { it.isAlive } ?: return@HUD 0 to 0
            listOfNotNull(bossStand(boss, 2)?.customName, bossStand(boss, 1)?.customName)
        }
        if (lines.isEmpty()) return@HUD 0 to 0

        // fixed width so the centre doesn't shift as the timer text changes length
        val width = bossHudExample.maxOf { mc.font.width(it) }
        lines.forEachIndexed { i, line ->
            text(line.visualOrderText, (width - mc.font.width(line)) / 2, i * BOSS_HUD_LINE_HEIGHT, Colors.WHITE)
        }
        width to BOSS_HUD_LINE_HEIGHT * lines.size
    }

    const val ICHOR_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTYxNTg4ODAwMDU1MywKICAicHJvZmlsZUlkIiA6ICI5ZDIyZGRhOTVmZGI0MjFmOGZhNjAzNTI1YThkZmE4ZCIsCiAgInByb2ZpbGVOYW1lIiA6ICJTYWZlRHJpZnQ0OCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jMDM0MDkyM2E2ZGU0ODI1YTE3NjgxM2QxMzM1MDNlZmYxODZkYjA4OTZlMzJiNjcwNDkyOGMyYTJiZjY4NDIyIgogICAgfQogIH0KfQ=="
    private const val TWINCLAWS_ALERT_MS = 1500L
    private const val BOSS_HUD_LINE_HEIGHT = 10
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
        on<LevelEvent.Unload> {
            resetTwinclawsState()
            ichors.clear()
        }

        on<TickEvent.Server> { mc.execute { pollTwinclaws() } }
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
        if (!twinclawsAlert.enabled) {
            resetTwinclawsState()
            return
        }

        val boss = SlayerUtils.boss?.takeIf { it.isAlive } ?: run {
            resetTwinclawsState()
            return
        }
        val timerName = bossStand(boss, 2)?.plainTextName
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

    // +1 = nametag, +2 = timer
    private fun bossStand(boss: Entity, offset: Int): Entity? = mc.level?.getEntity(boss.id + offset)

    private fun fireTwinclawsAlert() {
        twinclawsAlertUntil = System.currentTimeMillis() + TWINCLAWS_ALERT_MS
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
