package dev.cd.adrift.features.boss

import dev.cd.adrift.events.LevelEvent
import dev.cd.adrift.events.RenderWorldEvent
import dev.cd.adrift.events.TickEvent
import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module
import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.Location
import dev.cd.adrift.utils.SlayerUtils
import dev.cd.adrift.utils.render.quads
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
//? if >=26.2 {
import net.minecraft.world.entity.EntityTypes
//?} else
//import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.Block
import net.minecraft.core.registries.BuiltInRegistries
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Draws where our own boss's Stonewrath will land: a circle that follows us (as the server sees us) while the boss
 * is up, then freezes on the landing spot once the server locks its target. See [StonewrathTracker] for the logic;
 * this feeds it packets and draws it. Lootshare bosses are skipped.
 */
object StonewrathDisplay : Module(
    "Stonewrath",
    Category.BOSS,
    "Draws a circle where Stonewrath will land: follows you until the boss picks its target, then stays put.",
    toggle = "Stonewrath Display",
    enabledByDefault = false,
) {
    private val followColour by color("Follow Colour", Color("ffd23cff"), "Circle colour while it's still following you.")
    private val lockedColour by color("Locked Colour", Color("ff3030ff"), "Circle colour once the landing spot is locked.")
    private val ringWidth by double("Ring Width", 0.12, 0.04..0.5, 0.02, "Width of the circle's edge.", " blocks")
    private val fillOpacity by double("Fill Opacity", 0.15, 0.0..1.0, 0.05, "Opacity of the inside of the circle, relative to the colour's.")
    private val hideBlocks = bool("Hide Blocks", true, "Hides the falling blocks Stonewrath spawns around you.")
    private val showWhileRising by bool("Show While Rising", false, "Shows a faint circle on you from when the boss rises, before its blocks appear.")

    init {
        hud("Fly Countdown", "Shows the time until the boss flies up for Stonewrath.", 0.5f, 0.34f, default = true, title = "Stonewrath Fly Countdown", scale = 1.5f) { preview ->
            val (label, colour) = if (preview) "Fly 1.8s" to followColour
            else flyCountdown() ?: return@hud 0 to 0
            text(label, 0, 0, colour)
            width(label) to mc.font.lineHeight
        }
        hud("Lock Countdown", "Shows the time until the landing spot locks, from when the boss flies up.", 0.5f, 0.38f, default = true, title = "Stonewrath Lock Countdown", scale = 1.5f) { preview ->
            val (label, colour) = if (preview) "Lock 1.2s" to followColour
            else lockCountdown() ?: return@hud 0 to 0
            text(label, 0, 0, colour)
            width(label) to mc.font.lineHeight
        }
        hud("Land Countdown", "Shows the time until Stonewrath lands, from when the landing spot locks.", 0.5f, 0.42f, default = true, title = "Stonewrath Land Countdown", scale = 1.5f) { preview ->
            val (label, colour) = if (preview) "Land 2.4s" to lockedColour
            else landCountdown() ?: return@hud 0 to 0
            text(label, 0, 0, colour)
            width(label) to mc.font.lineHeight
        }
    }

    private const val SEGMENTS = 64
    /** Almost every ground block lands within this. */
    private const val RADIUS = 4.0
    /** How far from our boss a block may spawn to be its own (the boss hovers over its target). */
    private const val BOSS_RANGE = 8.0
    private const val CLAW_SPEED = 0.3
    private const val MIN_VY = 0.1
    private const val MAX_VY = 0.25
    private const val SOUND_RANGE = 4.0
    private const val HISTORY_SECONDS = 1.5
    private const val DEFAULT_LAG_MS = 225.0
    private const val EXTRA_LAG_MS = 100.0
    /** The boss rises ~14 blocks; this high counts as up, back below [LANDED_HEIGHT] counts as landed. */
    private const val RISEN_HEIGHT = 5.0
    private const val LANDED_HEIGHT = 2.0
    private const val Y_OFFSET = 0.02
    /** Per-frame lerp at 60 fps. */
    private const val SMOOTHING = 0.3

    // By name: the stained glass constants differ between versions
    private val GROUND_BLOCKS = setOf("gray_stained_glass", "red_stained_glass", "redstone_block")

    private fun now() = System.nanoTime() / 1.0e9

    // Main thread only: packets hand over through mc.execute
    private val tracker = StonewrathTracker()
    private var hadToken = false
    private var bossStartY = 0.0
    private var bossRose = false

    // Our boss's ground blocks, id to ticks since hidden
    private val hidden = HashMap<Int, Int>()
    /** Ticks a hidden id is kept before its entity has to exist. */
    private const val HIDE_GRACE_TICKS = 5
    private const val HIDE_MAX_TICKS = 200

    /** Called from the entity render dispatcher. */
    @JvmStatic
    fun isHidden(entity: Entity): Boolean = hidden.isNotEmpty() && entity.id in hidden

    private class Sample(val t: Double, val x: Double, val y: Double, val z: Double)
    private val history = ArrayDeque<Sample>()

    // Drawn position, eased towards the tracker's target
    private var shown = false
    private var drawX = 0.0
    private var drawZ = 0.0
    private var lastFrame = 0.0

    init {
        onReceive<ClientboundAddEntityPacket> {
            //? if >=26.2 {
            if (type != EntityTypes.FALLING_BLOCK) return@onReceive
            //?} else
            //if (type != EntityType.FALLING_BLOCK) return@onReceive
            val v = movement
            if (hypot(v.x, v.z) > CLAW_SPEED || v.y !in MIN_VY..MAX_VY) return@onReceive
            // By block, not raw state id: those change between versions
            if (BuiltInRegistries.BLOCK.getKey(Block.stateById(data).block).path !in GROUND_BLOCKS) return@onReceive
            val t = now()
            val id = id
            val x = x
            val y = y
            val z = z
            mc.execute { onBlock(t, id, x, y, z) }
        }

        onReceive<ClientboundSoundPacket> {
            val path = sound.value().location().path
            val lever = path == "block.lever.click"
            if (!lever && path != "entity.generic.explode") return@onReceive
            val t = now()
            val x = x
            val y = y
            val z = z
            mc.execute { if (lever) onLever(t, x, y, z) else if (tracker.active) tracker.explode(t, x, z) }
        }

        on<TickEvent.End> { tick() }
        on<LevelEvent.Load> { reset() }
        on<LevelEvent.Unload> { reset() }

        on<RenderWorldEvent> { render() }

        hideBlocks.onChange { if (!hideBlocks()) hidden.clear() }
    }

    override fun onDisable() {
        reset()
    }

    private fun onBlock(t: Double, id: Int, x: Double, y: Double, z: Double) {
        if (!tracker.active) return
        val boss = SlayerUtils.boss ?: return
        if (hypot(x - boss.x, z - boss.z) > BOSS_RANGE) return
        if (hideBlocks()) hidden[id] = 0
        tracker.block(t, x, y, z)
    }

    private fun onLever(t: Double, x: Double, y: Double, z: Double) {
        if (!tracker.active) return
        val player = mc.player ?: return
        val dx = x - player.x
        val dy = y - player.y
        val dz = z - player.z
        if (dx * dx + dy * dy + dz * dz > SOUND_RANGE * SOUND_RANGE) return
        tracker.leverClick(t)
    }

    private fun tick() {
        mc.level?.let { pruneHidden(it) } ?: hidden.clear()
        val t = now()
        val player = mc.player
        if (player != null) {
            history.addLast(Sample(t, player.x, player.y, player.z))
            while (history.size > 2 && history.first().t < t - HISTORY_SECONDS) history.removeFirst()
        }

        // Own boss only: lootshare bosses never start one
        val boss = SlayerUtils.boss
        if (!Location.inRift || boss == null || boss.isRemoved || !boss.isAlive) {
            hadToken = false
            if (tracker.active) end()
            return
        }

        val name = mc.level?.getEntity(boss.id + 2)?.plainTextName ?: ""
        if (tracker.active && (SlayerUtils.inMania || name.contains("MANIA"))) end()

        val token = name.contains("STONEWRATH")
        if (token && !hadToken && !tracker.active) {
            tracker.start(t)
            bossStartY = boss.y
            bossRose = false
        }
        hadToken = token

        if (tracker.active) {
            val ground = tracker.groundY.takeUnless { it.isNaN() } ?: bossStartY
            if (boss.y > maxOf(ground, bossStartY) + RISEN_HEIGHT) bossRose = true
            // Back down after the dive: give the impact a moment, then hide
            if (bossRose && boss.y < ground + LANDED_HEIGHT) tracker.finishAt(t + StonewrathTracker.IMPACT_HOLD)
            tracker.update(t, lagged(t))
        }
        if (!tracker.active) shown = false
    }

    /** Forgets hidden blocks once they've landed and gone. */
    private fun pruneHidden(level: ClientLevel) {
        if (hidden.isEmpty()) return
        val it = hidden.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.setValue(e.value + 1)
            val gone = e.value > HIDE_GRACE_TICKS && level.getEntity(e.key)?.takeUnless { it.isRemoved } == null
            if (gone || e.value > HIDE_MAX_TICKS) it.remove()
        }
    }

    /** Where the server last saw us: our position from ping + 100 ms ago. */
    private fun lagged(t: Double): StonewrathTracker.Pos? {
        if (history.isEmpty()) return null
        val player = mc.player
        val ping = player?.let { mc.connection?.getPlayerInfo(it.uuid)?.latency } ?: 0
        val lagMs = if (ping > 0) ping + EXTRA_LAG_MS else DEFAULT_LAG_MS
        val at = t - lagMs / 1000.0
        val first = history.first()
        if (at <= first.t) return StonewrathTracker.Pos(first.x, first.y, first.z)
        for (i in history.size - 1 downTo 1) {
            val a = history[i - 1]
            if (a.t > at) continue
            val b = history[i]
            val f = if (b.t > a.t) ((at - a.t) / (b.t - a.t)).coerceIn(0.0, 1.0) else 1.0
            return StonewrathTracker.Pos(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f)
        }
        val last = history.last()
        return StonewrathTracker.Pos(last.x, last.y, last.z)
    }

    private fun visible(): Boolean = when (tracker.phase) {
        StonewrathTracker.Phase.IDLE -> false
        StonewrathTracker.Phase.ARMED -> showWhileRising && tracker.rising
        else -> true
    } && tracker.hasTarget && !tracker.groundY.isNaN()

    /** Until the boss flies up; gone once it has. */
    private fun flyCountdown(): Pair<String, Color>? {
        if (!tracker.active || bossRose) return null
        val left = tracker.t0 + StonewrathTracker.RISE_TIME - now()
        if (left <= 0.0) return null
        return "Fly %.1fs".format(left) to followColour
    }

    private fun pastFly(t: Double) = bossRose || t >= tracker.t0 + StonewrathTracker.RISE_TIME

    /** From when the boss flies up until the landing spot locks (which can come early). */
    private fun lockCountdown(): Pair<String, Color>? {
        if (!tracker.active || tracker.phase == StonewrathTracker.Phase.LOCKED) return null
        val t = now()
        if (!pastFly(t)) return null
        val left = (tracker.t0 + StonewrathTracker.LOCK_TIME - t).coerceAtLeast(0.0)
        return "Lock %.1fs".format(left) to followColour
    }

    /** From when the landing spot locks until the boss lands. */
    private fun landCountdown(): Pair<String, Color>? {
        if (!tracker.active || tracker.phase != StonewrathTracker.Phase.LOCKED) return null
        val left = (tracker.t0 + StonewrathTracker.LANDING_TIME - now()).coerceAtLeast(0.0)
        return "Land %.1fs".format(left) to lockedColour
    }

    private fun RenderWorldEvent.render() {
        val t = now()
        val dt = (t - lastFrame).coerceIn(0.0, 0.25)
        lastFrame = t
        if (!visible()) {
            shown = false
            return
        }
        if (!shown) {
            shown = true
            drawX = tracker.targetX
            drawZ = tracker.targetZ
        } else {
            val k = 1.0 - (1.0 - SMOOTHING).pow(dt * 60.0)
            drawX += (tracker.targetX - drawX) * k
            drawZ += (tracker.targetZ - drawZ) * k
        }

        val base = when (tracker.phase) {
            StonewrathTracker.Phase.LOCKED -> lockedColour
            StonewrathTracker.Phase.ARMED -> followColour.multiplyAlpha(0.5f)
            else -> followColour
        }
        val fill = base.multiplyAlpha(fillOpacity.toFloat())
        val y = tracker.groundY + Y_OFFSET
        val r = RADIUS
        val half = ringWidth / 2.0
        val inner = (r - half).coerceAtLeast(0.0)
        val outer = r + half
        val cx = drawX
        val cz = drawZ

        quads(depth = false) {
            for (i in 0 until SEGMENTS) {
                val a0 = 2 * PI * i / SEGMENTS
                val a1 = 2 * PI * (i + 1) / SEGMENTS
                val c0 = cos(a0)
                val s0 = sin(a0)
                val c1 = cos(a1)
                val s1 = sin(a1)
                quad(
                    base,
                    cx + c0 * inner, y, cz + s0 * inner,
                    cx + c0 * outer, y, cz + s0 * outer,
                    cx + c1 * outer, y, cz + s1 * outer,
                    cx + c1 * inner, y, cz + s1 * inner,
                )
                if (fill.alpha > 0) quad(
                    fill,
                    cx, y, cz,
                    cx + c0 * inner, y, cz + s0 * inner,
                    cx + c1 * inner, y, cz + s1 * inner,
                    cx + c1 * inner, y, cz + s1 * inner,
                )
            }
        }
    }

    private fun end() {
        tracker.reset()
        shown = false
    }

    private fun reset() {
        end()
        hadToken = false
        bossRose = false
        history.clear()
        hidden.clear()
    }
}
