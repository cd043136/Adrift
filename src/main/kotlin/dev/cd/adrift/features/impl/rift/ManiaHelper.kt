package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.getStringWidth
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import dev.cd.adrift.utils.Category
import dev.cd.adrift.utils.RenderUtils.drawFlatSquare
import dev.cd.adrift.utils.RenderUtils.tintBoxes
import dev.cd.adrift.utils.SlayerUtils
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import java.io.BufferedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

object ManiaHelper : Module(
    "Mania Helper",
    category = Category.RIFT,
    description = "Draws your mania pattern on the ground, helpful for uneven terrain or overlapping mania"
) {
    private const val MAX_RENDER_RADIUS = 30
    private const val MAX_PLAYER_DISTANCE_SQ = 20.0 * 20.0
    private const val SCAN_DELAY_TICKS = 40
    private const val TEXT_HEIGHT = 9
    private const val BLOCK_TINT_INDEX = 1
    private const val PLANE_Y_OFFSET = 0.1

    private val style by SelectorSetting(
        name = "Render style",
        default = "Plane",
        options = listOf("Plane", "Block Tint"),
        desc = "Plane = 2d square on the block, Block Tint = changes block colour itself"
    )
    private val squareSize by NumberSetting("Square size", 0.5, 0.1, 1.0, 0.05, desc = "Side length of the plane squares")
        .withDependency { style != BLOCK_TINT_INDEX }
    private val renderRadius by NumberSetting(
        "Render radius",
        MAX_RENDER_RADIUS,
        3,
        MAX_RENDER_RADIUS,
        1,
        desc = "Only render columns this close to you, max renders the whole pattern",
        unit = " blocks"
    )
    private val safeOnly by BooleanSetting("Safe only", default = false, desc = "Only render the safe (green) ring")
    private val onlyDifferent by BooleanSetting(
        "Only where different",
        default = false,
        desc = "Skip columns where the real block already shows the right mania block"
    )
    private val safeColour by ColorSetting("Safe colour", default = Color("55ff5599"), allowAlpha = true, desc = "")
    private val terracottaColour by ColorSetting(
        "Red terracotta colour",
        default = Color("b4503c80"),
        allowAlpha = true,
        desc = ""
    )
    private val redstoneColour by ColorSetting("Redstone colour", default = Color("ff202080"), allowAlpha = true, desc = "")
    private val coalColour by ColorSetting("Coal colour", default = Color("1a1a1a99"), allowAlpha = true, desc = "")
    private val debug by BooleanSetting(
        "Debug",
        default = false,
        desc = "Log batches and centre fitting to chat and config/adrift/mania_debug.log"
    )

    private val hud by HUD("Mania Status", desc = "Safe / move in / move out and time until coal during your mania") { example ->
        val (label, colour) = if (example) "SAFE | coal 1.2s" to Colors.MINECRAFT_GREEN
        else hudStatus() ?: return@HUD 0 to 0
        text(label, 0, 0, colour)
        getStringWidth(label) to TEXT_HEIGHT
    }

    private val maniaRegex = Regex("""MANIA (\d+(?:\.\d+)?)s""")

    // Odin posts packets and server ticks on the netty thread: only these fields are touched there.
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
    private var debugWriter: BufferedWriter? = null
    private var debugWriterFailed = false

    private val log = object : ManiaLog {
        override val enabled: Boolean get() = debug
        override fun chat(message: String) = modMessage("§7[Mania]§r $message")
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
            if (!capturing && LocationUtils.currentArea != Island.Rift) return@on
            // Queued behind the block packets of this tick, so the world is up to date when it runs.
            mc.execute { onServerTick(tick) }
        }

        on<LevelEvent.Load> { reset() }
        on<LevelEvent.Unload> { reset() }

        on<RenderEvent.Extract> {
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

            val size = squareSize
            surfaceCache.sync(s, player.blockY)
            val radius = renderRadius
            val radiusSq = if (radius >= MAX_RENDER_RADIUS) Double.MAX_VALUE else (radius * radius).toDouble()
            val greenOnly = safeOnly
            val skipMatching = onlyDifferent
            val blockTint = style == BLOCK_TINT_INDEX
            val feetY = player.y
            for (block in ManiaBlock.entries) colours[block.ordinal] = colourFor(block)

            for (i in 0 until ManiaGeometry.offsetCount) {
                val expected = s.expectedBlock(s.columnBand[i]) ?: continue
                if (greenOnly && expected != ManiaBlock.GREEN) continue
                val x = s.centreX + ManiaGeometry.offsetX[i]
                val z = s.centreZ + ManiaGeometry.offsetZ[i]
                val dx = x + 0.5 - px
                val dz = z + 0.5 - pz
                if (dx * dx + dz * dz > radiusSq) continue
                surfaceCache.ensure(i, level, x, z, feetY)
                if (skipMatching && surfaceCache.types[i] == expected) continue
                val colour = colours[expected.ordinal]
                if (blockTint) for (box in surfaceCache.tints[i]) drawFilledBox(box, colour, true)
                else drawFlatSquare(x + 0.5, z + 0.5, surfaceCache.tops[i] + PLANE_Y_OFFSET, size, colour, true)
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
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
        drainPending(tick)
        updateLifecycle(tick)
        val s = session ?: return
        s.tick(tick)
        runWorldScan(s, tick)
        if (session === s && debug) logTick(s, tick)
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
            if (!s.processBatch(tick, batch, synthetic)) endSession("blocks reverted")
        }
        batch.clear()
    }

    private fun updateLifecycle(tick: Long) {
        val level = mc.level
        val boss = SlayerUtils.boss?.takeIf { it.isAlive }
        val timerText = boss?.let { (SlayerUtils.timer ?: level?.getEntity(it.id + 2))?.plainTextName }
        val inMania = level != null && LocationUtils.currentArea == Island.Rift && timerText?.contains("MANIA") == true

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
        if (debug) {
            val message = "Mania started, guess (${s.guessX}, ${s.guessZ}), boss y %.2f".format(s.bossY)
            log.chat(message)
            log.file("START tick=${s.startTick} $message")
        }
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
            if (debug) log.chat("No block packets after ${SCAN_DELAY_TICKS}t, scanning the world instead")
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
            Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                .also { debugWriter = it }
        } catch (e: IOException) {
            debugWriterFailed = true
            modMessage("Mania debug log unavailable: ${e.message}")
            null
        }
    }

    private fun closeDebugLog() {
        val writer = debugWriter ?: return
        debugWriter = null
        try {
            writer.close()
        } catch (ignored: IOException) {
        }
    }

    /** Per-column surface at the player's height, keyed by offset index of the current centre. */
    private class SurfaceCache {
        private val valid = BooleanArray(ManiaGeometry.offsetCount)
        val types = Array(ManiaGeometry.offsetCount) { ManiaBlock.NONE }
        val tops = DoubleArray(ManiaGeometry.offsetCount)
        val tints = Array(ManiaGeometry.offsetCount) { emptyList<AABB>() }
        private val pos = BlockPos.MutableBlockPos()
        private var owner: ManiaSession? = null
        private var version = -1
        private var playerBlockY = Int.MIN_VALUE

        fun sync(s: ManiaSession, blockY: Int) {
            if (s === owner && s.centreVersion == version && blockY == playerBlockY) return
            owner = s
            version = s.centreVersion
            playerBlockY = blockY
            valid.fill(false)
        }

        fun invalidate(s: ManiaSession, x: Int, z: Int) {
            if (s !== owner) return
            val i = ManiaGeometry.offsetIndex(x - s.centreX, z - s.centreZ)
            if (i >= 0) valid[i] = false
        }

        fun ensure(i: Int, level: Level, x: Int, z: Int, feetY: Double) {
            if (!valid[i]) compute(i, level, x, z, feetY)
        }

        fun clear() {
            owner = null
            valid.fill(false)
        }

        private fun compute(i: Int, level: Level, x: Int, z: Int, feetY: Double) {
            var top = feetY
            var type = ManiaBlock.NONE
            var tint = emptyList<AABB>()
            for (y in playerBlockY + SCAN_UP downTo playerBlockY - SCAN_DOWN) {
                pos.set(x, y, z)
                val state = level.getBlockState(pos)
                val shape = state.getCollisionShape(level, pos)
                if (shape.isEmpty) continue
                top = y + shape.max(Direction.Axis.Y)
                type = ManiaBlock.of(state)
                tint = tintBoxes(shape, x, y, z)
                break
            }
            tops[i] = top
            types[i] = type
            tints[i] = tint
            valid[i] = true
        }

        private companion object {
            const val SCAN_UP = 2
            const val SCAN_DOWN = 4
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

        /** Once [s] has a locked centre, columns outside its pattern are skipped. */
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
                    if (dx * dx + dz * dz > ManiaGeometry.PATTERN_RADIUS_SQ) continue
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
