package dev.cd.adrift.features.impl.rift

import dev.cd.adrift.features.impl.rift.ManiaGeometry.BAND_COUNT
import dev.cd.adrift.features.impl.rift.ManiaGeometry.BORDER
import dev.cd.adrift.features.impl.rift.ManiaGeometry.CORE
import dev.cd.adrift.features.impl.rift.ManiaGeometry.FIT_RANGE
import dev.cd.adrift.features.impl.rift.ManiaGeometry.FOREIGN_RADIUS
import dev.cd.adrift.features.impl.rift.ManiaGeometry.FOREIGN_RADIUS_SQ
import dev.cd.adrift.features.impl.rift.ManiaGeometry.OUTSIDE
import dev.cd.adrift.features.impl.rift.ManiaGeometry.PATTERN_RADIUS_SQ
import dev.cd.adrift.features.impl.rift.ManiaGeometry.bandOf
import dev.cd.adrift.features.impl.rift.ManiaGeometry.isRing
import dev.cd.adrift.features.impl.rift.ManiaGeometry.offsetBand
import dev.cd.adrift.features.impl.rift.ManiaGeometry.offsetCount
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.abs
import kotlin.math.max

// AI made this entire thing
internal enum class ManiaBlock(val symbol: String) {
    NONE("-"), GREEN("G"), RED("R"), REDSTONE("RS"), COAL("C");

    companion object {
        fun of(state: BlockState): ManiaBlock = when (state.block) {
            Blocks.GREEN_TERRACOTTA -> GREEN
            Blocks.RED_TERRACOTTA -> RED
            Blocks.REDSTONE_BLOCK -> REDSTONE
            Blocks.COAL_BLOCK -> COAL
            else -> NONE
        }
    }
}

/** A block change near our boss, tagged with the server tick it arrived in. */
internal class ManiaUpdate(val tick: Long, val x: Int, val y: Int, val z: Int, val type: ManiaBlock)

internal interface ManiaLog {
    val enabled: Boolean
    fun chat(message: String)
    fun file(line: String)
}

/**
 * Static mania geometry. Columns are addressed by their offset from the pattern centre; offsets are precomputed once
 * so the per-session model and the renderer can work on flat arrays indexed by "offset index".
 */
internal object ManiaGeometry {
    const val CORE = 0
    const val BORDER = 5
    const val OUTSIDE = 6
    const val BAND_COUNT = 7

    const val PATTERN_RADIUS = 15
    const val PATTERN_RADIUS_SQ = PATTERN_RADIUS * PATTERN_RADIUS
    const val FIT_RANGE = 4
    /** Our own blocks never reach this far from the centre (border plus a block of fit error), foreign ones do. */
    const val FOREIGN_RADIUS = PATTERN_RADIUS + 1
    const val FOREIGN_RADIUS_SQ = FOREIGN_RADIUS * FOREIGN_RADIUS
    const val FOREIGN_MARGIN = 4
    const val CAPTURE_RADIUS = FOREIGN_RADIUS + FIT_RANGE + FOREIGN_MARGIN
    const val CAPTURE_RADIUS_SQ = CAPTURE_RADIUS * CAPTURE_RADIUS

    private const val SIDE = 2 * PATTERN_RADIUS + 1
    private val lookup = IntArray(SIDE * SIDE) { -1 }

    val offsetX: IntArray
    val offsetZ: IntArray
    /** Band per offset index. */
    val offsetBand: IntArray
    val offsetCount: Int

    init {
        val xs = IntArrayList()
        val zs = IntArrayList()
        for (dx in -PATTERN_RADIUS..PATTERN_RADIUS) for (dz in -PATTERN_RADIUS..PATTERN_RADIUS) {
            if (dx * dx + dz * dz > PATTERN_RADIUS * PATTERN_RADIUS) continue
            lookup[(dx + PATTERN_RADIUS) * SIDE + dz + PATTERN_RADIUS] = xs.size
            xs.add(dx)
            zs.add(dz)
        }
        offsetX = xs.toIntArray()
        offsetZ = zs.toIntArray()
        offsetCount = offsetX.size
        offsetBand = IntArray(offsetCount) { bandOf(offsetX[it], offsetZ[it]) }
    }

