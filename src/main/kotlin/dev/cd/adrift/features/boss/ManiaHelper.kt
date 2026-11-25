package dev.cd.adrift.features.boss

import dev.cd.adrift.Adrift
import dev.cd.adrift.events.LevelEvent
import dev.cd.adrift.features.boss.ManiaFx.Companion.smoothstep
import dev.cd.adrift.events.RenderWorldEvent
import dev.cd.adrift.events.TickEvent
import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module
import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.Colors
import dev.cd.adrift.utils.Location
import dev.cd.adrift.utils.SlayerUtils
import dev.cd.adrift.utils.modMessage
import dev.cd.adrift.utils.render.cameraPos
import dev.cd.adrift.utils.render.glow
import dev.cd.adrift.utils.render.lines
import dev.cd.adrift.utils.render.opaqueQuads
import dev.cd.adrift.utils.render.quads
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.LongArrayList
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.io.BufferedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

object ManiaHelper : Module(
    "Mania",
    Category.BOSS,
    "Draws your mania pattern on the ground, helpful for uneven terrain or overlapping mania.",
    toggle = "Mania Helper",
) {
    private const val MAX_PLAYER_DISTANCE_SQ = 20.0 * 20.0
    private const val SCAN_DELAY_TICKS = 40
    private const val MAX_LOGGED_SESSIONS = 100
    /** Roughly 50 MB of debug log; older sessions are dropped beyond it. */
    private const val MAX_LOG_CHARS = 50L * 1024 * 1024
    /** Debug log lines are handed to the log thread in chunks of about this many chars. */
    private const val DEBUG_CHUNK_CHARS = 64 * 1024
    /** Chunks waiting for the log thread before further output is dropped. */
    private const val DEBUG_QUEUE_SIZE = 256
    private const val TEXT_HEIGHT = 9
    private const val LOCKED_SCAN_RADIUS = ManiaGeometry.FOREIGN_RADIUS + ManiaGeometry.FOREIGN_MARGIN
    private const val LOCKED_SCAN_RADIUS_SQ = LOCKED_SCAN_RADIUS * LOCKED_SCAN_RADIUS
    private const val TICK_NANOS = 50_000_000.0
    /** Mania seconds left when the breakdance has fully stopped. */
    private const val DANCE_STOP = 1.0
    /** Seconds the breakdance takes to fade in at the mania start and out before [DANCE_STOP]. */
    private const val DANCE_FADE = 0.4
    private val CURTAIN_TINT = Color(0xFFE2FFF5.toInt())
    private const val EDGE_SAMPLES = 8
    // North and south sides sampled west then east, west and east sides sampled north then south.
    private val EDGE_SAMPLE_X = doubleArrayOf(0.25, 0.75, 0.25, 0.75, 0.12, 0.12, 0.88, 0.88)
    private val EDGE_SAMPLE_Z = doubleArrayOf(0.12, 0.12, 0.88, 0.88, 0.25, 0.75, 0.25, 0.75)

    private val style by choice(
        "Style", ManiaStyle.STATIC,
        "How the floor looks. Static keeps it still; Disco throws spots, lasers and flashing tiles across the floor " +
            "to a beat, with a strobe when coal hits.",
    )
    private val pulse by bool("Pulse", true, "Waves ripple out from the centre; works with either style.")
    private val mirrorBall by bool("Mirror Ball", true, "Hangs a spinning mirror ball over the boss, beaming the disco spots down.")
        .showIf { style == ManiaStyle.DISCO }
    private val breakdance by bool(
        "Breakdance", true,
        "Your boss breakdances to the beat until the last second of mania. Boss Highlight is off while it dances.",
    ).showIf { style == ManiaStyle.DISCO }
    private val outline by bool("Outline", true, "Traces the edge of each ring, following steps and slabs; the safe ring glows.")
    private val outlineWidth by double("Outline Width", 2.0, 0.5..5.0, 0.25, "Thickness of the outline.")
        .showIf { outline }
    private val curtain by bool("Light Curtain", false, "A shimmering curtain of light rises off the edges of the safe ring.")
    private val safeOnly by bool("Safe Only", false, "Only draws the safe (green) ring.")
    private val onlyDifferent by bool("Only Where Different", false, "Skips columns where the real block already shows the right mania block.")
    private val safeColour by color("Safe Colour", Color("55ff5599"), "Safe ring colour.")
    private val terracottaColour by color("Red Terracotta Colour", Color("b4503c80"), "Red terracotta ring colour.")
    private val redstoneColour by color("Redstone Colour", Color("ff202080"), "Redstone ring colour.")
    private val coalColour by color("Coal Colour", Color("1a1a1a99"), "Coal colour.")
    private val debugLog by bool(
        "Debug Log", false,
        "Writes mania detection diagnostics to config/adrift/mania_debug.log, for reporting detection problems.",
    )

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
    private var sessionNanos = 0L
    /** Disco beat the breakdance starts on, the first one after the mania began. */
    private var danceStartBeat = 0.0

    // Debug log, off unless [debugLog] is on. Lines are buffered on the main thread and handed in chunks to one
    // background thread, which owns the file; the main thread never touches the disk.
    private val debugIo = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(DEBUG_QUEUE_SIZE),
        { task -> Thread(task, "Adrift Mania Log").apply { isDaemon = true } },
        { _, _ -> Adrift.LOGGER.warn("Mania debug log is backed up, dropping output") },
    ).apply { allowCoreThreadTimeOut(true) }
    private val debugBuffer = StringBuilder()
    private var debugOpen = false
    @Volatile private var debugWriterFailed = false
    /** Background thread only. */
    private var debugWriter: BufferedWriter? = null

    private val log = object : ManiaLog {
        override val enabled: Boolean get() = debugLog && !debugWriterFailed
        override fun chat(message: String) = file("MSG $message")
        override fun file(line: String) {
            if (!enabled) return
            if (!debugOpen) {
                debugOpen = true
                debugIo.execute(::openDebugLog)
            }
            debugBuffer.append(line).append(System.lineSeparator())
            if (debugBuffer.length >= DEBUG_CHUNK_CHARS) flushDebugBuffer(toDisk = false)
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

            val fromCentreX = player.x - (s.centreX + 0.5)
            val fromCentreZ = player.z - (s.centreZ + 0.5)
            if (fromCentreX * fromCentreX + fromCentreZ * fromCentreZ > MAX_PLAYER_DISTANCE_SQ) return@on

            surfaceCache.sync(s)
            val greenOnly = safeOnly
            val skipMatching = onlyDifferent
            for (block in ManiaBlock.entries) colours[block.ordinal] = colourFor(block)
            val now = System.nanoTime()
            fx.pulse = pulse
            fx.curtain = curtain
            fx.begin(style, (now - epoch) / 1.0e9, s)
            val lit = fx.lit
            light.begin()
            val outlined = outline
            val traced = outlined || fx.curtain
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
                    surfaceCache.ensure(i, level, x, z)
                    val surfaces = surfaceCache.surfaces[i]
                    if (surfaces.isEmpty()) continue
                    val safe = expected == ManiaBlock.GREEN
                    val crest = fx.crest(offX, offZ, safe)
                    val colour = fx.tint(colours[expected.ordinal], expected, crest)
                    for (surface in surfaces) {
                        if (skipMatching && surface.type == expected) continue
                        for (box in surface.tints) {
                            box(box, colour)
                            if (lit) light.face(box)
                        }
                    }
                    if (!traced) continue
                    for (side in 0 until 4) {
                        val j = ManiaGeometry.offsetIndex(offX + ManiaOutline.SIDE_DX[side], offZ + ManiaOutline.SIDE_DZ[side])
                        val beyondBand = if (j >= 0) ManiaGeometry.offsetBand[j] else ManiaGeometry.OUTSIDE
                        val beyond = if (j >= 0) s.expectedBlock(beyondBand) else null
                        if (zoneRank(expected) <= zoneRank(beyond)) continue
                        for (surface in surfaces) {
                            if (skipMatching && surface.type == expected) continue
                            contour.edge(offX, offZ, side, surface.edges, expected, crest)
                        }
                    }
                }
            }
            val curtainLit = fx.curtain && !contour.isEmpty
            val ball = fx.style == ManiaStyle.DISCO && mirrorBall
            val dancing = ManiaDance.hasFx()
            val middleX = s.centreX + 0.5
            val middleZ = s.centreZ + 0.5
            if (ball) opaqueQuads { fx.disco.ball(this, middleX, s.bossY, middleZ, cameraPos) }
            if (!light.isEmpty || curtainLit || ball || dancing) glow {
                light.draw(this, fx, middleX, middleZ)
                if (curtainLit) contour.curtain(this, fx, colours[ManiaBlock.GREEN.ordinal].mix(CURTAIN_TINT, 0.5))
                if (ball) fx.disco.beams(this, middleX, s.bossY, middleZ, cameraPos)
                if (dancing) ManiaDance.drawFx(this, cameraPos)
            }
            if (ball) lines { fx.disco.chain(this, middleX, s.bossY, middleZ) }
            if (outlined && !contour.isEmpty) lines { contour.draw(this, outlineWidth.toFloat(), colours) }
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
        if (session === s && log.enabled) logTick(s, tick)
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
            val level = sessionLevel
            for (update in batch) surfaceCache.update(s, level, update.x, update.y, update.z)
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
        sessionNanos = System.nanoTime()
        danceStartBeat = ceil(discoBeat(sessionNanos))
        debugWriterFailed = false
        if (log.enabled) log.file("START tick=${s.startTick} Mania started, guess (${s.guessX}, ${s.guessZ}), boss y %.2f".format(s.bossY))
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

    /**
     * How far the boss's breakdance has faded in at [now], 0 when it isn't dancing. Runs from the mania start, in the
     * disco style, until [DANCE_STOP] seconds of mania are left.
     */
    internal fun danceWeight(now: Long): Double {
        if (!enabled || style != ManiaStyle.DISCO || !breakdance) return 0.0
        val s = session ?: return 0.0
        if (sessionLevel !== mc.level) return 0.0
        val remaining = s.maniaRemaining ?: return 0.0
        // The timer only updates each server tick; count down between them.
        val left = remaining - ((now - lastTickNanos) / 1.0e9).coerceIn(0.0, TICK_NANOS / 1.0e9)
        val since = (now - sessionNanos) / 1.0e9
        return smoothstep(0.0, DANCE_FADE, since) * smoothstep(DANCE_STOP, DANCE_STOP + DANCE_FADE, left)
    }

    /** Disco beats since the breakdance started; negative until its first beat. */
    internal fun danceBeat(now: Long): Double = discoBeat(now) - danceStartBeat

    /** Whether [entity] is our boss mid-breakdance, which Boss Highlight leaves alone. */
    fun isDancing(entity: Entity?): Boolean =
        entity != null && entity === SlayerUtils.boss && danceWeight(System.nanoTime()) > 0.0

    /** The disco floor's beat count, so the dance kicks with the tiles. */
    private fun discoBeat(now: Long): Double = (now - epoch) / 1.0e9 * ManiaDisco.BEATS_PER_SECOND

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
        flushDebugBuffer(toDisk = true)
    }

    /** Hands the buffered lines to the log thread, which also flushes the file with [toDisk]. */
    private fun flushDebugBuffer(toDisk: Boolean) {
        if (!debugOpen || (debugBuffer.isEmpty() && !toDisk)) return
        val chunk = debugBuffer.toString()
        debugBuffer.setLength(0)
        debugIo.execute {
            val writer = debugWriter ?: return@execute
            try {
                writer.write(chunk)
                if (toDisk) writer.flush()
            } catch (e: IOException) {
                debugLogFailed("write failed", e)
            }
        }
    }

    /** Log thread only. */
    private fun openDebugLog() {
        closeDebugWriter()
        if (debugWriterFailed) return
        try {
            val path = FabricLoader.getInstance().configDir.resolve("adrift").resolve("mania_debug.log")
            Files.createDirectories(path.parent)
            trimDebugLog(path)
            debugWriter = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: IOException) {
            debugLogFailed("unavailable", e)
        }
    }

    /** Log thread only. */
    private fun debugLogFailed(what: String, e: IOException) {
        closeDebugWriter()
        if (debugWriterFailed) return
        debugWriterFailed = true
        mc.execute { modMessage("Mania debug log $what: ${e.message}") }
    }

    /**
     * Drops the oldest sessions so that, with the one about to start, at most [MAX_LOGGED_SESSIONS] remain and the
     * file stays under about [MAX_LOG_CHARS]. Streams the file rather than loading it. Log thread only.
     */
    private fun trimDebugLog(path: Path) {
        if (!Files.exists(path)) return
        val startLines = IntArrayList()
        val startChars = LongArrayList()
        var lines = 0
        var chars = 0L
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("START ")) {
                    startLines.add(lines)
                    startChars.add(chars)
                }
                lines++
                chars += line.length + 1
            }
        }
        var first = maxOf(0, startLines.size - (MAX_LOGGED_SESSIONS - 1))
        while (first < startLines.size && chars - startChars.getLong(first) > MAX_LOG_CHARS) first++
        if (first == 0) return
        val cut = if (first < startLines.size) startLines.getInt(first) else lines
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            Files.newBufferedWriter(tmp, StandardCharsets.UTF_8).use { writer ->
                var n = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (n++ < cut) continue
                    writer.write(line)
                    writer.newLine()
                }
            }
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun closeDebugLog() {
        if (!debugOpen) return
        flushDebugBuffer(toDisk = false)
        debugOpen = false
        debugIo.execute(::closeDebugWriter)
    }

    /** Log thread only. */
    private fun closeDebugWriter() {
        val writer = debugWriter ?: return
        debugWriter = null
        try {
            writer.close()
        } catch (ignored: IOException) {
        }
    }

    /** The top of the block at [y]; [edges] holds the surface height along each side of it, see [edgeHeights]. */
    private class Surface(var type: ManiaBlock, val y: Int, val tints: List<AABB>, val edges: DoubleArray)

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
        /** Per column, a bit per scanned block (from the bottom of the scan) that was a full cube when computed. */
        private val fullCubes = IntArray(ManiaGeometry.offsetCount)
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

        /**
         * Brings the column up to date with a block change at ([x], [y], [z]), already applied to [level]. Mania steps
         * swap one full cube for another, which leaves the surfaces as they were apart from the block's type, so
         * those are updated in place; any other change has the column recomputed.
         */
        fun update(s: ManiaSession, level: Level?, x: Int, y: Int, z: Int) {
            if (s !== owner) return
            val i = ManiaGeometry.offsetIndex(x - s.centreX, z - s.centreZ)
            if (i < 0 || !valid[i]) return
            // Another centre resets everything at the next sync anyway.
            if (s.centreVersion != version) return
            val bit = y - (baseY - REACH_DOWN)
            // compute never looks at blocks outside its scan.
            if (bit < 0 || bit >= SCAN_HEIGHT) return
            if (level != null && fullCubes[i] and (1 shl bit) != 0) {
                pos.set(x, y, z)
                val state = level.getBlockState(pos)
                if (!state.isAir && state.getCollisionShape(level, pos) === Shapes.block()) {
                    for (surface in surfaces[i]) if (surface.y == y) surface.type = ManiaBlock.of(state)
                    return
                }
            }
            valid[i] = false
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
            var full = 0
            // Passable blocks directly above the current y; the scan starts HEADROOM above the reach so the top is checked too.
            var clearance = 0
            for (y in baseY + REACH_UP + HEADROOM downTo baseY - REACH_DOWN) {
                pos.set(x, y, z)
                val state = level.getBlockState(pos)
                val shape = if (state.isAir) Shapes.empty() else state.getCollisionShape(level, pos)
                if (shape.isEmpty) {
                    clearance++
                    continue
                }
                val fullCube = shape === Shapes.block()
                if (fullCube) full = full or (1 shl (y - (baseY - REACH_DOWN)))
                if (clearance >= HEADROOM && y <= baseY + REACH_UP) {
                    // A full cube is one unit box, so skip building its box list and edge samples
                    val boxes = if (fullCube) UNIT_BOX else shape.toAabbs()
                    val top = y + if (fullCube) 1.0 else shape.max(Direction.Axis.Y)
                    (found ?: ArrayList<Surface>(2).also { found = it }) +=
                        Surface(ManiaBlock.of(state), y, tintBoxes(boxes, x, y, z), edgeHeights(boxes, y, top))
                }
                clearance = 0
            }
            surfaces[i] = found ?: emptyList()
            fullCubes[i] = full
            valid[i] = true
        }

        private companion object {
            /** Render this far from the boss's feet; around 10 blocks out the player takes damage anyway. */
            const val REACH_UP = 10
            const val REACH_DOWN = 10
            const val HEADROOM = 2
            /** Blocks [compute] scans per column, from REACH_DOWN below the boss to HEADROOM above the reach. */
            const val SCAN_HEIGHT = REACH_DOWN + REACH_UP + HEADROOM + 1
            val UNIT_BOX = listOf(AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
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
