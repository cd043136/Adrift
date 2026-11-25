package dev.cd.adrift.features.boss

import dev.cd.adrift.events.LevelEvent
import dev.cd.adrift.events.RenderWorldEvent
import dev.cd.adrift.events.TickEvent
import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module
import dev.cd.adrift.mixin.ClientboundMoveEntityPacketAccessor
import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.Location
import dev.cd.adrift.utils.SlayerUtils
import dev.cd.adrift.utils.render.lines
import dev.cd.adrift.utils.render.quads
import dev.cd.adrift.utils.renderPos
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket
import net.minecraft.network.protocol.game.VecDeltaCodec
//? if >=26.2 {
import net.minecraft.world.entity.EntityTypes
//?} else
//import net.minecraft.world.entity.EntityType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Predicts where a boss lands after its SPECTRAL hop, which it makes riding a freshly spawned armor stand. The hop's
 * direction is the stand's, and its length is a linear fit over the stand's first few position updates.
 */
object SpectralLanding : Module(
    "Spectral Landing",
    Category.BOSS,
    "Shows where the boss will land after its SPECTRAL hop.",
    toggle = "Spectral Landing",
) {
    private val colour by color("Colour", Color("a040ff60"), "Colour of the landing box, trail and line.")
    private val showLine by bool("Line", true, "Draws a line from the boss to the predicted landing spot.")
    private val otherBoss by bool("On Other Boss", false, "Also predicts for a boss you're helping someone with.")

    private const val LINGER_TICKS = 6
    private const val TRAIL_TICKS = 20
    private const val TRAIL_STEP_SQ = 0.05 * 0.05
    private const val TRAIL_MAX_POINTS = 256
    private const val MAX_HOP_TICKS = 30
    private const val CANDIDATE_TICKS = 5
    private const val MIN_FIRST_STEP = 0.4
    private const val MIN_LENGTH = 4.5
    private const val MAX_LENGTH = 7.6
    private const val BOX_HALF_WIDTH = 0.3
    private const val BOX_HEIGHT = 1.8
    private const val BASE_HALF_WIDTH = 0.5
    private const val GROUND_SCAN_UP = 2
    private const val GROUND_SCAN_DOWN = 8
    private const val STAND_ABOVE_FEET = 0.9375

    private val FIT = arrayOf(
        doubleArrayOf(10.4022, 3.3718, -4.1907),
        doubleArrayOf(-2.2190, -3.0643, -3.3226, 4.9038, 0.9842),
        doubleArrayOf(3.6829, 0.6060, 1.0733, -1.8139, -2.7765, 2.0290, 1.4307),
    )

    private class Hop(val spawn: Vec3, val boss: Int) {
        val codec = VecDeltaCodec().apply { setBase(spawn) }
        val trail = ArrayList<Vec3>()
        val dists = ArrayList<Double>(4)
        val heights = ArrayList<Double>(4)
        var last: Vec3 = spawn
        var lastTick = -1
        var age = 0
        var leftAt = -1
        var landing: Vec3? = null
        var refined = false
        var hover = false
    }

    private class Candidate(val spawn: Vec3, val tick: Int)

    private var tick = 0
    private val candidates = HashMap<Int, Candidate>()
    private val hops = HashMap<Int, Hop>()
    private val watched: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    init {
        onReceive<ClientboundAddEntityPacket> {
            //? if >=26.2 {
            if (type != EntityTypes.ARMOR_STAND) return@onReceive
            //?} else
            //if (type != EntityType.ARMOR_STAND) return@onReceive
            if (!Location.inRift) return@onReceive
            val id = id
            val spawn = Vec3(x, y, z)
            mc.execute { if (enabled && Location.inRift) candidates[id] = Candidate(spawn, tick) }
        }

        onReceive<ClientboundSetPassengersPacket> {
            // Outside the Rift there are no candidates, and no hops unless some are still being watched
            if (!Location.inRift && watched.isEmpty()) return@onReceive
            val vehicle = vehicle
            val passengers = passengers
            mc.execute { onPassengers(vehicle, passengers) }
        }

        onReceive<ClientboundMoveEntityPacket> {
            if (!hasPosition()) return@onReceive
            val packet = this
            val id = (packet as ClientboundMoveEntityPacketAccessor).`adrift$getEntityId`()
            if (id !in watched) return@onReceive
            mc.execute {
                val hop = hops[id] ?: return@execute
                //? if >=26.3 {
                val pos = packet.getPositionDelta().decode(hop.codec).endPosition()
                //?} else
                //val pos = hop.codec.decode(packet.xa.toLong(), packet.ya.toLong(), packet.za.toLong())
                onUpdate(hop, pos)
            }
        }

        onReceive<ClientboundEntityPositionSyncPacket> {
            val id = id()
            if (id !in watched) return@onReceive
            //? if >=26.3 {
            val pos = position().endPosition()
            //?} else
            //val pos = values().position()
            mc.execute { hops[id]?.let { onUpdate(it, pos) } }
        }

        onReceive<ClientboundRemoveEntitiesPacket> {
            if (watched.isEmpty()) return@onReceive
            val ids = entityIds.filter { it in watched }
            if (ids.isNotEmpty()) mc.execute { for (id in ids) hops[id]?.let { if (it.leftAt < 0) it.leftAt = tick } }
        }

        on<TickEvent.End> { onTick() }
        on<LevelEvent.Load> { reset() }

        on<RenderWorldEvent> {
            if (hops.isEmpty()) return@on
            for (hop in hops.values) if (!hop.hover) extendTrail(hop)

            quads(depth = true) {
                for (hop in hops.values) {
                    val land = visibleLanding(hop) ?: continue
                    val box = AABB(
                        land.x - BOX_HALF_WIDTH, land.y, land.z - BOX_HALF_WIDTH,
                        land.x + BOX_HALF_WIDTH, land.y + BOX_HEIGHT, land.z + BOX_HALF_WIDTH
                    )
                    box(box, if (hop.refined) colour else colour.multiplyAlpha(0.5f))
                }
            }

            lines {
                val edge = colour.withAlpha(1f)
                for ((id, hop) in hops) {
                    if (hop.hover) continue
                    val fade = trailFade(hop)
                    if (fade > 0f) polyline(hop.trail, colour.withAlpha(fade), 3f)

                    val land = visibleLanding(hop) ?: continue
                    val y = land.y + 0.02
                    polyline(
                        listOf(
                            Vec3(land.x - BASE_HALF_WIDTH, y, land.z - BASE_HALF_WIDTH),
                            Vec3(land.x + BASE_HALF_WIDTH, y, land.z - BASE_HALF_WIDTH),
                            Vec3(land.x + BASE_HALF_WIDTH, y, land.z + BASE_HALF_WIDTH),
                            Vec3(land.x - BASE_HALF_WIDTH, y, land.z + BASE_HALF_WIDTH),
                            Vec3(land.x - BASE_HALF_WIDTH, y, land.z - BASE_HALF_WIDTH),
                        ),
                        edge, 3f
                    )
                    if (showLine && hop.leftAt < 0) {
                        val from = mc.level?.getEntity(id)?.renderPos ?: hop.last
                        polyline(listOf(from, land.add(0.0, 0.05, 0.0)), edge, 2f)
                    }
                }
            }
        }
    }

    /** Ticks since the boss got off, or -1 while it's still hopping. */
    private fun sinceLeft(hop: Hop): Int = if (hop.leftAt >= 0) tick - hop.leftAt else -1

    private fun visibleLanding(hop: Hop): Vec3? = hop.landing?.takeIf { !hop.hover && sinceLeft(hop) <= LINGER_TICKS }

    private fun extendTrail(hop: Hop) {
        if (sinceLeft(hop) > LINGER_TICKS || hop.trail.size >= TRAIL_MAX_POINTS) return
        val boss = mc.level?.getEntity(hop.boss) ?: return
        val p = boss.renderPos.add(0.0, boss.bbHeight * 0.5, 0.0)
        val last = hop.trail.lastOrNull()
        if (last == null || last.distanceToSqr(p) > TRAIL_STEP_SQ) hop.trail += p
    }

    private fun trailFade(hop: Hop): Float {
        val sinceLeft = sinceLeft(hop)
        return if (sinceLeft <= LINGER_TICKS) 1f
        else 1f - (sinceLeft - LINGER_TICKS).toFloat() / (TRAIL_TICKS - LINGER_TICKS)
    }

    override fun onDisable() {
        reset()
    }

    private fun onPassengers(vehicle: Int, passengers: IntArray) {
        val hop = hops[vehicle]
        if (hop != null) {
            if (passengers.isEmpty() && hop.leftAt < 0) hop.leftAt = tick
            return
        }
        val candidate = candidates.remove(vehicle) ?: return
        if (!enabled) return
        val boss = passengers.firstOrNull { isTracked(it) } ?: return
        hops[vehicle] = Hop(candidate.spawn, boss)
        watched += vehicle
    }

    private fun isTracked(id: Int): Boolean {
        SlayerUtils.boss?.let { if (it.id == id) return true }
        return otherBoss && SlayerUtils.lootshareBoss?.id == id
    }

    private fun onUpdate(hop: Hop, pos: Vec3) {
        hop.codec.setBase(pos)
        hop.last = pos
        if (hop.hover || hop.leftAt >= 0) return

        val dx = pos.x - hop.spawn.x
        val dz = pos.z - hop.spawn.z
        val dist = sqrt(dx * dx + dz * dz)
        val height = pos.y - hop.spawn.y

        if (hop.lastTick == tick && hop.dists.isNotEmpty()) {
            hop.dists[hop.dists.size - 1] = dist
            hop.heights[hop.heights.size - 1] = height
        } else {
            hop.dists += dist
            hop.heights += height
        }
        hop.lastTick = tick

        if (hop.dists[0] < MIN_FIRST_STEP) {
            hop.hover = true
            hop.landing = null
            return
        }
        if (hop.dists.size > FIT.size || dist < 1.0e-6) return

        val w = FIT[hop.dists.size - 1]
        var length = w[0]
        for (i in hop.dists.indices) length += w[1 + 2 * i] * hop.dists[i] + w[2 + 2 * i] * hop.heights[i]
        length = length.coerceIn(maxOf(MIN_LENGTH, dist), MAX_LENGTH)

        val x = hop.spawn.x + dx / dist * length
        val z = hop.spawn.z + dz / dist * length
        hop.landing = Vec3(x, groundY(x, z, hop.spawn.y), z)
        hop.refined = hop.dists.size >= 2
    }

    private fun groundY(x: Double, z: Double, standY: Double): Double {
        val feet = standY - STAND_ABOVE_FEET
        val level = mc.level ?: return feet
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        val top = floor(feet).toInt() + GROUND_SCAN_UP
        val pos = BlockPos.MutableBlockPos()
        var aboveClear = true
        for (by in top downTo top - GROUND_SCAN_UP - GROUND_SCAN_DOWN) {
            pos.set(bx, by, bz)
            val shape = level.getBlockState(pos).getCollisionShape(level, pos)
            val clear = shape.isEmpty
            if (!clear && aboveClear) return by + shape.max(Direction.Axis.Y)
            aboveClear = clear
        }
        return feet
    }

    private fun onTick() {
        tick++
        if (candidates.isNotEmpty()) candidates.values.removeIf { tick - it.tick > CANDIDATE_TICKS }
        if (hops.isEmpty()) return
        val it = hops.entries.iterator()
        while (it.hasNext()) {
            val (id, hop) = it.next()
            hop.age++
            val linger = maxOf(LINGER_TICKS, TRAIL_TICKS)
            val done = hop.hover || hop.age > MAX_HOP_TICKS + linger || (hop.leftAt >= 0 && tick - hop.leftAt > linger)
            if (!done) continue
            it.remove()
            watched -= id
        }
    }

    private fun reset() {
        candidates.clear()
        hops.clear()
        watched.clear()
    }
}
