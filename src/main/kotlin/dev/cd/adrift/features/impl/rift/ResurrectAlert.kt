package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
//? if >=26.2 {
import com.odtheking.odin.events.RenderExtractEvent
//?} else
//import dev.cd.adrift.utils.RenderExtractEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.drawLine
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import dev.cd.adrift.utils.Category
import dev.cd.adrift.utils.SlayerUtils
import net.minecraft.client.player.RemotePlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot
import kotlin.math.max


object ResurrectAlert : Module(
    "Resurrect Alert",
    category = Category.RIFT,
    description = "Helper for clotgoyle resurrection"
) {
    private val colour by ColorSetting("Colour", default = Color("ffff00ff"), allowAlpha = true, desc = "Tint of clotgoyle skulls on the ground.")
    private val tracer by BooleanSetting("Tracer", false, desc = "Tracer to your boss's skulls while RESURRECT is being cast.")
    private val timer by BooleanSetting("Timer", false, desc = "Countdown above your boss's skulls while RESURRECT is being cast.")

    private const val SKULL_TEXTURE = "5ee935b1"
    private const val CLOTGOYLE_NAME = "Clotgoyle "
    private const val BOSS_NAME = "Bloodfiend "
    private const val CLOT_BOSS_RANGE = 9.0
    private const val SKULL_LINK_RANGE = 2.5
    private const val SKULL_LINK_TICKS = 40
    private const val FALLBACK_BOSS_RANGE = 20.0
    private const val SKULL_CENTRE = 1.74
    private const val TEXT_ABOVE = 0.6
    private const val TEXT_SCALE = 0.8f
    private const val LINE_WIDTH = 3f

    private val resurrectRegex = Regex("""RESURRECT (\d+(?:\.\d+)?)s""")

    private class Clot(var pos: Vec3, val boss: Int, var goneTick: Long = -1)

    private val serverTick = AtomicLong()

    private var tick = 0L
    private val clots = HashMap<Int, Clot>()
    /** Skull stand id -> owning boss id (-1 when unknown) */
    private val skulls = HashMap<Int, Int>()
    private val notSkull = HashSet<Int>()
    private var resurrectEnd = -1L

    init {
        on<TickEvent.Server> {
            val t = serverTick.incrementAndGet()
            mc.execute { onServerTick(t) }
        }
        on<LevelEvent.Load> { reset() }
        on<LevelEvent.Unload> { reset() }

        on<RenderExtractEvent> {
            if (!tracer && !timer) return@on
            if (resurrectEnd < 0 || LocationUtils.currentArea != Island.Rift) return@on
            val boss = SlayerUtils.boss ?: return@on
            val level = mc.level ?: return@on

            //? if >=26.2 {
            val camera = mc.gameRenderer.mainCamera()
            //?} else
            //val camera = mc.gameRenderer.mainCamera
            val forward = camera.forwardVector()
            val cam = camera.position().add(forward.x() * 0.5, forward.y() * 0.5, forward.z() * 0.5)
            val line = colour.copy().apply { alphaFloat = 1f }
            val remaining = max(0L, resurrectEnd - tick)
            val seconds = remaining / 20.0
            val label = (if (remaining <= 20) "§c" else "§e") + "%.2fs".format(seconds)

            for ((id, owner) in skulls) {
                if (owner != boss.id) continue
                val stand = level.getEntity(id) ?: continue
                val centre = stand.position().add(0.0, SKULL_CENTRE, 0.0)
                if (tracer) drawLine(listOf(cam, centre), line, true, LINE_WIDTH)
                if (timer) drawText(label, centre.add(0.0, TEXT_ABOVE, 0.0), TEXT_SCALE, true)
            }
        }
    }

    @JvmStatic
    fun isTinted(entity: Entity): Boolean = enabled && entity is ArmorStand && entity.id in skulls

    @JvmStatic
    fun tintColor(): Int = colour.rgba

    override fun onDisable() {
        super.onDisable()
        reset()
    }

    private fun reset() {
        clots.clear()
        skulls.clear()
        notSkull.clear()
        resurrectEnd = -1L
    }

    private fun onServerTick(t: Long) {
        tick = t
        if (!enabled) return
        val level = mc.level
        if (level == null || LocationUtils.currentArea != Island.Rift) {
            if (skulls.isNotEmpty() || clots.isNotEmpty()) reset()
            return
        }

        updateResurrect()

        val bosses = level.players().filter { it is RemotePlayer && it.isAlive && it.plainTextName == BOSS_NAME }
        val seen = HashSet<Int>()

        for (entity in level.entitiesForRendering()) {
            when (entity) {
                is RemotePlayer -> if (entity.plainTextName == CLOTGOYLE_NAME) {
                    seen += entity.id
                    val clot = clots.getOrPut(entity.id) { Clot(entity.position(), nearestBoss(bosses, entity.position(), CLOT_BOSS_RANGE)) }
                    clot.pos = entity.position()
                    if (!entity.isAlive && clot.goneTick < 0) clot.goneTick = t
                }
                is ArmorStand -> {
                    if (entity.id in skulls || entity.id in notSkull) continue
                    when (isClotgoyleSkull(entity)) {
                        true -> skulls[entity.id] = ownerOf(entity, bosses)
                        false -> notSkull += entity.id
                        null -> {} // head not sent yet, look again next tick
                    }
                }
            }
        }

        for ((id, clot) in clots) if (id !in seen && clot.goneTick < 0) clot.goneTick = t
        clots.values.removeIf { it.goneTick >= 0 && t - it.goneTick > SKULL_LINK_TICKS }
        skulls.keys.removeIf { level.getEntity(it)?.isRemoved != false }
        notSkull.removeIf { level.getEntity(it)?.isRemoved != false }
    }

    private fun updateResurrect() {
        val text = SlayerUtils.timer?.plainTextName
        val match = text?.let { resurrectRegex.find(it) }
        if (match == null) {
            resurrectEnd = -1L
            return
        }
        if (resurrectEnd < 0) {
            val seconds = match.groupValues[1].toDoubleOrNull() ?: return
            resurrectEnd = tick + Math.round(seconds * 20)
        }
    }

    private fun ownerOf(stand: ArmorStand, bosses: List<Entity>): Int {
        val pos = stand.position()
        val clot = clots.values
            .filter { it.goneTick >= 0 && hypot(it.pos.x - pos.x, it.pos.z - pos.z) < SKULL_LINK_RANGE }
            .maxByOrNull { it.goneTick }
        if (clot != null && clot.boss >= 0) return clot.boss
        return nearestBoss(bosses, pos, FALLBACK_BOSS_RANGE)
    }

    private fun nearestBoss(bosses: List<Entity>, pos: Vec3, range: Double): Int =
        bosses.map { it to it.position().distanceTo(pos) }
            .filter { it.second < range }
            .minByOrNull { it.second }?.first?.id ?: -1

    private fun isClotgoyleSkull(stand: ArmorStand): Boolean? {
        val head = stand.getItemBySlot(EquipmentSlot.HEAD)
        if (head.isEmpty) return null
        val profile = head.get(DataComponents.PROFILE) ?: return false
        val textures = profile.partialProfile().properties().get("textures").firstOrNull() ?: return false
        return try {
            String(Base64.getDecoder().decode(textures.value())).contains(SKULL_TEXTURE)
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
