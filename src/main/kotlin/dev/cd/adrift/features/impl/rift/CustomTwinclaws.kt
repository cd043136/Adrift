package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
//? if >=26.2 {
import com.odtheking.odin.events.RenderExtractEvent
//?} else
//import dev.cd.adrift.utils.RenderExtractEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.drawLine
import com.odtheking.odin.utils.renderPos
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import dev.cd.adrift.mixin.ClientboundMoveEntityPacketAccessor
import dev.cd.adrift.utils.Category
import dev.cd.adrift.utils.RenderUtils.drawQuads
import dev.cd.adrift.utils.SlayerUtils
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.network.protocol.game.VecDeltaCodec
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
//? if >=26.2 {
import net.minecraft.world.entity.EntityTypes
//?} else
//import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws our boss's Twinclaws as 3D crescents instead of falling blocks.
 *
 * A claw is 14 falling blocks with consecutive ids, spawned moving sideways at ~0.75 blocks/tick. Each block is also
 * simulated from its own packets ([Ghost]), since Odin's Hide Falling Blocks removes them from the level on spawn.
 */
object CustomTwinclaws : Module(
    "Custom Twinclaws",
    category = Category.RIFT,
    description = "Draws Twinclaws as a 3D crescent instead of falling blocks."
) {
    private val colour by ColorSetting("Colour", default = Color("ff303050"), allowAlpha = true, desc = "Fill of the crescent. Edges use it at full opacity.")
    //? if >=26.2 {
    private val lineWidth by NumberSetting("Line Width", 3.0, 1.0..8.0, 0.5, desc = "Width of the crescent edges.")
    //?} else
    //private val lineWidth by NumberSetting("Line Width", 3.0, 1.0, 8.0, 0.5, desc = "Width of the crescent edges.")
    private val hideBlocks by BooleanSetting("Hide Blocks", true, desc = "Hides the falling blocks the claw is made of.")

    private const val BLOCKS_PER_CLAW = 14
    private const val CLAW_SPEED_MIN = 0.6
    private const val CLAW_SPEED_MAX = 0.9
    private const val BOSS_RANGE_SQ = 4.0 * 4.0
    private const val BOSS_BELOW = 3.0
    private const val BOSS_ABOVE = 1.0
    private const val MAX_AGE_TICKS = 100
    private const val SPAWN_GRACE_TICKS = 5

    private const val SPAN = 5.57
    private const val BULGE = 1.8
    private const val TIP_OVERHANG = 0.05
    private const val THICKNESS = 0.9
    private const val WIDTH = 0.7
    private const val SEGMENTS = 24

    private const val GRAVITY = 0.04
    private const val AIR_DRAG = 0.98
    private const val BLOCK_HEIGHT = 0.98
    private const val NO_GRAVITY_DATA_ID = 5
    private const val SNAP_DISTANCE_SQ = 4096.0

    /** A claw block simulated like the client's `FallingBlockEntity`, minus collisions. */
    private class Ghost(spawn: Vec3, var vel: Vec3) {
        var pos: Vec3 = spawn
        var old: Vec3 = spawn
        var noGravity = false
        var removed = false
        val codec = VecDeltaCodec().apply { setBase(spawn) }

        fun tick() {
            old = pos
            if (!noGravity) vel = vel.add(0.0, -GRAVITY, 0.0)
            pos = pos.add(vel)
            vel = vel.scale(AIR_DRAG)
        }

        fun moveTo(p: Vec3) {
            if (p.distanceToSqr(pos) > SNAP_DISTANCE_SQ) old = p
            pos = p
        }

        fun teleport(change: PositionMoveRotation, relatives: Set<Relative>) {
            val now = PositionMoveRotation.calculateAbsolute(PositionMoveRotation(pos, vel, 0f, 0f), change, relatives)
            moveTo(now.position())
            vel = now.deltaMovement()
        }

        fun drawnPos(): Vec3 {
            val t = mc.deltaTracker.getGameTimeDeltaPartialTick(true).toDouble()
            return Vec3(Mth.lerp(t, old.x, pos.x), Mth.lerp(t, old.y, pos.y), Mth.lerp(t, old.z, pos.z))
        }
    }

    private class Claw(val vx: Double, val vz: Double, val ours: Boolean) {
        val ids = IntArray(BLOCKS_PER_CLAW)
        val ghosts = arrayOfNulls<Ghost>(BLOCKS_PER_CLAW)
        val offX = DoubleArray(BLOCKS_PER_CLAW)
        val offY = DoubleArray(BLOCKS_PER_CLAW)
        val offZ = DoubleArray(BLOCKS_PER_CLAW)
        var size = 0
        var age = 0
        var baseX = 0.0
        var baseY = 0.0
        var baseZ = 0.0

        val lastId get() = ids[size - 1]

        fun add(id: Int, x: Double, y: Double, z: Double, ghost: Ghost) {
            if (size == 0) {
                baseX = x
                baseY = y
                baseZ = z
            }
            ids[size] = id
            ghosts[size] = ghost
            offX[size] = x - baseX
            offY[size] = y - baseY
            offZ[size] = z - baseZ
            size++
        }
    }

    private val claws = ArrayList<Claw>()
    private val ours = HashSet<Int>()
    private val ghosts = HashMap<Int, Ghost>()
    // Filled on the netty thread so packets right after a spawn aren't missed
    private val watched: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    @JvmStatic
    fun isHidden(entity: Entity): Boolean = enabled && hideBlocks && ours.isNotEmpty() && entity.id in ours

    init {
        onReceive<ClientboundAddEntityPacket> {
            //? if >=26.2 {
            if (type != EntityTypes.FALLING_BLOCK) return@onReceive
            //?} else
            //if (type != EntityType.FALLING_BLOCK) return@onReceive
            val v = movement
            if (abs(v.y) > 1.0e-3) return@onReceive
            val speed = sqrt(v.x * v.x + v.z * v.z)
            if (speed !in CLAW_SPEED_MIN..CLAW_SPEED_MAX) return@onReceive
            val id = id
            val x = x
            val y = y
            val z = z
            watched += id
            mc.execute { onClawBlock(id, x, y, z, v) }
        }

        onReceive<ClientboundMoveEntityPacket> {
            if (!hasPosition()) return@onReceive
            val packet = this
            val id = (packet as ClientboundMoveEntityPacketAccessor).`adrift$getEntityId`()
            if (id !in watched) return@onReceive
            mc.execute {
                val ghost = ghosts[id] ?: return@execute
                //? if >=26.3 {
                val pos = packet.getPositionDelta().decode(ghost.codec).endPosition()
                //?} else
                //val pos = ghost.codec.decode(packet.xa.toLong(), packet.ya.toLong(), packet.za.toLong())
                ghost.codec.setBase(pos)
                ghost.moveTo(pos)
            }
        }
        onReceive<ClientboundEntityPositionSyncPacket> {
            val id = id()
            if (id !in watched) return@onReceive
            //? if >=26.3 {
            val pos = position().endPosition()
            //?} else
            //val pos = values().position()
            mc.execute {
                val ghost = ghosts[id] ?: return@execute
                ghost.codec.setBase(pos)
                ghost.moveTo(pos)
            }
        }
        onReceive<ClientboundTeleportEntityPacket> {
            val id = id()
            if (id !in watched) return@onReceive
            val change = change()
            val relatives = relatives()
            mc.execute { ghosts[id]?.teleport(change, relatives) }
        }
        onReceive<ClientboundSetEntityMotionPacket> {
            val id = id()
            if (id !in watched) return@onReceive
            val movement = movement()
            mc.execute { ghosts[id]?.vel = movement }
        }
        onReceive<ClientboundSetEntityDataPacket> {
            val id = id()
            if (id !in watched) return@onReceive
            val noGravity = packedItems().lastOrNull { it.id() == NO_GRAVITY_DATA_ID }?.value() as? Boolean
                ?: return@onReceive
            mc.execute { ghosts[id]?.noGravity = noGravity }
        }
        onReceive<ClientboundRemoveEntitiesPacket> {
            if (watched.isEmpty()) return@onReceive
            val ids = entityIds.filter { it in watched }
            if (ids.isNotEmpty()) mc.execute { for (id in ids) ghosts[id]?.removed = true }
        }

        on<TickEvent.End> { tick() }
        on<LevelEvent.Load> { reset() }
        on<LevelEvent.Unload> { reset() }

        on<RenderExtractEvent> {
            if (claws.isEmpty()) return@on
            val level = mc.level ?: return@on
            val shapes = claws.mapNotNull { if (it.ours) crescent(level, it) else null }
            if (shapes.isEmpty()) return@on

            val quads = ArrayList<Vec3>(shapes.size * SEGMENTS * 16)
            for (rings in shapes) for (i in 0 until SEGMENTS) for (j in 0 until 4) {
                quads += rings[i][j]
                quads += rings[i][(j + 1) % 4]
                quads += rings[i + 1][(j + 1) % 4]
                quads += rings[i + 1][j]
            }
            drawQuads(quads, colour, depth = true)

            val width = lineWidth.toFloat()
            val edge = colour.copy().apply { alphaFloat = 1f }
            for (rings in shapes) for (j in 0 until 4) drawLine(rings.map { it[j] }, edge, true, width)
        }
    }

    override fun onDisable() {
        super.onDisable()
        reset()
    }

    private fun onClawBlock(id: Int, x: Double, y: Double, z: Double, movement: Vec3) {
        if (!enabled || LocationUtils.currentArea != Island.Rift) {
            watched -= id
            return
        }
        val vx = movement.x
        val vz = movement.z
        val claw = claws.lastOrNull {
            it.size < BLOCKS_PER_CLAW && it.lastId == id - 1 && abs(it.vx - vx) < 1.0e-4 && abs(it.vz - vz) < 1.0e-4
        } ?: Claw(vx, vz, nearBoss(x, y, z)).also { claws += it }
        val ghost = Ghost(Vec3(x, y, z), movement)
        ghosts[id] = ghost
        claw.add(id, x, y, z, ghost)
        if (claw.ours) ours += id
    }

    private fun nearBoss(x: Double, y: Double, z: Double): Boolean =
        listOfNotNull(SlayerUtils.boss, SlayerUtils.lootshareBoss).any { boss ->
            val dx = x - boss.x
            val dz = z - boss.z
            val dy = y - boss.y
            dx * dx + dz * dz <= BOSS_RANGE_SQ && dy >= -BOSS_BELOW && dy <= BOSS_ABOVE
        }

    private fun tick() {
        if (claws.isEmpty()) return
        val level = mc.level ?: return reset()
        if (level.tickRateManager().runsNormally()) for (ghost in ghosts.values) if (!ghost.removed) ghost.tick()
        val it = claws.iterator()
        while (it.hasNext()) {
            val claw = it.next()
            claw.age++
            val gone = claw.age > SPAWN_GRACE_TICKS && (0 until claw.size).none { k -> present(level, claw, k) }
            if (!gone && claw.age <= MAX_AGE_TICKS) continue
            it.remove()
            for (k in 0 until claw.size) {
                val id = claw.ids[k]
                ours -= id
                ghosts -= id
                watched -= id
            }
        }
    }

    private fun alive(level: ClientLevel, id: Int): Entity? = level.getEntity(id)?.takeUnless { it.isRemoved }

    private fun present(level: ClientLevel, claw: Claw, k: Int): Boolean =
        alive(level, claw.ids[k]) != null || claw.ghosts[k]?.removed == false

    /** Drawn centre of block [k]: the real entity if it's in the level, else its ghost. */
    private fun centre(level: ClientLevel, claw: Claw, k: Int): Vec3? {
        alive(level, claw.ids[k])?.let { return it.renderPos.add(0.0, it.bbHeight * 0.5, 0.0) }
        val ghost = claw.ghosts[k]?.takeUnless { it.removed } ?: return null
        return ghost.drawnPos().add(0.0, BLOCK_HEIGHT * 0.5, 0.0)
    }

    /**
     * Rings of four points (front, right, back, left) from tip to tip. Anchored on the block furthest along the
     * flight line, since the bottom blocks can snag on terrain and lag behind.
     */
    private fun crescent(level: ClientLevel, claw: Claw): Array<Array<Vec3>>? {
        val speed = sqrt(claw.vx * claw.vx + claw.vz * claw.vz)
        val fx = claw.vx / speed
        val fz = claw.vz / speed

        var best = Double.NEGATIVE_INFINITY
        var ox = 0.0
        var oy = 0.0
        var oz = 0.0
        for (k in 0 until claw.size) {
            val p = centre(level, claw, k) ?: continue
            val gx = p.x - claw.offX[k]
            val gz = p.z - claw.offZ[k]
            val along = gx * fx + gz * fz
            if (along <= best) continue
            best = along
            ox = gx
            oy = p.y - claw.offY[k]
            oz = gz
        }
        if (best == Double.NEGATIVE_INFINITY) return null
        val rx = -fz
        val rz = fx

        return Array(SEGMENTS + 1) { i ->
            val s = i.toDouble() / SEGMENTS
            val u = -TIP_OVERHANG + s * (1 + 2 * TIP_OVERHANG)
            val ahead = BULGE * sin(PI * u)
            val cx = ox + fx * ahead
            val cy = oy + SPAN * u
            val cz = oz + fz * ahead
            val dAhead = BULGE * PI * cos(PI * u)
            val len = sqrt(SPAN * SPAN + dAhead * dAhead)
            val nf = SPAN / len
            val ny = -dAhead / len
            val taper = sin(PI * s).coerceAtLeast(0.0).pow(0.8)
            val t = THICKNESS * 0.5 * taper
            val w = WIDTH * 0.5 * taper
            arrayOf(
                Vec3(cx + fx * nf * t, cy + ny * t, cz + fz * nf * t),
                Vec3(cx + rx * w, cy, cz + rz * w),
                Vec3(cx - fx * nf * t, cy - ny * t, cz - fz * nf * t),
                Vec3(cx - rx * w, cy, cz - rz * w),
            )
        }
    }

    private fun reset() {
        claws.clear()
        ours.clear()
        ghosts.clear()
        watched.clear()
    }
}