    /** Offset index of `(dx, dz)` relative to the centre, or -1 if outside the pattern radius. */
    fun offsetIndex(dx: Int, dz: Int): Int {
        if (dx < -PATTERN_RADIUS || dx > PATTERN_RADIUS || dz < -PATTERN_RADIUS || dz > PATTERN_RADIUS) return -1
        return lookup[(dx + PATTERN_RADIUS) * SIDE + dz + PATTERN_RADIUS]
    }

    /**
     * The mania pattern is a fixed radial template around the centre column. Thresholds are the largest squared
     * distances observed per band in logged manias; no column has a squared distance between one band's limit and
     * the next band's smallest.
     */
    fun bandOf(dx: Int, dz: Int): Int {
        val d2 = dx * dx + dz * dz
        return when {
            d2 <= 2 -> CORE
            d2 <= 20 -> 1
            d2 <= 53 -> 2
            d2 <= 98 -> 3
            d2 <= 157 -> 4
            d2 <= 197 -> BORDER
            else -> OUTSIDE
        }
    }

    fun isRing(band: Int) = band in 1..4
}

/**
 * Model of one mania of our own boss. Fed with per-tick block batches; owns the centre fit and per-cycle phase state.
 * Main thread only.
 */
internal class ManiaSession(
    val startTick: Long,
    val guessX: Int,
    val guessZ: Int,
    private val bossX: Double,
    val bossY: Double,
    private val bossZ: Double,
    private val log: ManiaLog,
) {
    enum class Phase { WAITING, TERRACOTTA, REDSTONE, COAL }

    var centreX = guessX
        private set
    var centreZ = guessZ
        private set
    var locked = false
        private set
    /** Bumped whenever the centre changes, so column-indexed caches know to reset. */
    var centreVersion = 0
        private set
    var phase = Phase.WAITING
        private set
    /** Safe ring of the current cycle, 0 when unknown. */
    var greenRing = 0
        private set
    var cycleIndex = -1
        private set
    var packetsSeen = 0
        private set
    var maniaRemaining: Double? = null
        set(value) {
            if (field == null) startRemaining = value
            field = value
        }
    private var startRemaining: Double? = null

    private var cycleStartTick = 0L
    private var redstoneTick = 0L
    private var coalTick = 0L
    private var phasePredicted = false
    private var referenceCount = 0
    private var lastRedCount = 0
    private var refitsAfterLock = 0
    private val centreWins = HashMap<Long, Int>()

    private val greens = IntArrayList()
    private val reds = IntArrayList()
    private val redstones = IntArrayList()
    private val coals = IntArrayList()
    private val greenHist = IntArray(BAND_COUNT)
    private val redHist = IntArray(BAND_COUNT)
    private val seenStamp = IntArray(offsetCount)
    private val seenType = arrayOfNulls<ManiaBlock>(offsetCount)
    private val conflictStamp = IntArray(offsetCount)
    private val batchOffsets = IntArrayList()
    private var batchStamp = 0
    private val foreignCounts = IntArray(GROUP_NONE + 1)
    private val filtered = ArrayList<ManiaUpdate>()

    /** Debug description of the last terracotta/stage decision, only set while logging. */
    private var lastOutcome = ""
    private var pickedRing = 0
    private var pickedConsistent = 0

    fun bandAt(x: Int, z: Int): Int {
        val i = ManiaGeometry.offsetIndex(x - centreX, z - centreZ)
        return if (i < 0) OUTSIDE else offsetBand[i]
    }

    /** Block the column should currently show, or null if we can't tell (or it isn't part of the pattern). */
    fun expectedBlock(band: Int): ManiaBlock? = when {
        band == CORE || band == BORDER -> ManiaBlock.COAL
        !isRing(band) || greenRing == 0 -> null
        band == greenRing -> ManiaBlock.GREEN
        else -> when (phase) {
            Phase.TERRACOTTA -> ManiaBlock.RED
            Phase.REDSTONE -> ManiaBlock.REDSTONE
            Phase.COAL -> ManiaBlock.COAL
            Phase.WAITING -> null
        }
    }

    fun ticksUntilCoal(now: Long): Long? = when (phase) {
        Phase.TERRACOTTA -> max(0L, cycleStartTick + COAL_OFFSET - now)
        Phase.REDSTONE -> max(0L, redstoneTick + REDSTONE_DURATION - now)
        else -> null
    }

    /** @return false if the batch reverted the pattern and the session should end. */
    fun processBatch(tick: Long, updates: List<ManiaUpdate>, synthetic: Boolean): Boolean {
        if (!synthetic) for (u in updates) if (u.type != ManiaBlock.NONE) packetsSeen++
        if (log.enabled) for (u in updates) log.file("B $tick ${u.x} ${u.y} ${u.z} ${u.type.symbol}")

        var batch = updates
        var fitted = false
        if (locked) {
            countForeign(updates)
            // Locked onto a nearby foreign pattern in the first window, our own batch looks foreign: let it re-lock.
            if (foreignCounts[GROUP_TERRACOTTA] >= FOREIGN_MIN_CELLS && refitsAfterLock < MAX_REFITS &&
                cycleIndex <= 0 && inFirstCycleWindow()
            ) {
                val version = centreVersion
                fitCentre(updates)
                fitted = true
                if (centreVersion != version) countForeign(updates)
            }
            batch = dropForeign(tick, updates)
        }

        var g = 0
        var r = 0
        var rs = 0
        var c = 0
        for (u in batch) when (u.type) {
            ManiaBlock.GREEN -> g++
            ManiaBlock.RED -> r++
            ManiaBlock.REDSTONE -> rs++
            ManiaBlock.COAL -> c++
            ManiaBlock.NONE -> Unit
        }
        val maniaCells = g + r + rs + c
        if (batch.isEmpty()) return true

        if (g + r > 0 && (!locked || refitsAfterLock < MAX_REFITS) && !fitted) fitCentre(batch)
        if (!locked) {
            if (maniaCells > 0) logBatch(tick, g, r, rs, c, batch.size - maniaCells, 0, "ignored (centre not locked)")
            return true
        }

        val inNone = classify(batch)
        val inCircle = greens.size + reds.size + redstones.size + coals.size
        if (maniaCells == 0 && inNone == 0) return true

        if (isRevert(inNone)) {
            logBatch(tick, g, r, rs, c, batch.size - maniaCells, inCircle, "revert ($inNone cells)")
            return false
        }

        val outcomes = if (log.enabled) ArrayList<String>(3) else null
        var terracottaAccepted = false
        if (greens.size + reds.size > 0) {
            terracottaAccepted = handleTerracotta(tick)
            outcomes?.add("terracotta $lastOutcome")
        }
        if (redstones.size > 0) {
            handleStage(Phase.REDSTONE, redstones, tick)
            outcomes?.add("redstone $lastOutcome")
        }
        if (coals.size > 0 && !terracottaAccepted) {
            handleStage(Phase.COAL, coals, tick)
            outcomes?.add("coal $lastOutcome")
        }
        if (outcomes != null) {
            val summary = if (outcomes.isEmpty()) "ignored (nothing in circle)" else outcomes.joinToString("; ")
            logBatch(tick, g, r, rs, c, batch.size - maniaCells, inCircle, summary)
        }
        return true
    }

    /** Timer fallback for missed batches. */
    fun tick(now: Long) {
        when (phase) {
            Phase.TERRACOTTA -> if (now - cycleStartTick >= REDSTONE_OFFSET + PREDICTION_GRACE) {
                phase = Phase.REDSTONE
                redstoneTick = cycleStartTick + REDSTONE_OFFSET
                phasePredicted = true
                if (log.enabled) log.chat("t$now predicted REDSTONE (no batch)")
            }
            Phase.REDSTONE -> if (now - redstoneTick >= REDSTONE_DURATION + PREDICTION_GRACE) {
                phase = Phase.COAL
                coalTick = redstoneTick + REDSTONE_DURATION
                phasePredicted = true
                if (log.enabled) log.chat("t$now predicted COAL (no batch)")
            }
            Phase.COAL -> if (cycleIndex < MAX_CYCLES - 1 && now - cycleStartTick >= CYCLE_TICKS + MISSED_CYCLE_GRACE) {
                cycleIndex++
                cycleStartTick += CYCLE_TICKS
                greenRing = 0
                phase = Phase.TERRACOTTA
                phasePredicted = true
                if (log.enabled) log.chat("t$now missed terracotta batch, cycle ${cycleIndex + 1} ring unknown")
            }
            Phase.WAITING -> Unit
        }
    }

    fun finish(reason: String) {
        if (!log.enabled) return
        val centre = if (locked) "($centreX, $centreZ)" else "unlocked"
        log.chat("Session ended ($reason): ${cycleIndex + 1} cycles, centre $centre")
        log.file("END reason=$reason cycles=${cycleIndex + 1} centre=$centre")
    }

    /**
     * Sorts in-circle cells by type, one entry per column. Columns receiving different types in the same batch are
     * dropped: that only happens when another boss's pattern lands on ours in the same tick.
     */
    private fun classify(batch: List<ManiaUpdate>): Int {
        greens.clear()
        reds.clear()
        redstones.clear()
        coals.clear()
        batchOffsets.clear()
        batchStamp++
        for (u in batch) {
            val i = ManiaGeometry.offsetIndex(u.x - centreX, u.z - centreZ)
            batchOffsets.add(i)
            if (i < 0) continue
            if (seenStamp[i] != batchStamp) {
                seenStamp[i] = batchStamp
                seenType[i] = u.type
            } else if (seenType[i] != u.type) conflictStamp[i] = batchStamp
        }

        var inNone = 0
        for (j in batch.indices) {
            val u = batch[j]
            val i = batchOffsets.getInt(j)
            if (i < 0 || conflictStamp[i] == batchStamp || seenStamp[i] != batchStamp) continue
            // Clear the stamp so later updates of the same column in this batch are skipped.
            seenStamp[i] = 0
            when (u.type) {
                ManiaBlock.GREEN -> greens.add(i)
                ManiaBlock.RED -> reds.add(i)
                ManiaBlock.REDSTONE -> redstones.add(i)
                ManiaBlock.COAL -> coals.add(i)
                ManiaBlock.NONE -> if (offsetBand[i] != OUTSIDE) inNone++
            }
        }
        return inNone
    }

    /**
     * Counts updates beyond [FOREIGN_RADIUS] of the locked centre per step (terracotta, redstone, coal, revert), which
     * can only come from another boss. Steps of different bosses can land in the same tick, so a tripped
     * group is a mix of ours (inside) and foreign (beyond): only the beyond cells are foreign.
     */
    private fun countForeign(batch: List<ManiaUpdate>) {
        foreignCounts.fill(0)
        for (u in batch) {
            val dx = u.x - centreX
            val dz = u.z - centreZ
            if (dx * dx + dz * dz > FOREIGN_RADIUS_SQ) foreignCounts[groupOf(u.type)]++
        }
    }

    /** [batch] keeping our inside-circle cells; only beyond-r16 cells of tripped groups are dropped. */
    private fun dropForeign(tick: Long, batch: List<ManiaUpdate>): List<ManiaUpdate> {
        var any = false
        for (k in 0 until GROUP_NONE) if (foreignCounts[k] >= FOREIGN_MIN_CELLS) any = true
        if (!any) return batch
        filtered.clear()
        var keptInside = 0
        for (u in batch) {
            val group = groupOf(u.type)
            if (group == GROUP_NONE || foreignCounts[group] < FOREIGN_MIN_CELLS) {
                filtered += u
                continue
            }
            val dx = u.x - centreX
            val dz = u.z - centreZ
            if (dx * dx + dz * dz <= FOREIGN_RADIUS_SQ) {
                filtered += u
                keptInside++
            }
        }
        if (log.enabled) {
            val dropped = (0 until GROUP_NONE).filter { foreignCounts[it] >= FOREIGN_MIN_CELLS }
                .joinToString { "${GROUP_NAMES[it]} ${foreignCounts[it]}" }
            log.chat("t$tick ignored foreign $dropped (cells beyond r$FOREIGN_RADIUS), ${batch.size - filtered.size} updates dropped ($keptInside inside kept)")
        }
        return filtered
    }

    private fun groupOf(type: ManiaBlock): Int = when (type) {
        ManiaBlock.GREEN, ManiaBlock.RED -> GROUP_TERRACOTTA
        ManiaBlock.REDSTONE -> GROUP_REDSTONE
        ManiaBlock.COAL -> GROUP_COAL
        ManiaBlock.NONE -> GROUP_NONE
    }

    private fun isRevert(inNone: Int): Boolean {
        if (cycleIndex < 0 || inNone < max(MIN_REVERT_CELLS, referenceCount / 2)) return false
        // Our revert stays inside our circle; one spilling beyond it is another boss's pattern ending.
        if (foreignCounts[GROUP_NONE] >= FOREIGN_MIN_CELLS) return false
        // Guards against an overlapping boss reverting its own pattern on top of ours mid-mania.
        val remaining = maniaRemaining
        return remaining == null || remaining <= REVERT_WINDOW_SECONDS || cycleIndex >= MAX_CYCLES - 2
    }

    private fun fitCentre(batch: List<ManiaUpdate>) {
        if (!locked && beforeFirstCycle()) {
            if (log.enabled) log.chat("Centre fit rejected: pattern arrived before our first cycle")
            return
        }
        // Each step resends the full pattern (G+R+C, G+RS+C, G+C), so greens alone cannot
        // identify terracotta: a redstone/coal resend would otherwise lock us 25t late.
        // RED only appears in the terracotta step, so (re)locks need RED strength.
        var batchReds = 0
        var batchRs = 0
        for (u in batch) when (u.type) {
            ManiaBlock.RED -> batchReds++
            ManiaBlock.REDSTONE -> batchRs++
            else -> Unit
        }
        if (batchReds < MIN_RED_FOR_LOCK) {
            if (log.enabled) log.chat(
                "Centre fit rejected: need RED for lock (reds $batchReds/${MIN_RED_FOR_LOCK}, " +
                    "rs $batchRs looks like a resend)"
            )
            return
        }
        if (locked) refitsAfterLock++
        var bestX = guessX
        var bestZ = guessZ
        var bestRing = 0
        var bestScore = Int.MIN_VALUE
        var bestConsistent = 0
        var bestDist = Int.MAX_VALUE
        var bestForeign = 0
        var total = 0

        for (ox in -FIT_RANGE..FIT_RANGE) for (oz in -FIT_RANGE..FIT_RANGE) {
            val cx = guessX + ox
            val cz = guessZ + oz
            greenHist.fill(0)
            redHist.fill(0)
            var coalOk = 0
            var cells = 0
            var foreign = 0
            for (u in batch) {
                val type = u.type
                if (type == ManiaBlock.NONE || type == ManiaBlock.REDSTONE) continue
                val dx = u.x - cx
                val dz = u.z - cz
                // Cells beyond this candidate's circle belong to someone else's pattern.
                val distSq = dx * dx + dz * dz
                if (distSq > PATTERN_RADIUS_SQ) {
                    if (distSq > FOREIGN_RADIUS_SQ && type != ManiaBlock.COAL) foreign++
                    continue
                }
                val band = bandOf(dx, dz)
                // Coal only supports a fit: another boss's coal step can land in the same tick as our terracotta.
                when (type) {
                    ManiaBlock.GREEN -> greenHist[band]++
                    ManiaBlock.RED -> redHist[band]++
                    else -> if (band == CORE || band == BORDER) coalOk++ else continue
                }
                cells++
            }
            val redRing = ringSum(redHist)
            val dist = ox * ox + oz * oz
            for (k in 1..4) {
                val consistent = greenHist[k] + redRing - redHist[k] + coalOk
                val score = 2 * consistent - cells
                if (score > bestScore || (score == bestScore && dist < bestDist)) {
                    bestScore = score
                    bestDist = dist
                    bestConsistent = consistent
                    bestForeign = foreign
                    total = cells
                    bestX = cx
                    bestZ = cz
                    bestRing = k
                }
            }
        }

        if (total < MIN_FIT_CELLS || bestConsistent < total * MIN_FIT_CONSISTENCY) {
            if (log.enabled) log.chat("Centre fit rejected: best ($bestX, $bestZ) ring $bestRing, $bestConsistent/$total consistent")
            return
        }
        // An overlapping boss landing the same tick spills cells beyond r16, but our own
        // cells still dominate: tolerate foreign proportionally instead of hard-rejecting
        // a strong consistent fit (e.g. 160/160 ours + 81 foreign on the true start).
        val foreignTolerance = max(FOREIGN_MIN_CELLS, (total * FOREIGN_TOLERANCE).toInt())
        if (bestForeign >= foreignTolerance) {
            if (log.enabled) log.chat("Centre fit rejected: best ($bestX, $bestZ) has $bestForeign cells beyond r$FOREIGN_RADIUS (tolerance $foreignTolerance)")
            return
        }

        val fitSummary = "score $bestScore ($bestConsistent/$total), ring $bestRing"
        // Fits within a block of the locked centre count as agreement; sparse terrain can wobble the fit by one.
        val currentKey = packColumn(centreX, centreZ)
        if (locked && abs(bestX - centreX) <= 1 && abs(bestZ - centreZ) <= 1) {
            centreWins.merge(currentKey, 1, Int::plus)
            return
        }
        // A pattern centred well away from our boss is most likely someone else's, unless it arrives exactly when our
        // first cycle is due, or (for sessions that missed the start) keeps fitting cleanly while nothing is locked.
        val key = packColumn(bestX, bestZ)
        val distance = max(abs(bestX - guessX), abs(bestZ - guessZ))
        if (distance > NEAR_FIT_RANGE && !inFirstCycleWindow()) {
            val confirmed = !locked && bestConsistent >= total * FAR_FIT_CONSISTENCY &&
                (centreWins.merge(key, 1, Int::plus) ?: 1) >= FAR_FIT_WINS
            if (!confirmed) {
                if (log.enabled) log.chat("Centre fit rejected: ($bestX, $bestZ) is $distance from the boss, $fitSummary")
                return
            }
        }

        if (!locked) {
            centreWins[key] = max(centreWins[key] ?: 0, 1)
            lockCentre(bestX, bestZ, reset = false)
            if (log.enabled) {
                val offX = bossX - (bestX + 0.5)
                val offZ = bossZ - (bestZ + 0.5)
                val message = "Centre locked ($bestX, $bestZ), guess ($guessX, $guessZ), " +
                    "boss offset (%.2f, %.2f), %s".format(offX, offZ, fitSummary)
                log.chat(message)
                log.file("LOCK $message")
            }
            return
        }

        val wins = centreWins.merge(key, 1, Int::plus) ?: 1
        // Two bosses close together can both start a cycle inside the first window; ours is the one nearer our boss.
        val closer = cycleIndex <= 0 && inFirstCycleWindow() && bossDistSq(bestX, bestZ) < bossDistSq(centreX, centreZ)
        if (closer || wins > (centreWins[currentKey] ?: 0)) {
            if (closer) centreWins[key] = max(wins, (centreWins[currentKey] ?: 0) + 1)
            val previous = "($centreX, $centreZ)"
            lockCentre(bestX, bestZ, reset = true)
            if (log.enabled) {
                log.chat("Centre re-locked $previous -> ($bestX, $bestZ), $fitSummary")
                log.file("RELOCK $previous -> ($bestX, $bestZ) $fitSummary")
            }
        } else if (log.enabled) {
            log.chat("Centre fit disagrees: ($bestX, $bestZ) $fitSummary, keeping ($centreX, $centreZ)")
        }
    }

    private fun inFirstCycleWindow(): Boolean {
        val start = startRemaining ?: return false
        val now = maniaRemaining ?: return false
        return start >= FIRST_CYCLE_REMAINING + FIRST_CYCLE_WINDOW && abs(now - FIRST_CYCLE_REMAINING) <= FIRST_CYCLE_WINDOW
    }

    private fun bossDistSq(x: Int, z: Int): Double {
        val dx = bossX - (x + 0.5)
        val dz = bossZ - (z + 0.5)
        return dx * dx + dz * dz
    }

    /** True while a session that saw the mania start is still well ahead of our first terracotta. */
    private fun beforeFirstCycle(): Boolean {
        val start = startRemaining ?: return false
        val now = maniaRemaining ?: return false
        return start >= FIRST_CYCLE_REMAINING + FIRST_CYCLE_WINDOW && now > FIRST_CYCLE_REMAINING + PRE_START_MARGIN
    }

    private fun lockCentre(x: Int, z: Int, reset: Boolean) {
        centreX = x
        centreZ = z
        locked = true
        centreVersion++
        if (reset) {
            phase = Phase.WAITING
            greenRing = 0
            cycleIndex = -1
            phasePredicted = false
            referenceCount = 0
            lastRedCount = 0
        }
    }

    /** @return true if the batch was accepted as ours. */
    private fun handleTerracotta(tick: Long): Boolean {
        val total = greens.size + reds.size
        val sinceStart = tick - cycleStartTick
        val continuation = cycleIndex >= 0 && greenRing != 0 && phase == Phase.TERRACOTTA && !phasePredicted &&
            sinceStart <= CONTINUATION_TICKS
        // A late batch for a cycle we already predicted as missed.
        val replacing = cycleIndex >= 0 && greenRing == 0 && phasePredicted &&
            sinceStart <= CYCLE_WINDOW_MAX - CYCLE_TICKS
        // Lost our ring (missed cycle) but a strong RED batch arrived off-window (e.g. after a
        // 25t-late start every real step lands outside 58..76): re-anchor instead of staying
        // unknown forever. RED-gated so redstone/coal resends (R~0) can never trigger it.
        val reanchoring = cycleIndex >= 0 && greenRing == 0 && phasePredicted &&
            reds.size >= MIN_RED_FOR_LOCK

        if (!continuation && !replacing && !reanchoring) {
            // RED only appears in the terracotta step (redstone/coal resend G+C/G+RS+C):
            // greens alone are a resend, not a new cycle (t52144/t52404 false starts).
            if (reds.size < MIN_RED_FOR_LOCK)
                return outcome(false) { "ignored (no RED ${reds.size}/${MIN_RED_FOR_LOCK}; resend?)" }
            // Our cycles are ~66 server ticks apart; another boss's batches rarely land in this window.
            if (cycleIndex >= 0 && sinceStart !in CYCLE_WINDOW_MIN..CYCLE_WINDOW_MAX)
                return outcome(false) { "ignored (${sinceStart}t since cycle start)" }
            if (cycleIndex >= MAX_CYCLES - 1) return outcome(false) { "ignored (cycle limit)" }
        }
        if (!continuation) {
            val minCells = max(MIN_CELLS, (referenceCount * MIN_TERRACOTTA_COVERAGE).toInt())
            if (total < minCells) return outcome(false) { "ignored ($total/$minCells cells)" }
        }

        fillHistograms(greens, greenHist)
        fillHistograms(reds, redHist)
        val redRing = ringSum(redHist)
        val margin = pickRing { greenHist[it] + redRing - redHist[it] }

        val ring = if (continuation) greenRing else pickedRing
        val consistent = greenHist[ring] + redRing - redHist[ring]
        if (consistent < total * MIN_CONSISTENCY) return outcome(false) { "ignored (consistency $consistent/$total)" }
        // Red cells agree with almost any ring, so a foreign pattern can look consistent; its green ring can't.
        if (greens.size >= MIN_PURITY_GREENS && greenHist[ring] < greens.size * MIN_GREEN_PURITY)
            return outcome(false) { "ignored (green purity ${greenHist[ring]}/${greens.size} for ring $ring)" }
        if (!continuation && margin * 2 < total * MIN_MARGIN)
            return outcome(false) { "ignored (ambiguous ring, $pickedConsistent vs ${pickedConsistent - margin})" }

        if (!continuation) {
            if (!replacing && !reanchoring) cycleIndex++
            greenRing = ring
            phase = Phase.TERRACOTTA
            phasePredicted = false
            cycleStartTick = tick
            lastRedCount = 0
            if (referenceCount == 0) referenceCount = total
        }
        lastRedCount += reds.size

        return outcome(true) {
            val kind = if (continuation) "continuation" else if (replacing) "late" else if (reanchoring) "re-anchored" else "cycle ${cycleIndex + 1}"
            "ours, $kind, ring $ring ($consistent/$total)"
        }
    }

    /** Redstone/coal steps only advance the phase; they repeat the terracotta information, so nothing is learned. */
    private fun handleStage(target: Phase, cells: IntArrayList, tick: Long): Boolean {
        val stageTick = if (target == Phase.REDSTONE) redstoneTick else coalTick
        if (phase == target && !phasePredicted && greenRing != 0 && tick - stageTick <= CONTINUATION_TICKS)
            return outcome(true) { "ours, continuation" }

        val from = if (target == Phase.REDSTONE) Phase.TERRACOTTA else Phase.REDSTONE
        if (phase != from && !(phase == target && phasePredicted)) return outcome(false) { "ignored (phase $phase)" }
        val since = tick - if (target == Phase.REDSTONE) cycleStartTick else redstoneTick
        val minDelay = if (target == Phase.REDSTONE) MIN_REDSTONE_DELAY else MIN_COAL_DELAY
        if (since < minDelay) return outcome(false) { "ignored (${since}t after previous step)" }
        val minCells = max(MIN_CELLS, (lastRedCount * MIN_STAGE_COVERAGE).toInt())
        if (cells.size < minCells) return outcome(false) { "ignored (${cells.size}/$minCells cells)" }

        fillHistograms(cells, redHist)
        val ringCells = ringSum(redHist)
        val inferred = greenRing == 0
        // The safe ring is the one that didn't turn.
        if (inferred && pickRing { ringCells - redHist[it] } < cells.size * MIN_MARGIN / 2)
            return outcome(false) { "ignored (cannot infer ring)" }
        val ring = if (inferred) pickedRing else greenRing
        val consistent = ringCells - redHist[ring]
        if (consistent < cells.size * MIN_CONSISTENCY)
            return outcome(false) { "ignored (consistency $consistent/${cells.size})" }

        greenRing = ring
        phase = target
        phasePredicted = false
        if (target == Phase.REDSTONE) redstoneTick = tick else coalTick = tick
        return outcome(true) { "ours ($consistent/${cells.size}${if (inferred) ", inferred ring $ring" else ""})" }
    }

    /**
     * Picks the ring with the most consistent cells into [pickedRing] / [pickedConsistent].
     * @return its margin over the runner-up.
     */
    private inline fun pickRing(consistency: (Int) -> Int): Int {
        var best = -1
        var second = -1
        for (k in 1..4) {
            val consistent = consistency(k)
            if (consistent > best) {
                second = best
                best = consistent
                pickedRing = k
            } else if (consistent > second) second = consistent
        }
        pickedConsistent = best
        return best - second
    }

    private inline fun outcome(accepted: Boolean, describe: () -> String): Boolean {
        if (log.enabled) lastOutcome = describe()
        return accepted
    }

    private fun fillHistograms(cells: IntArrayList, hist: IntArray) {
        hist.fill(0)
        for (j in 0 until cells.size) hist[offsetBand[cells.getInt(j)]]++
    }

    private fun ringSum(hist: IntArray) = hist[1] + hist[2] + hist[3] + hist[4]

    private fun logBatch(tick: Long, g: Int, r: Int, rs: Int, c: Int, n: Int, inCircle: Int, outcome: String) {
        if (!log.enabled) return
        val line = "t$tick G$g R$r RS$rs C$c N$n in=$inCircle -> $outcome | $phase ring $greenRing cycle ${cycleIndex + 1}"
        log.chat(line)
        log.file("BATCH $line")
    }

    private companion object {
        const val REDSTONE_OFFSET = 25
        const val REDSTONE_DURATION = 20
        const val COAL_OFFSET = REDSTONE_OFFSET + REDSTONE_DURATION
        const val CYCLE_TICKS = 66
        const val MAX_CYCLES = 8
        const val PREDICTION_GRACE = 5
        const val MISSED_CYCLE_GRACE = 4
        const val FIRST_CYCLE_REMAINING = 25.0
        const val FIRST_CYCLE_WINDOW = 0.35
        const val PRE_START_MARGIN = 0.5
        const val NEAR_FIT_RANGE = 2
        const val CYCLE_WINDOW_MIN = 58L
        const val CYCLE_WINDOW_MAX = 76L
        const val CONTINUATION_TICKS = 3
        const val MIN_REDSTONE_DELAY = 10
        const val MIN_COAL_DELAY = 8

        const val MIN_CELLS = 8
        const val MIN_RED_FOR_LOCK = 12
        const val FOREIGN_TOLERANCE = 0.35
        const val MIN_FIT_CELLS = 30
        const val MAX_REFITS = 2
        const val MIN_FIT_CONSISTENCY = 0.7
        const val FAR_FIT_CONSISTENCY = 0.9
        const val FAR_FIT_WINS = 2
        const val MIN_CONSISTENCY = 0.8
        const val MIN_GREEN_PURITY = 0.75
        const val MIN_PURITY_GREENS = 4
        const val FOREIGN_MIN_CELLS = 6
        const val GROUP_TERRACOTTA = 0
        const val GROUP_REDSTONE = 1
        const val GROUP_COAL = 2
        const val GROUP_NONE = 3
        val GROUP_NAMES = arrayOf("terracotta", "redstone", "coal")
        const val MIN_MARGIN = 0.15
        const val MIN_TERRACOTTA_COVERAGE = 0.4
        const val MIN_STAGE_COVERAGE = 0.3
        const val MIN_REVERT_CELLS = 30
        const val REVERT_WINDOW_SECONDS = 3.0

        fun packColumn(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
    }
}
