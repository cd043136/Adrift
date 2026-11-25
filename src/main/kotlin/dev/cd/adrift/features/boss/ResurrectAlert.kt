package dev.cd.adrift.features.boss

import dev.cd.adrift.events.LevelEvent
import dev.cd.adrift.events.RenderWorldEvent
import dev.cd.adrift.events.TickEvent
import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module
import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.Location
import dev.cd.adrift.utils.SlayerUtils
import dev.cd.adrift.utils.render.camera
import dev.cd.adrift.utils.render.lines
import dev.cd.adrift.utils.render.text
import dev.cd.adrift.utils.texture
import net.minecraft.client.player.RemotePlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot
import kotlin.math.max

/**
 * Highlights the skulls dead clotgoyles leave behind, and counts down to them coming back. When a boss finishes
 * casting RESURRECT, every skull that belongs to it turns back into a clotgoyle.
 */
object ResurrectAlert : Module(
    "Resurrect Alert",
    Category.BOSS,
    "Tints clotgoyle skulls on the ground and warns when your boss resurrects them.",
    toggle = "Resurrect Alert",
) {
    private val colour by color("Colour", Color("ffff00ff"), "Tint of clotgoyle skulls on the ground.")
    private val tracer by bool("Tracer", false, "Draws a tracer to your boss's skulls while RESURRECT is being cast.")
    private val timer by bool("Timer", false, "Shows a countdown above your boss's skulls while RESURRECT is being cast.")

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
    /** Whether there are clotgoyles or skulls to clear once we leave the Rift; read on the netty thread. */
    @Volatile private var tracking = false

    init {
        on<TickEvent.Server> {
            val t = serverTick.incrementAndGet()
            if (!Location.inRift && !tracking) return@on
            mc.execute { onServerTick(t) }
        }
        on<LevelEvent.Load> { reset() }
        on<LevelEvent.Unload> { reset() }

        on<RenderWorldEvent> {
            if (!tracer && !timer) return@on
            if (resurrectEnd < 0 || !Location.inRift) return@on
            val boss = SlayerUtils.boss ?: return@on
            val level = mc.level ?: return@on

            val centres = skulls.filterValues { it == boss.id }.keys
                .mapNotNull { level.getEntity(it)?.position()?.add(0.0, SKULL_CENTRE, 0.0) }
            if (centres.isEmpty()) return@on

            if (tracer) lines {
                // A vertex exactly at the camera gets clipped and the whole line vanishes, so start just in front of it
                val forward = camera.forwardVector()
                val cam = camera.position().add(forward.x() * 0.5, forward.y() * 0.5, forward.z() * 0.5)
                for (centre in centres) line(cam.x, cam.y, cam.z, centre.x, centre.y, centre.z, colour.withAlpha(1f), LINE_WIDTH)
            }

            if (timer) {
                val remaining = max(0L, resurrectEnd - tick)
                val label = (if (remaining <= 20) "§c" else "§e") + "%.2fs".format(remaining / 20.0)
                for (centre in centres) text(label, centre.add(0.0, TEXT_ABOVE, 0.0), TEXT_SCALE)
            }
        }
    }

    @JvmStatic
    fun isTinted(entity: Entity): Boolean = enabled && entity is ArmorStand && entity.id in skulls

    @JvmStatic
    fun tintColor(): Int = colour.rgba

    override fun onDisable() {
        reset()
    }

    private fun reset() {
        clots.clear()
        skulls.clear()
        notSkull.clear()
        resurrectEnd = -1L
        tracking = false
    }

    private fun onServerTick(t: Long) {
        tick = t
        if (!enabled) return
        val level = mc.level
        if (level == null || !Location.inRift) {
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
        tracking = skulls.isNotEmpty() || clots.isNotEmpty()
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
        val texture = head.texture ?: return false
        return try {
            String(Base64.getDecoder().decode(texture)).contains(SKULL_TEXTURE)
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
