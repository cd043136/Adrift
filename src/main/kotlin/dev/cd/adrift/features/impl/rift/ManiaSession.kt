package dev.cd.adrift.features.impl.rift

import dev.cd.adrift.features.impl.rift.ManiaGeometry.BAND_COUNT
import dev.cd.adrift.features.impl.rift.ManiaGeometry.BORDER
import dev.cd.adrift.features.impl.rift.ManiaGeometry.CAPTURE_RADIUS
import dev.cd.adrift.features.impl.rift.ManiaGeometry.CORE
import dev.cd.adrift.features.impl.rift.ManiaGeometry.FIT_RANGE
import dev.cd.adrift.features.impl.rift.ManiaGeometry.ORBIT_COUNT
import dev.cd.adrift.features.impl.rift.ManiaGeometry.OUTSIDE
import dev.cd.adrift.features.impl.rift.ManiaGeometry.PATTERN_RADIUS_SQ
import dev.cd.adrift.features.impl.rift.ManiaGeometry.UNKNOWN
import dev.cd.adrift.features.impl.rift.ManiaGeometry.bandName
import dev.cd.adrift.features.impl.rift.ManiaGeometry.formulaBand
import dev.cd.adrift.features.impl.rift.ManiaGeometry.isRing
import dev.cd.adrift.features.impl.rift.ManiaGeometry.offsetCount
import dev.cd.adrift.features.impl.rift.ManiaGeometry.offsetFormula
import dev.cd.adrift.features.impl.rift.ManiaGeometry.offsetOrbit
import dev.cd.adrift.features.impl.rift.ManiaGeometry.offsetX
import dev.cd.adrift.features.impl.rift.ManiaGeometry.offsetZ
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
    const val UNKNOWN = -1

    const val PATTERN_RADIUS = 15
    const val PATTERN_RADIUS_SQ = PATTERN_RADIUS * PATTERN_RADIUS
    const val FIT_RANGE = 4
    const val CAPTURE_RADIUS = PATTERN_RADIUS + FIT_RANGE
    const val CAPTURE_RADIUS_SQ = CAPTURE_RADIUS * CAPTURE_RADIUS
    const val ORBIT_COUNT = (PATTERN_RADIUS + 1) * (PATTERN_RADIUS + 1)

    private const val SIDE = 2 * PATTERN_RADIUS + 1
    private val lookup = IntArray(SIDE * SIDE) { -1 }

    val offsetX: IntArray
    val offsetZ: IntArray
    val offsetFormula: IntArray
    val offsetOrbit: IntArray
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
        offsetFormula = IntArray(offsetCount) { formulaBand(offsetX[it], offsetZ[it]) }
        offsetOrbit = IntArray(offsetCount) { orbitIndex(offsetX[it], offsetZ[it]) }
    }

    /** Offset index of `(dx, dz)` relative to the centre, or -1 if outside the pattern radius. */
    fun offsetIndex(dx: Int, dz: Int): Int {
        if (dx < -PATTERN_RADIUS || dx > PATTERN_RADIUS || dz < -PATTERN_RADIUS || dz > PATTERN_RADIUS) return -1
        return lookup[(dx + PATTERN_RADIUS) * SIDE + dz + PATTERN_RADIUS]
    }

    /** Shared index for the 8 symmetric images `(±dx, ±dz)`, `(±dz, ±dx)`. */
    fun orbitIndex(dx: Int, dz: Int): Int {
        val a = abs(dx)
        val b = abs(dz)
        return max(a, b) * (PATTERN_RADIUS + 1) + minOf(a, b)
    }

    /** Hypothesised radii (strict `d < 2, 5, 7.5, 10, 12.5`, border to 14.5), compared as `4·d² < (2r)²`. */
    fun formulaBand(dx: Int, dz: Int): Int {
        val q = 4 * (dx * dx + dz * dz)
        return when {
            q < 16 -> CORE
            q < 100 -> 1
            q < 225 -> 2
            q < 400 -> 3
            q < 625 -> 4
            q <= 841 -> BORDER
            else -> OUTSIDE
        }
    }

    fun isRing(band: Int) = band in 1..4

    fun bandName(band: Int) = when (band) {
        CORE -> "core"
        BORDER -> "border"
        OUTSIDE -> "outside"
        UNKNOWN -> "unknown"
        else -> "ring $band"
    }
}

