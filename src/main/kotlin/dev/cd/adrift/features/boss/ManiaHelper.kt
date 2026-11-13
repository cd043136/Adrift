package dev.cd.adrift.features.boss

import dev.cd.adrift.events.LevelEvent
import dev.cd.adrift.events.RenderWorldEvent
import dev.cd.adrift.events.TickEvent
import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module
import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.Colors
import dev.cd.adrift.utils.Location
import dev.cd.adrift.utils.SlayerUtils
import dev.cd.adrift.utils.modMessage
import dev.cd.adrift.utils.render.glow
import dev.cd.adrift.utils.render.lines
import dev.cd.adrift.utils.render.quads
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.VoxelShape
import java.io.BufferedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

object ManiaHelper : Module(
    "Mania",
    Category.BOSS,
    "Draws your mania pattern on the ground, helpful for uneven terrain or overlapping mania.",
    toggle = "Mania Helper",
) {
    private const val MAX_RENDER_RADIUS = 30
    private const val MAX_PLAYER_DISTANCE_SQ = 20.0 * 20.0
    private const val SCAN_DELAY_TICKS = 40
    private const val MAX_LOGGED_SESSIONS = 100
    private const val TEXT_HEIGHT = 9
    private const val LOCKED_SCAN_RADIUS = ManiaGeometry.FOREIGN_RADIUS + ManiaGeometry.FOREIGN_MARGIN
    private const val LOCKED_SCAN_RADIUS_SQ = LOCKED_SCAN_RADIUS * LOCKED_SCAN_RADIUS
    private const val TICK_NANOS = 50_000_000.0
    private const val EDGE_SAMPLES = 8
    // North and south sides sampled west then east, west and east sides sampled north then south.
    private val EDGE_SAMPLE_X = doubleArrayOf(0.25, 0.75, 0.25, 0.75, 0.12, 0.12, 0.88, 0.88)
    private val EDGE_SAMPLE_Z = doubleArrayOf(0.12, 0.12, 0.88, 0.88, 0.25, 0.75, 0.25, 0.75)

    private val renderRadius by int(
        "Render Radius", MAX_RENDER_RADIUS, 3..MAX_RENDER_RADIUS, 1,
        "Only draws columns this close to you; the maximum draws the whole pattern.", " blocks"
    )
    private val style by choice(
        "Style", ManiaStyle.PULSE,
        "How the colours move. Pulse ripples waves out from the centre; Heartbeat thumps, racing faster as coal gets " +
            "closer; Radar sweeps a hand round once per cycle, past a mark where coal lands.",
    )
    private val materials by bool(
        "Material Effects", true,
        "Gives each block its own look: redstone smoulders and flares like embers, smoke drifts over coal and light " +
            "ripples across the safe ring like sun through water.",
    )
    private val glitter by bool(
        "Glitter", false, "Stars twinkle across the floor, more and more of them as coal gets closer.",
    )
    private val shockwaves by bool(
        "Shockwaves", true, "A ring of light bursts out from the centre whenever the pattern changes.",
    )
    private val ripples by bool(
        "Footstep Ripples", false, "Rings spread over the floor from your steps, like walking on water; landing makes a big one.",
    )
    private val lamp by bool(
        "Proximity Lamp", false, "The floor around you is brighter and lit by a soft pool of light; far away it fades back.",
    )
    private val outline by bool("Outline", true, "Traces the edge of each ring, following steps and slabs; the safe ring glows.")
    private val outlineWidth by double("Outline Width", 2.0, 0.5..5.0, 0.25, "Thickness of the outline.")
        .showIf { outline }
    private val marquee by bool("Marquee", false, "Lights chase round the outline like marquee bulbs, neighbouring rings in turn.")
        .showIf { outline }
    private val safeOnly by bool("Safe Only", false, "Only draws the safe (green) ring.")
    private val onlyDifferent by bool("Only Where Different", false, "Skips columns where the real block already shows the right mania block.")
    private val safeColour by color("Safe Colour", Color("55ff5599"), "Safe ring colour.")
    private val terracottaColour by color("Red Terracotta Colour", Color("b4503c80"), "Red terracotta ring colour.")
    private val redstoneColour by color("Redstone Colour", Color("ff202080"), "Redstone ring colour.")
    private val coalColour by color("Coal Colour", Color("1a1a1a99"), "Coal colour.")

    init {
        hud("Status HUD", "Shows safe / move in / move out and the time until coal during your mania.", 0.5f, 0.6f, title = "Mania Status") { preview ->
            val (label, colour) = if (preview) "SAFE | coal 1.2s" to Colors.MINECRAFT_GREEN
            else hudStatus() ?: return@hud 0 to 0
            text(label, 0, 0, colour)
            width(label) to TEXT_HEIGHT
        }
    }

    private val maniaRegex = Regex("""MANIA (\d+(?:\.\d+)?)s""")

    // Packets and server ticks arrive on the netty thread: only these fields are touched there.
    private val serverTick = AtomicLong()
    private val pending = ConcurrentLinkedQueue<ManiaUpdate>()
    @Volatile private var capturing = false
    @Volatile private var captureX = 0
    @Volatile private var captureZ = 0

    // Main thread only.
    private var session: ManiaSession? = null
    private var sessionLevel: Level? = null
    private var wasMania = false
    private var lastTick = 0L
    private var worldScan: WorldScan? = null
    private val batch = ArrayList<ManiaUpdate>()
    private val surfaceCache = SurfaceCache()
    private val colours = Array(ManiaBlock.entries.size) { Colors.WHITE }
    private val contour = ManiaOutline()
    private val fx = ManiaFx()
    private val light = ManiaLight()
    private val epoch = System.nanoTime()
    private var lastTickNanos = 0L
    private var debugWriter: BufferedWriter? = null
    private var debugWriterFailed = false

    private val log = object : ManiaLog {
        override val enabled: Boolean get() = true
        override fun chat(message: String) = file("MSG $message")
        override fun file(line: String) {
            val writer = debugWriter ?: openDebugLog() ?: return
            try {
                writer.write(line)
                writer.newLine()
            } catch (e: IOException) {
                closeDebugLog()
                debugWriterFailed = true
                modMessage("Mania debug log write failed: ${e.message}")
            }
        }
    }

    init {
        onReceive<ClientboundBlockUpdatePacket> { capture(pos.x, pos.y, pos.z, blockState) }
        onReceive<ClientboundSectionBlocksUpdatePacket> {
            if (capturing) runUpdates { pos, state -> capture(pos.x, pos.y, pos.z, state) }
        }

        on<TickEvent.Server> {
            val tick = serverTick.incrementAndGet()
            if (!capturing && !Location.inRift) return@on
            // Queued behind the block packets of this tick, so the world is up to date when it runs.
            mc.execute { onServerTick(tick) }
        }

        on<LevelEvent.Load> { reset() }
        on<LevelEvent.Unload> { reset() }

        on<RenderWorldEvent> {
            val s = session ?: return@on
            if (!s.locked || s.cycleIndex < 0) return@on
            val player = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (level !== sessionLevel) return@on

            val px = player.x
            val pz = player.z
            val fromCentreX = px - (s.centreX + 0.5)
            val fromCentreZ = pz - (s.centreZ + 0.5)
            if (fromCentreX * fromCentreX + fromCentreZ * fromCentreZ > MAX_PLAYER_DISTANCE_SQ) return@on

            surfaceCache.sync(s)
            val radius = renderRadius
            val radiusSq = if (radius >= MAX_RENDER_RADIUS) Double.MAX_VALUE else (radius * radius).toDouble()
            val greenOnly = safeOnly
            val skipMatching = onlyDifferent
            for (block in ManiaBlock.entries) colours[block.ordinal] = colourFor(block)
            val now = System.nanoTime()
            val tickNow = lastTick + ((now - lastTickNanos) / TICK_NANOS).coerceIn(0.0, 1.0)
            fx.materials = materials
            fx.glitter = glitter
            fx.shockwaves = shockwaves
            fx.ripples = ripples
            fx.lamp = lamp
            fx.marquee = outline && marquee
            fx.colours = colours
            fx.begin(style, (now - epoch) / 1.0e9, s, tickNow, fromCentreX, fromCentreZ, player.onGround())
            val lit = fx.lit
            light.begin()
            val outlined = outline
            contour.begin(s.centreX, s.centreZ, s.bossY)

            // One batch for the whole pattern instead of a draw per block
            quads(depth = true) {
                for (i in 0 until ManiaGeometry.offsetCount) {
                    val expected = s.expectedBlock(ManiaGeometry.offsetBand[i]) ?: continue
                    if (greenOnly && expected != ManiaBlock.GREEN) continue
                    val offX = ManiaGeometry.offsetX[i]
                    val offZ = ManiaGeometry.offsetZ[i]
                    val x = s.centreX + offX
                    val z = s.centreZ + offZ
                    val dx = x + 0.5 - px
                    val dz = z + 0.5 - pz
                    if (dx * dx + dz * dz > radiusSq) continue
                    surfaceCache.ensure(i, level, x, z)
                    val surfaces = surfaceCache.surfaces[i]
                    if (surfaces.isEmpty()) continue
                    val safe = expected == ManiaBlock.GREEN
                    val crest = fx.crest(offX, offZ, safe)
                    val colour = fx.tint(colours[expected.ordinal], expected, crest, offX, offZ)
                    for (surface in surfaces) {
                        if (skipMatching && surface.type == expected) continue
                        for (box in surface.tints) {
                            box(box, colour)
                            if (lit) light.face(box, offX, offZ, expected, colour)
                        }
                    }
                    if (!outlined) continue
                    for (side in 0 until 4) {
                        val j = ManiaGeometry.offsetIndex(offX + ManiaOutline.SIDE_DX[side], offZ + ManiaOutline.SIDE_DZ[side])
                        val beyondBand = if (j >= 0) ManiaGeometry.offsetBand[j] else ManiaGeometry.OUTSIDE
                        val beyond = if (j >= 0) s.expectedBlock(beyondBand) else null
                        if (zoneRank(expected) <= zoneRank(beyond)) continue
                        val boundary = minOf(ManiaGeometry.offsetBand[i], beyondBand)
                        for (surface in surfaces) {
                            if (skipMatching && surface.type == expected) continue
                            contour.edge(offX, offZ, side, surface.edges, expected, crest, boundary)
                        }
                    }
                }
            }
            if (!light.isEmpty) glow { light.draw(this, fx, s.centreX + 0.5, s.centreZ + 0.5) }
            if (!contour.isEmpty) lines { contour.draw(this, outlineWidth.toFloat(), colours, fx) }
        }
    }

    override fun onDisable() {
        reset()
    }

    private fun capture(x: Int, y: Int, z: Int, state: BlockState) {
        if (!capturing) return
        val dx = x - captureX
        val dz = z - captureZ
        if (dx * dx + dz * dz > ManiaGeometry.CAPTURE_RADIUS_SQ) return
        pending.add(ManiaUpdate(serverTick.get(), x, y, z, ManiaBlock.of(state)))
    }

    private fun onServerTick(tick: Long) {
        if (!enabled) return
        lastTick = tick
        lastTickNanos = System.nanoTime()
        drainPending(tick)
        updateLifecycle(tick)
        val s = session ?: return
        s.tick(tick)
        runWorldScan(s, tick)
        if (session === s) logTick(s, tick)
    }

    /** Batches are everything that arrived before this tick's ping, grouped by the tick they arrived in. */
    private fun drainPending(tick: Long) {
        var batchTick = Long.MIN_VALUE
        while (true) {
            val update = pending.peek() ?: break
            if (update.tick >= tick) break
            pending.poll()
            if (session == null) continue
            if (update.tick != batchTick) {
                flushBatch(batchTick, synthetic = false)
                batchTick = update.tick
            }
            batch += update
        }
        flushBatch(batchTick, synthetic = false)
    }

    private fun flushBatch(tick: Long, synthetic: Boolean) {
        if (batch.isEmpty()) return
        val s = session
        if (s != null) {
            for (update in batch) surfaceCache.invalidate(s, update.x, update.z)
            s.processBatch(tick, batch, synthetic)
        }
        batch.clear()
    }

    private fun updateLifecycle(tick: Long) {
        val level = mc.level
        val boss = SlayerUtils.boss?.takeIf { it.isAlive }
        val timerText = boss?.let { (SlayerUtils.timer ?: level?.getEntity(it.id + 2))?.plainTextName }
        val inMania = level != null && Location.inRift && timerText?.contains("MANIA") == true

        if (session != null) {
            if (!inMania) endSession(if (boss == null) "boss gone" else "mania over")
            else if (sessionLevel !== level) endSession("world changed")
        }
        if (!inMania) {
            wasMania = false
            return
        }

        if (!wasMania) {
            wasMania = true
            startSession(ManiaSession(tick, Mth.floor(boss.x), Mth.floor(boss.z), boss.x, boss.y, boss.z, log), level)
        }
        session?.maniaRemaining = maniaRegex.find(timerText)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun startSession(s: ManiaSession, level: Level) {
        endSession("new mania")
        session = s
        sessionLevel = level
        pending.clear()
        captureX = s.guessX
        captureZ = s.guessZ
        capturing = true
        debugWriterFailed = false
        log.file("START tick=${s.startTick} Mania started, guess (${s.guessX}, ${s.guessZ}), boss y %.2f".format(s.bossY))
    }

    private fun endSession(reason: String) {
        capturing = false
        pending.clear()
        batch.clear()
        worldScan = null
        val s = session ?: return
        session = null
        sessionLevel = null
        surfaceCache.clear()
        s.finish(reason)
        closeDebugLog()
    }

    private fun reset() {
        endSession("reset")
        wasMania = false
    }

    /** Fallback for when the server delivers the pattern as chunk resends instead of block update packets. */
    private fun runWorldScan(s: ManiaSession, tick: Long) {
        if (s.packetsSeen > 0 || tick - s.startTick < SCAN_DELAY_TICKS) {
            worldScan = null
            return
        }
        val player = mc.player ?: return
        val level = sessionLevel ?: return
        val scan = worldScan ?: WorldScan(s.guessX, s.guessZ, Mth.floor(s.bossY)).also {
            worldScan = it
            log.chat("No block packets after ${SCAN_DELAY_TICKS}t, scanning the world instead")
        }
        scan.scan(level, player.blockY, s, tick, batch)
        flushBatch(tick, synthetic = true)
    }

    private fun hudStatus(): Pair<String, Color>? {
        val s = session?.takeIf { it.locked && it.cycleIndex >= 0 } ?: return null
        val player = mc.player ?: return null
        val band = s.bandAt(Mth.floor(player.x), Mth.floor(player.z))
        val ring = s.greenRing
        val status = when {
            band == ManiaGeometry.OUTSIDE -> "OUTSIDE CIRCLE"
            ring == 0 -> "UNKNOWN RING"
            band == ring -> "SAFE"
            band > ring -> "MOVE IN"
            else -> "MOVE OUT"
        }
        val timing = s.ticksUntilCoal(lastTick)?.let { "coal %.1fs".format(it / 20.0) }
            ?: if (s.phase == ManiaSession.Phase.COAL) "COAL" else null
        val label = if (timing == null) status else "$status | $timing"
        return label to if (status == "SAFE") Colors.MINECRAFT_GREEN else Colors.MINECRAFT_RED
    }

    /** Each boundary is drawn once, by the zone that matters more: safe over danger over coal over nothing. */
    private fun zoneRank(block: ManiaBlock?): Int = when (block) {
        ManiaBlock.GREEN -> 3
        ManiaBlock.RED, ManiaBlock.REDSTONE -> 2
        ManiaBlock.COAL -> 1
        ManiaBlock.NONE, null -> 0
    }

    private fun colourFor(block: ManiaBlock): Color = when (block) {
        ManiaBlock.GREEN -> safeColour
        ManiaBlock.RED -> terracottaColour
        ManiaBlock.REDSTONE -> redstoneColour
        ManiaBlock.COAL, ManiaBlock.NONE -> coalColour
    }

    private fun logTick(s: ManiaSession, tick: Long) {
        val boss = SlayerUtils.boss
        val player = mc.player
        val bossPos = boss?.let { "%.3f %.3f %.3f".format(it.x, it.y, it.z) } ?: "- - -"
        val playerPos = player?.let { "%.3f %.3f %.3f %.1f".format(it.x, it.y, it.z, it.health) } ?: "- - - -"
        log.file("T $tick boss $bossPos player $playerPos ${s.phase} ring=${s.greenRing} mania=${s.maniaRemaining}")
        if (tick % 20 != 0L) return
        try {
            debugWriter?.flush()
        } catch (ignored: IOException) {
            closeDebugLog()
        }
    }

    private fun openDebugLog(): BufferedWriter? {
        if (debugWriterFailed) return null
        return try {
            val path = FabricLoader.getInstance().configDir.resolve("adrift").resolve("mania_debug.log")
            Files.createDirectories(path.parent)
            trimDebugLog(path)
            Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                .also { debugWriter = it }
        } catch (e: IOException) {
            debugWriterFailed = true
            modMessage("Mania debug log unavailable: ${e.message}")
            null
        }
    }

    /** Drops the oldest sessions so that, with the one about to start, at most [MAX_LOGGED_SESSIONS] remain. */
    private fun trimDebugLog(path: Path) {
        if (!Files.exists(path)) return
        val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
        val starts = lines.indices.filter { lines[it].startsWith("START ") }
        val excess = starts.size - (MAX_LOGGED_SESSIONS - 1)
        if (excess <= 0) return
        Files.write(path, lines.subList(starts[excess], lines.size), StandardCharsets.UTF_8)
    }

    private fun closeDebugLog() {
        val writer = debugWriter ?: return
        debugWriter = null
        try {
            writer.close()
        } catch (ignored: IOException) {
        }
    }

    /** [edges] holds the surface height along each side of the block, see [edgeHeights]. */
    private class Surface(val type: ManiaBlock, val tints: List<AABB>, val edges: DoubleArray)

    /**
     * Height of the block's top just inside each of its four sides, two samples per side (one per half, which is as
     * fine as stairs get), indexed `side * 2 + half` in [ManiaOutline] side order. Parts of a side the shape doesn't
     * reach (fences, walls) take the shape's top.
     */
    private fun edgeHeights(boxes: List<AABB>, y: Int, top: Double): DoubleArray {
        val heights = DoubleArray(EDGE_SAMPLES) { top }
        if (boxes.size <= 1) return heights
        for (k in 0 until EDGE_SAMPLES) {
            val sx = EDGE_SAMPLE_X[k]
            val sz = EDGE_SAMPLE_Z[k]
            var best = Double.NEGATIVE_INFINITY
            for (box in boxes) {
                if (sx < box.minX || sx > box.maxX || sz < box.minZ || sz > box.maxZ) continue
                if (box.maxY > best) best = box.maxY
            }
            if (best != Double.NEGATIVE_INFINITY) heights[k] = y + best
        }
        return heights
    }

    /**
     * Shape-accurate block tint boxes: the block's own collision boxes at (x, y, z), grown by [epsilon] so they
     * don't z-fight the block faces, so stairs/slabs/fences hug their geometry.
     */
    private fun tintBoxes(boxes: List<AABB>, x: Int, y: Int, z: Int, epsilon: Double = 0.002): List<AABB> =
        boxes.map { it.move(x.toDouble(), y.toDouble(), z.toDouble()).inflate(epsilon) }

    /** Standable surfaces per column within the mania's vertical reach, keyed by offset index of the current centre. */
    private class SurfaceCache {
        private val valid = BooleanArray(ManiaGeometry.offsetCount)
        val surfaces = Array(ManiaGeometry.offsetCount) { emptyList<Surface>() }
        private val pos = BlockPos.MutableBlockPos()
        private var owner: ManiaSession? = null
        private var version = -1
        private var baseY = 0

        fun sync(s: ManiaSession) {
            if (s === owner && s.centreVersion == version) return
            owner = s
            version = s.centreVersion
            baseY = Mth.floor(s.bossY)
            valid.fill(false)
        }

        fun invalidate(s: ManiaSession, x: Int, z: Int) {
            if (s !== owner) return
            val i = ManiaGeometry.offsetIndex(x - s.centreX, z - s.centreZ)
            if (i >= 0) valid[i] = false
        }

        fun ensure(i: Int, level: Level, x: Int, z: Int) {
            if (!valid[i]) compute(i, level, x, z)
        }

        fun clear() {
            owner = null
            valid.fill(false)
        }

        private fun compute(i: Int, level: Level, x: Int, z: Int) {
            var found: ArrayList<Surface>? = null
            // Passable blocks directly above the current y; the scan starts HEADROOM above the reach so the top is checked too.
            var clearance = 0
            for (y in baseY + REACH_UP + HEADROOM downTo baseY - REACH_DOWN) {
                pos.set(x, y, z)
                val state = level.getBlockState(pos)
                val shape = state.getCollisionShape(level, pos)
                if (shape.isEmpty) {
                    clearance++
                    continue
                }
                if (clearance >= HEADROOM && y <= baseY + REACH_UP) {
                    val boxes = shape.toAabbs()
                    val top = y + shape.max(Direction.Axis.Y)
                    (found ?: ArrayList<Surface>(2).also { found = it }) +=
                        Surface(ManiaBlock.of(state), tintBoxes(boxes, x, y, z), edgeHeights(boxes, y, top))
                }
                clearance = 0
            }
            surfaces[i] = found ?: emptyList()
            valid[i] = true
        }

        private companion object {
            /** Render this far from the boss's feet; around 10 blocks out the player takes damage anyway. */
            const val REACH_UP = 10
            const val REACH_DOWN = 10
            const val HEADROOM = 2
        }
    }

    /** Diffs the topmost mania block per column between ticks to synthesise batches. */
    private class WorldScan(private val originX: Int, private val originZ: Int, private val bossY: Int) {
        private val columnX: IntArray
        private val columnZ: IntArray
        private val lastType: Array<ManiaBlock>
        private val lastY: IntArray
        private val pos = BlockPos.MutableBlockPos()
        private var levels = IntArray(0)
        private var levelsPlayerY = Int.MIN_VALUE

        init {
            val r = ManiaGeometry.CAPTURE_RADIUS
            val offsets = (-r..r).flatMap { dx -> (-r..r).filter { dz -> dx * dx + dz * dz <= r * r }.map { dx to it } }
            columnX = IntArray(offsets.size) { offsets[it].first }
            columnZ = IntArray(offsets.size) { offsets[it].second }
            lastType = Array(offsets.size) { ManiaBlock.NONE }
            lastY = IntArray(offsets.size)
        }

        /** Once [s] has a locked centre, columns too far from it to tell foreign patterns apart are skipped. */
        fun scan(level: Level, playerY: Int, s: ManiaSession, tick: Long, out: MutableList<ManiaUpdate>) {
            if (playerY != levelsPlayerY) {
                levelsPlayerY = playerY
                levels = ((playerY - 4..playerY + 2) + (bossY - 8..bossY)).distinct().sortedDescending().toIntArray()
            }
            for (i in columnX.indices) {
                val x = originX + columnX[i]
                val z = originZ + columnZ[i]
                if (s.locked) {
                    val dx = x - s.centreX
                    val dz = z - s.centreZ
                    if (dx * dx + dz * dz > LOCKED_SCAN_RADIUS_SQ) continue
                }
                var type = ManiaBlock.NONE
                var foundY = 0
                for (y in levels) {
                    val found = ManiaBlock.of(level.getBlockState(pos.set(x, y, z)))
                    if (found == ManiaBlock.NONE) continue
                    type = found
                    foundY = y
                    break
                }
                if (type == lastType[i]) continue
                out += ManiaUpdate(tick, x, if (type == ManiaBlock.NONE) lastY[i] else foundY, z, type)
                lastType[i] = type
                if (type != ManiaBlock.NONE) lastY[i] = foundY
            }
        }
    }
}
