package dev.cd.adrift.features.boss

import kotlin.math.floor
import kotlin.math.hypot

/**
 * Where our boss's Stonewrath will land, worked out from the ground blocks it spawns. Plain logic with no game
 * types, so it can be fed recorded packets. Times are seconds on one clock (the module uses [System.nanoTime]),
 * all of them client receive times.
 *
 * While the boss is up it spawns blocks around where the server thinks we are; once it picks its target they
 * stop following us and gather round the landing spot. So the circle follows the recent blocks, then freezes on
 * them at the lock and keeps refining on the blocks that keep coming.
 */
internal class StonewrathTracker {
    enum class Phase { IDLE, ARMED, FOLLOW, LOCKED }

    /** A ground position (feet y). */
    data class Pos(val x: Double, val y: Double, val z: Double)

    private class Spawn(val t: Double, val x: Double, val y: Double, val z: Double)
    private class Centroid(val t: Double, val x: Double, val z: Double)

    var phase = Phase.IDLE
        private set
    /** When the STONEWRATH token showed up. */
    var t0 = 0.0
        private set

    /** Where the circle should be, once [hasTarget]. */
    var targetX = 0.0
        private set
    var targetZ = 0.0
        private set
    var hasTarget = false
        private set
    /** Top of the ground the blocks spawn on, NaN until known. */
    var groundY = Double.NaN
        private set
    /** True while ARMED and following our lagged position before any block arrived. */
    var rising = false
        private set

    val active get() = phase != Phase.IDLE

    private val spawns = ArrayDeque<Spawn>()
    private val centroids = ArrayDeque<Centroid>()
    private var lastBlockT = Double.NEGATIVE_INFINITY
    private var stillSince = Double.NaN

    private var sumX = 0.0
    private var sumZ = 0.0
    private var sumN = 0
    private var snapped = false
    private var endAt = Double.NaN

    fun start(t: Double) {
        reset()
        phase = Phase.ARMED
        t0 = t
    }

    fun reset() {
        phase = Phase.IDLE
        spawns.clear()
        centroids.clear()
        lastBlockT = Double.NEGATIVE_INFINITY
        stillSince = Double.NaN
        sumX = 0.0
        sumZ = 0.0
        sumN = 0
        snapped = false
        endAt = Double.NaN
        hasTarget = false
        groundY = Double.NaN
        rising = false
    }

    /** Ends at [t], unless something ends it sooner. Lets the circle sit on the impact for a moment. */
    fun finishAt(t: Double) {
        if (endAt.isNaN() || t < endAt) endAt = t
    }

    /** A qualifying ground block (already filtered by type, speed and closeness to our boss). */
    fun block(t: Double, x: Double, y: Double, z: Double) {
        if (phase == Phase.IDLE || t - t0 < MIN_BLOCK_TIME) return
        if (phase == Phase.ARMED) {
            phase = Phase.FOLLOW
            rising = false
        }
        spawns.addLast(Spawn(t, x, y, z))
        lastBlockT = t
        // Blocks spawn at our feet or one below: the highest recent one is the ground's top
        var top = Double.NEGATIVE_INFINITY
        for (s in spawns) if (s.t >= t - LOCK_WINDOW) top = maxOf(top, floor(s.y))
        groundY = top
        if (phase == Phase.LOCKED && !snapped) {
            sumX += x
            sumZ += z
            sumN++
            targetX = sumX / sumN
            targetZ = sumZ / sumN
        }
    }

    /** `block.lever.click` near us: the server has picked its target. */
    fun leverClick(t: Double) {
        if (phase == Phase.FOLLOW && t - t0 > SOUND_LOCK_AFTER) lock(t)
    }

    /** `entity.generic.explode` at ([x], [z]): the impact, when it's on our circle. */
    fun explode(t: Double, x: Double, z: Double) {
        if (phase != Phase.LOCKED || !hasTarget) return
        val e = t - t0
        if (e < 8.5 || e > END_AFTER) return
        if (hypot(x - targetX, z - targetZ) > EXPLODE_RANGE) return
        snapped = true
        targetX = x
        targetZ = z
        finishAt(t + IMPACT_HOLD)
    }