/**
 * Model of one mania of our own boss. Fed with per-tick block batches; owns the centre fit, per-cycle phase state and
 * the learned column → band map. Main thread only.
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

    /** Resolved band per offset index (see [ManiaGeometry.offsetX]). */
    val columnBand: IntArray = offsetFormula.copyOf()

    private var cycleStartTick = 0L
    private var redstoneTick = 0L
    private var coalTick = 0L
    private var phasePredicted = false
    private var referenceCount = 0
    private var lastRedCount = 0
    private var refitsAfterLock = 0
    private var overrideCount = 0
    private val centreWins = HashMap<Long, Int>()

    // Learned observations per absolute column, on a grid around the initial guess so they survive a centre change.
    // Green/red counts are indexed `cell * 5 + ring of that cycle`.
    private val greenCounts = IntArray(GRID_CELLS * 5)
    private val redCounts = IntArray(GRID_CELLS * 5)
    private val coalCounts = IntArray(GRID_CELLS)
    private val orbitGreen = IntArray(ORBIT_COUNT * 5)
    private val orbitRed = IntArray(ORBIT_COUNT * 5)
    private val orbitCoal = IntArray(ORBIT_COUNT)

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

    /** Debug description of the last terracotta/stage decision, only set while logging. */
    private var lastOutcome = ""
    private var pickedRing = 0
    private var pickedConsistent = 0

    fun bandAt(x: Int, z: Int): Int {
        val i = ManiaGeometry.offsetIndex(x - centreX, z - centreZ)
        return if (i < 0) OUTSIDE else columnBand[i]
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
    fun processBatch(tick: Long, batch: List<ManiaUpdate>, synthetic: Boolean): Boolean {
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
        if (!synthetic) packetsSeen += maniaCells
        if (log.enabled) for (u in batch) log.file("B $tick ${u.x} ${u.y} ${u.z} ${u.type.symbol}")

        if (g + r > 0 && (!locked || refitsAfterLock < MAX_REFITS)) fitCentre(batch)
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
        var differing = 0
        if (locked) for (i in 0 until offsetCount) {
            if (columnBand[i] == offsetFormula[i]) continue
            differing++
            log.file("L ${offsetX[i]} ${offsetZ[i]} learned=${bandName(columnBand[i])} formula=${bandName(offsetFormula[i])}")
        }
        val centre = if (locked) "($centreX, $centreZ)" else "unlocked"
        log.chat("Session ended ($reason): ${cycleIndex + 1} cycles, centre $centre, $differing columns differ from formula")
        log.file("END reason=$reason cycles=${cycleIndex + 1} centre=$centre differing=$differing")
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
                ManiaBlock.NONE -> if (columnBand[i] != OUTSIDE) inNone++
            }
        }
        return inNone
    }

    private fun isRevert(inNone: Int): Boolean {
        if (cycleIndex < 0 || inNone < max(MIN_REVERT_CELLS, referenceCount / 2)) return false
        // Guards against an overlapping boss reverting its own pattern on top of ours mid-mania.
        val remaining = maniaRemaining
        return remaining == null || remaining <= REVERT_WINDOW_SECONDS || cycleIndex >= MAX_CYCLES - 2
    }

    private fun fitCentre(batch: List<ManiaUpdate>) {
        if (locked) refitsAfterLock++
        var bestX = guessX
        var bestZ = guessZ
        var bestRing = 0
        var bestScore = Int.MIN_VALUE
        var bestConsistent = 0
        var bestDist = Int.MAX_VALUE
        var total = 0

        for (ox in -FIT_RANGE..FIT_RANGE) for (oz in -FIT_RANGE..FIT_RANGE) {
            val cx = guessX + ox
            val cz = guessZ + oz
            greenHist.fill(0)
            redHist.fill(0)
            var coalOk = 0
            var cells = 0
            for (u in batch) {
                val type = u.type
                if (type == ManiaBlock.NONE || type == ManiaBlock.REDSTONE) continue
                val dx = u.x - cx
                val dz = u.z - cz
                // Cells beyond this candidate's circle belong to someone else's pattern.
                if (dx * dx + dz * dz > PATTERN_RADIUS_SQ) continue
                cells++
                val band = formulaBand(dx, dz)
                when (type) {
                    ManiaBlock.GREEN -> greenHist[band]++
                    ManiaBlock.RED -> redHist[band]++
                    else -> if (band == CORE || band == BORDER) coalOk++
                }
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
        if (wins > (centreWins[currentKey] ?: 0)) {
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

    private fun lockCentre(x: Int, z: Int, reset: Boolean) {
        centreX = x
        centreZ = z
        locked = true
        centreVersion++
        if (reset) {
            greenCounts.fill(0)
            redCounts.fill(0)
            coalCounts.fill(0)
            phase = Phase.WAITING
            greenRing = 0
            cycleIndex = -1
            phasePredicted = false
            referenceCount = 0
            lastRedCount = 0
        }
        resolveBands()
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

        if (!continuation && !replacing) {
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
        if (!continuation && margin * 2 < total * MIN_MARGIN)
            return outcome(false) { "ignored (ambiguous ring, $pickedConsistent vs ${pickedConsistent - margin})" }

        if (!continuation) {
            if (!replacing) cycleIndex++
            greenRing = ring
            phase = Phase.TERRACOTTA
            phasePredicted = false
            cycleStartTick = tick
            lastRedCount = 0
            if (referenceCount == 0) referenceCount = total
        }
        lastRedCount += reds.size

        for (j in 0 until greens.size) greenCounts[gridOf(greens.getInt(j)) * 5 + ring]++
        for (j in 0 until reds.size) redCounts[gridOf(reds.getInt(j)) * 5 + ring]++
        if (cycleIndex == 0) for (j in 0 until coals.size) coalCounts[gridOf(coals.getInt(j))]++
        resolveBands()

        return outcome(true) {
            val kind = if (continuation) "continuation" else if (replacing) "late" else "cycle ${cycleIndex + 1}"
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

    /** Learned per-column data first, then its symmetric orbit, then the radius formula. */
    private fun resolveBands() {
        orbitGreen.fill(0)
        orbitRed.fill(0)
        orbitCoal.fill(0)
        for (i in 0 until offsetCount) {
            val g = gridOf(i)
            val o = offsetOrbit[i]
            for (k in 1..4) {
                orbitGreen[o * 5 + k] += greenCounts[g * 5 + k]
                orbitRed[o * 5 + k] += redCounts[g * 5 + k]
            }
            orbitCoal[o] += coalCounts[g]
        }

        var overrides = 0
        for (i in 0 until offsetCount) {
            val formula = offsetFormula[i]
            val g = gridOf(i)
            val o = offsetOrbit[i]
            var band = resolve(greenCounts, redCounts, g * 5, coalCounts[g], formula)
            if (band == UNKNOWN) band = resolve(orbitGreen, orbitRed, o * 5, orbitCoal[o], formula)
            if (band == UNKNOWN) band = formula
            columnBand[i] = band
            if (band != formula) overrides++
        }
        if (overrides != overrideCount) {
            overrideCount = overrides
            if (log.enabled) log.chat("Learned data overrides the formula on $overrides columns")
        }
    }

    /**
     * Scores the bands adjacent to the formula band against the observations (green in cycle k supports ring k and
     * contradicts the others; red in cycle k contradicts ring k; first-cycle coal supports core/border). A band other
     * than the formula's needs [OVERRIDE_MARGIN] more support, so a stray foreign update can't flip a column while a
     * genuinely wrong radius still gets corrected after a couple of cycles. [UNKNOWN] when the evidence is too weak.
     */
    private fun resolve(green: IntArray, red: IntArray, base: Int, coal: Int, formula: Int): Int {
        var totalGreen = 0
        var totalRed = 0
        for (k in 1..4) {
            totalGreen += green[base + k]
            totalRed += red[base + k]
        }
        if (totalGreen == 0 && totalRed == 0 && coal == 0) return UNKNOWN

        var best = UNKNOWN
        var bestSupport = Int.MIN_VALUE
        var formulaSupport = Int.MIN_VALUE
        for (band in max(CORE, formula - 1)..minOf(OUTSIDE, formula + 1)) {
            val support = when {
                isRing(band) -> 2 * green[base + band] - totalGreen - red[base + band] - COAL_WEIGHT * coal
                band == OUTSIDE -> -COAL_WEIGHT * coal - totalGreen - totalRed
                else -> COAL_WEIGHT * coal - totalGreen - totalRed
            }
            if (band == formula) formulaSupport = support
            if (support > bestSupport) {
                best = band
                bestSupport = support
            }
        }
        return when {
            best == formula || bestSupport == formulaSupport -> formula
            bestSupport - formulaSupport >= OVERRIDE_MARGIN -> best
            else -> UNKNOWN
        }
    }

    private fun gridOf(offset: Int): Int =
        (centreX - guessX + offsetX[offset] + GRID_RADIUS) * GRID_SIDE + (centreZ - guessZ + offsetZ[offset] + GRID_RADIUS)

    private fun fillHistograms(cells: IntArrayList, hist: IntArray) {
        hist.fill(0)
        for (j in 0 until cells.size) hist[columnBand[cells.getInt(j)]]++
    }

    private fun ringSum(hist: IntArray) = hist[1] + hist[2] + hist[3] + hist[4]

    private fun logBatch(tick: Long, g: Int, r: Int, rs: Int, c: Int, n: Int, inCircle: Int, outcome: String) {
        if (!log.enabled) return
        val line = "t$tick G$g R$r RS$rs C$c N$n in=$inCircle -> $outcome | $phase ring $greenRing cycle ${cycleIndex + 1}"
        log.chat(line)
        log.file("BATCH $line")
    }

    private companion object {
        const val GRID_RADIUS = CAPTURE_RADIUS
        const val GRID_SIDE = 2 * GRID_RADIUS + 1
        const val GRID_CELLS = GRID_SIDE * GRID_SIDE

        const val REDSTONE_OFFSET = 25
        const val REDSTONE_DURATION = 20
        const val COAL_OFFSET = REDSTONE_OFFSET + REDSTONE_DURATION
        const val CYCLE_TICKS = 66
        const val MAX_CYCLES = 8
        const val PREDICTION_GRACE = 5
        const val MISSED_CYCLE_GRACE = 4
        const val FIRST_CYCLE_REMAINING = 25.0
        const val FIRST_CYCLE_WINDOW = 0.35
        const val NEAR_FIT_RANGE = 2
        const val CYCLE_WINDOW_MIN = 58L
        const val CYCLE_WINDOW_MAX = 76L
        const val CONTINUATION_TICKS = 3
        const val MIN_REDSTONE_DELAY = 10
        const val MIN_COAL_DELAY = 8

        const val MIN_CELLS = 8
        const val MIN_FIT_CELLS = 30
        const val MAX_REFITS = 2
        const val MIN_FIT_CONSISTENCY = 0.7
        const val FAR_FIT_CONSISTENCY = 0.9
        const val FAR_FIT_WINS = 2
        const val MIN_CONSISTENCY = 0.8
        const val OVERRIDE_MARGIN = 3
        const val COAL_WEIGHT = 3
        const val MIN_MARGIN = 0.15
        const val MIN_TERRACOTTA_COVERAGE = 0.4
        const val MIN_STAGE_COVERAGE = 0.3
        const val MIN_REVERT_CELLS = 30
        const val REVERT_WINDOW_SECONDS = 3.0

        fun packColumn(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
    }
}