    /**
     * Called every client tick with where the server last saw us ([lagged], null if unknown). Runs the lock
     * fallbacks and the timeout.
     */
    fun update(t: Double, lagged: Pos?) {
        if (phase == Phase.IDLE) return
        val e = t - t0
        if (e > END_AFTER || !endAt.isNaN() && t >= endAt) return reset()
        while (spawns.isNotEmpty() && spawns.first().t < t - KEEP_SPAWNS) spawns.removeFirst()

        when (phase) {
            Phase.ARMED -> {
                rising = lagged != null && e >= RISE_TIME
                if (lagged != null) {
                    targetX = lagged.x
                    targetZ = lagged.z
                    groundY = floor(lagged.y)
                    hasTarget = true
                }
            }
            Phase.FOLLOW -> follow(t, e, lagged)
            else -> {}
        }
    }

    private fun follow(t: Double, e: Double, lagged: Pos?) {
        val window = centroid(t - FOLLOW_WINDOW, t)
        if (t - lastBlockT > BLOCK_GAP || window == null) {
            if (lagged != null) setTarget(lagged.x, lagged.z, groundY.takeUnless { it.isNaN() } ?: floor(lagged.y))
        } else {
            targetX = window.x
            targetZ = window.z
            hasTarget = true
        }

        // Fallback: the blocks have stopped, the boss is about to dive
        if (e > FALLBACK_AFTER && t - lastBlockT > BLOCK_GAP) {
            lock(lastBlockT)
            return
        }

        // Secondary: the blocks have stopped moving although we keep going
        val c = centroid(t - LOCK_WINDOW, t)
        if (c == null) {
            stillSince = Double.NaN
            return
        }
        centroids.addLast(c)
        while (centroids.size > 1 && centroids.first().t < t - STILL_SPAN - 0.1) centroids.removeFirst()
        val covered = centroids.first().t <= t - STILL_SPAN + 0.05
        val still = covered && e > FIRST_BLOCK_TIME + STILL_SPAN &&
            centroids.all { it.t < t - STILL_SPAN || hypot(it.x - c.x, it.z - c.z) < STILL_MOVE }
        val away = lagged != null && hypot(lagged.x - c.x, lagged.z - c.z) > AWAY_DISTANCE
        if (still && away) {
            if (stillSince.isNaN()) stillSince = t
            if (t - stillSince >= STILL_HOLD) lock(t)
        } else {
            stillSince = Double.NaN
        }
    }

    /** Freezes on the blocks of the [LOCK_WINDOW] before [windowEnd]; later blocks refine it. */
    private fun lock(windowEnd: Double) {
        sumX = 0.0
        sumZ = 0.0
        sumN = 0
        for (s in spawns) if (s.t >= windowEnd - LOCK_WINDOW && s.t <= windowEnd) {
            sumX += s.x
            sumZ += s.z
            sumN++
        }
        if (sumN == 0) {
            if (!hasTarget) return
            sumX = targetX
            sumZ = targetZ
            sumN = 1
        }
        targetX = sumX / sumN
        targetZ = sumZ / sumN
        hasTarget = true
        phase = Phase.LOCKED
        centroids.clear()
    }

    private fun centroid(from: Double, to: Double): Centroid? {
        var x = 0.0
        var z = 0.0
        var n = 0
        for (s in spawns) if (s.t >= from && s.t <= to) {
            x += s.x
            z += s.z
            n++
        }
        return if (n == 0) null else Centroid(to, x / n, z / n)
    }

    private fun setTarget(x: Double, z: Double, y: Double) {
        targetX = x
        targetZ = z
        if (groundY.isNaN()) groundY = y
        hasTarget = true
    }

    companion object {
        /** Seconds after the token. */
        const val RISE_TIME = 3.2
        const val FIRST_BLOCK_TIME = 4.4
        /** When the server usually picks its target (the lever click); it can lock earlier. */
        const val LOCK_TIME = 7.05
        const val LANDING_TIME = 9.9
        const val SOUND_LOCK_AFTER = 6.5
        const val FALLBACK_AFTER = 9.0
        const val END_AFTER = 11.0
        /** Blocks this soon after the token can't be ours. */
        const val MIN_BLOCK_TIME = 3.0

        const val FOLLOW_WINDOW = 0.4
        const val LOCK_WINDOW = 0.5
        const val KEEP_SPAWNS = 1.0
        /** No block for this long: fall back to our lagged position, or lock after [FALLBACK_AFTER]. */
        const val BLOCK_GAP = 0.15

        const val STILL_SPAN = 0.5
        const val STILL_MOVE = 0.6
        const val STILL_HOLD = 0.25
        const val AWAY_DISTANCE = 2.0

        const val EXPLODE_RANGE = 3.0
        const val IMPACT_HOLD = 0.15
    }
}
