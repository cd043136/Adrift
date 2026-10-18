package dev.cd.adrift.features.impl.rift

import dev.cd.adrift.utils.VampireDrop
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/** How loudly a landed result is celebrated; also sets how long the result stays on screen. */
enum class WinTier(val holdMs: Long) {
    NONE(3_000L),
    BIG_WIN(4_500L),
    JACKPOT(7_000L),
    ;

    companion object {
        fun of(drops: Collection<VampireDrop>): WinTier = when {
            VampireDrop.SANGRIA in drops -> JACKPOT
            VampireDrop.THE_ONE in drops || VampireDrop.UNFANGED in drops -> BIG_WIN
            else -> NONE
        }
    }
}

/** Screen-space bounds of the landed winner cell. */
data class CellRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Casino-style effects shown once the reel lands on a celebrated result:
 * rotating light rays behind the winner cell, a landing flash, glowing
 * rings and chasing marquee bulbs around the cell, coin and sparkle
 * particles, and a popping banner that replaces the case title.
 *
 * JACKPOT goes further: counter-rotating sangria/gold rays, a pulsing red
 * vignette, a screen-wide chasing marquee, flashes on the Sangria jingle's
 * chord hits, coin fountains, confetti rain and a shaking rainbow banner.
 */
class SpinnerCelebration(private val tier: WinTier) {
    private enum class Kind { COIN, CONFETTI, SPARKLE }

    private class Particle(
        val kind: Kind,
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val size: Int,
        val color: Int,
        val lifeMs: Float,
        var spin: Float,
        val spinSpeed: Float,
    ) {
        var ageMs = 0f
    }

    private val startedAt = System.currentTimeMillis()
    private var lastFrameNanos = System.nanoTime()
    private val particles = ArrayList<Particle>()
    private var burstSpawned = false
    private var coinCarry = 0f
    private var confettiCarry = 0f
    private var sparkleCarry = 0f

    private val jackpot = tier == WinTier.JACKPOT

    private val ageMs: Long
        get() = System.currentTimeMillis() - startedAt

    /** Drawn before the strip so the rays shine out from behind it. */
    fun renderBackdrop(graphics: GuiGraphicsExtractor, width: Int, height: Int, cell: CellRect) {
        val reach = farthestCorner(cell, width, height) * easeOutCubic(min(ageMs / 350f, 1f))
        val seconds = ageMs / 1000f
        if (!jackpot) {
            drawRays(graphics, cell, reach, 12, seconds * 0.4f) { i ->
                if (i % 2 == 0) ARGB.color(0x50, GOLD) else ARGB.color(0x2C, PALE_GOLD)
            }
            return
        }
        drawVignette(graphics, width, height)
        drawRays(graphics, cell, reach, 18, seconds * 0.6f) { i ->
            if (i % 2 == 0) ARGB.color(0x60, SANGRIA) else ARGB.color(0x50, GOLD)
        }
        drawRays(graphics, cell, reach * 0.8f, 9, -seconds * 0.35f) { ARGB.color(0x22, 0xFFFFFF) }
    }

    /** Drawn in a stratum above the strip and its text. */
    fun renderOverlay(
        graphics: GuiGraphicsExtractor,
        font: Font,
        width: Int,
        height: Int,
        cell: CellRect,
        titleCenterY: Float,
    ) {
        val now = System.nanoTime()
        val dt = ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameNanos = now
        spawnParticles(cell, width, height, dt)
        updateParticles(dt, width, height)

        drawFlash(graphics, width, height)
        if (jackpot) drawScreenMarquee(graphics, width, height)
        drawCellGlow(graphics, cell)
        drawCellBulbs(graphics, cell)
        drawParticles(graphics)
        if (jackpot) {
            drawJackpotBanner(graphics, font, width, titleCenterY)
        } else {
            drawBanner(graphics, font, width, titleCenterY)
        }
    }

    // ---- Backdrop ----

    private fun drawRays(
        graphics: GuiGraphicsExtractor,
        cell: CellRect,
        reach: Float,
        count: Int,
        rotation: Float,
        colorOf: (Int) -> Int,
    ) {
        if (reach < 1f) return
        val spread = tan(PI / count * 0.5).toFloat()
        val pose = graphics.pose()
        for (i in 0 until count) {
            val color = colorOf(i)
            val alpha = ARGB.alpha(color)
            pose.pushMatrix()
            pose.translate(cell.centerX, cell.centerY)
            pose.rotate(rotation + i * (2 * PI / count).toFloat())
            // Widening segments approximate a wedge that fades outward.
            for (s in 0 until RAY_SEGMENTS) {
                val r0 = reach * s / RAY_SEGMENTS
                val r1 = reach * (s + 1) / RAY_SEGMENTS
                val halfWidth = (1f + (r0 + r1) / 2f * spread).toInt()
                val a0 = alpha * (RAY_SEGMENTS - s) / RAY_SEGMENTS
                val a1 = alpha * (RAY_SEGMENTS - s - 1) / RAY_SEGMENTS
                graphics.fillGradient(
                    -halfWidth, r0.toInt(), halfWidth, r1.toInt(),
                    ARGB.color(a0, color), ARGB.color(a1, color),
                )
            }
            pose.popMatrix()
        }
    }

    /** Sangria-red glow pulsing in from the top and bottom edges. */
    private fun drawVignette(graphics: GuiGraphicsExtractor, width: Int, height: Int) {
        val pulse = 0.5f + 0.5f * sin(ageMs / 160f)
        val edge = ARGB.color((0x30 + 0x50 * pulse).toInt(), SANGRIA)
        val clear = ARGB.color(0, SANGRIA)
        graphics.fillGradient(0, 0, width, height / 3, edge, clear)
        graphics.fillGradient(0, height - height / 3, width, height, clear, edge)
    }

    // ---- Overlay ----

    private fun drawFlash(graphics: GuiGraphicsExtractor, width: Int, height: Int) {
        val t = ageMs
        val flashMs = if (jackpot) 450f else 300f
        if (t < flashMs) {
            val peak = if (jackpot) 0.85f else 0.55f
            graphics.fill(0, 0, width, height, ARGB.white(peak * (1f - t / flashMs)))
        }
        if (!jackpot) return
        // Red pulses on the jingle's chord hits.
        var accent = 0f
        for (tick in CelebrationSounds.sangriaJingleAccentTicks) {
            val since = t - tick * 50L
            if (since in 0 until ACCENT_FLASH_MS) accent = max(accent, 1f - since / ACCENT_FLASH_MS.toFloat())
        }
        if (accent > 0f) graphics.fill(0, 0, width, height, ARGB.color(0.3f * accent, ACCENT_RED))
    }

    /** Chasing gold / red / off bulbs around the whole screen edge. */
    private fun drawScreenMarquee(graphics: GuiGraphicsExtractor, width: Int, height: Int) {
        val inset = 4
        val step = (ageMs / 70L).toInt()
        val points = perimeter(inset, inset, width - inset, height - inset, 12)
        for (i in points.indices) {
            val (color, lit) = when (Math.floorMod(i - step, 3)) {
                0 -> BULB_ON to true
                1 -> SANGRIA_BULB to true
                else -> BULB_OFF to false
            }
            drawBulb(graphics, points[i].first, points[i].second, 4, color, lit)
        }
    }

    private fun drawCellGlow(graphics: GuiGraphicsExtractor, cell: CellRect) {
        val pulse = 0.45f + 0.55f * (0.5f + 0.5f * sin(ageMs / 130f))
        for (pad in 3..8) {
            val alpha = (0x90 * (1f - (pad - 3) / 6f) * pulse).toInt()
            val rgb = if (jackpot && pad % 2 == 0) SANGRIA else GOLD
            graphics.outline(
                cell.left - pad, cell.top - pad,
                cell.width + pad * 2, cell.height + pad * 2,
                ARGB.color(alpha, rgb),
            )
        }
    }

    private fun drawCellBulbs(graphics: GuiGraphicsExtractor, cell: CellRect) {
        val pad = 12
        val step = (ageMs / 80L).toInt()
        val points = perimeter(cell.left - pad, cell.top - pad, cell.right + pad, cell.bottom + pad, 8)
        for (i in points.indices) {
            val lit = Math.floorMod(i - step, 3) == 0
            val on = if (jackpot && (i / 3) % 2 == 1) SANGRIA_BULB else BULB_ON
            drawBulb(graphics, points[i].first, points[i].second, 3, if (lit) on else BULB_OFF, lit)
        }
    }

    private fun drawBulb(graphics: GuiGraphicsExtractor, x: Int, y: Int, size: Int, color: Int, lit: Boolean) {
        val half = size / 2
        if (lit) graphics.fill(x - size, y - size, x + size + 1, y + size + 1, ARGB.color(0x40, color))
        graphics.fill(x - half, y - half, x - half + size, y - half + size, color)
    }

    private fun drawBanner(graphics: GuiGraphicsExtractor, font: Font, width: Int, centerY: Float) {
        val pop = easeOutBack(min(ageMs / 450f, 1f))
        if (pop <= 0.01f) return
        val text = "BIG WIN!"
        val textWidth = font.width(text)
        val pulse = 1f + 0.06f * sin(ageMs / 110f)
        val scale = min(4f, width * 0.9f / textWidth) * pop * pulse
        val color = if ((ageMs / 120L) % 2L == 0L) GOLD_TEXT else PALE_GOLD_TEXT
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(width / 2f, centerY)
        pose.scale(scale, scale)
        drawOutlinedText(graphics, font, text, -textWidth / 2, -4, color, BIG_WIN_OUTLINE)
        pose.popMatrix()
    }

    /** Per-letter rainbow and wave, with a shake that settles after the landing. */
    private fun drawJackpotBanner(graphics: GuiGraphicsExtractor, font: Font, width: Int, centerY: Float) {
        val pop = easeOutBack(min(ageMs / 550f, 1f))
        if (pop <= 0.01f) return
        val text = "JACKPOT!!!"
        val textWidth = font.width(text)
        val pulse = 1f + 0.05f * sin(ageMs / 90f)
        val scale = min(4.5f, width * 0.92f / textWidth) * pop * pulse
        val shake = 0.6f + 2.4f * (1f - min(ageMs / 1_500f, 1f))
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(width / 2f + signedRandom() * shake, centerY + signedRandom() * shake)
        pose.scale(scale, scale)
        var x = -textWidth / 2
        text.forEachIndexed { i, ch ->
            val letter = ch.toString()
            val hue = (ageMs / 700f + i * 0.09f) % 1f
            pose.pushMatrix()
            pose.translate(0f, sin(ageMs / 120f + i * 0.7f) * 1.5f)
            drawOutlinedText(graphics, font, letter, x, -4, Mth.hsvToArgb(hue, 0.7f, 1f, 255), JACKPOT_OUTLINE)
            pose.popMatrix()
            x += font.width(letter)
        }
        pose.popMatrix()
    }

    private fun drawOutlinedText(
        graphics: GuiGraphicsExtractor,
        font: Font,
        text: String,
        x: Int,
        y: Int,
        color: Int,
        outline: Int,
    ) {
        for ((dx, dy) in OUTLINE_OFFSETS) graphics.text(font, text, x + dx, y + dy, outline, false)
        graphics.text(font, text, x, y, color, false)
    }

    // ---- Particles ----

    private fun spawnParticles(cell: CellRect, width: Int, height: Int, dt: Float) {
        if (jackpot) {
            spawnJackpotParticles(cell, width, height, dt)
            return
        }
        if (!burstSpawned) {
            burstSpawned = true
            repeat(45) { addCoin(cell.centerX, cell.centerY, 180f, 380f) }
            repeat(16) { addSparkleAround(cell) }
        }
        if (ageMs < 2_500L) {
            coinCarry += dt * 14f
            while (coinCarry >= 1f) {
                coinCarry -= 1f
                addCoin(cell.centerX, cell.top.toFloat(), 120f, 260f)
            }
            sparkleCarry += dt * 10f
            while (sparkleCarry >= 1f) {
                sparkleCarry -= 1f
                addSparkleAround(cell)
            }
        }
    }

    /** Big burst from the cell, then coin fountains from both bottom corners, confetti rain and screen sparkles. */
    private fun spawnJackpotParticles(cell: CellRect, width: Int, height: Int, dt: Float) {
        if (!burstSpawned) {
            burstSpawned = true
            repeat(90) { addCoin(cell.centerX, cell.centerY, 200f, 460f) }
            repeat(70) { addConfetti(cell.centerX, cell.centerY, burst = true) }
            repeat(30) { addSparkleAround(cell) }
        }
        if (ageMs >= 5_500L) return
        coinCarry += dt * 40f
        while (coinCarry >= 1f) {
            coinCarry -= 1f
            val fromLeft = Random.nextBoolean()
            val tilt = (0.15 + Random.nextDouble() * 0.45) * if (fromLeft) 1 else -1
            addCoin(if (fromLeft) 0f else width.toFloat(), height.toFloat(), 350f, 560f, -PI / 2 + tilt, 0.0)
        }
        confettiCarry += dt * 60f
        while (confettiCarry >= 1f) {
            confettiCarry -= 1f
            addConfetti(Random.nextFloat() * width, -8f, burst = false)
        }
        sparkleCarry += dt * 24f
        while (sparkleCarry >= 1f) {
            sparkleCarry -= 1f
            if (particles.size < MAX_PARTICLES) addSparkle(Random.nextFloat() * width, Random.nextFloat() * height)
        }
    }

    /** Burst confetti flies up out of the cell; rain confetti drifts down from above the screen. */
    private fun addConfetti(x: Float, y: Float, burst: Boolean) {
        if (particles.size >= MAX_PARTICLES) return
        val vx: Float
        val vy: Float
        if (burst) {
            val angle = (-PI / 2 + (Random.nextDouble() * 2 - 1) * CONFETTI_CONE).toFloat()
            val speed = 150f + Random.nextFloat() * 200f
            vx = cos(angle) * speed
            vy = sin(angle) * speed
        } else {
            vx = signedRandom() * 30f
            vy = 40f + Random.nextFloat() * 50f
        }
        particles += Particle(
            Kind.CONFETTI, x, y, vx, vy,
            size = 3,
            color = CONFETTI_COLORS[Random.nextInt(CONFETTI_COLORS.size)],
            lifeMs = 6_000f,
            spin = Random.nextFloat() * TWO_PI,
            spinSpeed = signedRandom() * 8f,
        )
    }

    /** Launches a coin at [aim] radians (default straight up) within ±[cone]. */
    private fun addCoin(
        x: Float,
        y: Float,
        minSpeed: Float,
        maxSpeed: Float,
        aim: Double = -PI / 2,
        cone: Double = COIN_CONE,
    ) {
        if (particles.size >= MAX_PARTICLES) return
        val angle = (aim + (Random.nextDouble() * 2 - 1) * cone).toFloat()
        val speed = minSpeed + Random.nextFloat() * (maxSpeed - minSpeed)
        particles += Particle(
            Kind.COIN, x, y, cos(angle) * speed, sin(angle) * speed,
            size = Random.nextInt(5, 8),
            color = COIN_FACE,
            lifeMs = 4_000f,
            spin = Random.nextFloat() * TWO_PI,
            spinSpeed = (6f + Random.nextFloat() * 8f) * if (Random.nextBoolean()) 1f else -1f,
        )
    }

    private fun addSparkleAround(cell: CellRect) {
        if (particles.size >= MAX_PARTICLES) return
        val x = cell.centerX + (Random.nextFloat() * 2 - 1) * cell.width * 0.9f
        val y = cell.centerY + (Random.nextFloat() * 2 - 1) * cell.height * 0.9f
        addSparkle(x, y)
    }

    private fun addSparkle(x: Float, y: Float) {
        particles += Particle(
            Kind.SPARKLE, x, y,
            (Random.nextFloat() * 2 - 1) * 20f, (Random.nextFloat() * 2 - 1) * 20f,
            size = 1,
            color = if (Random.nextBoolean()) 0xFFFFFFFF.toInt() else PALE_GOLD_TEXT,
            lifeMs = 400f + Random.nextFloat() * 400f,
            spin = 0f,
            spinSpeed = 0f,
        )
    }

    private fun updateParticles(dt: Float, width: Int, height: Int) {
        for (p in particles) {
            p.ageMs += dt * 1000f
            when (p.kind) {
                Kind.COIN -> p.vy += COIN_GRAVITY * dt
                Kind.CONFETTI -> {
                    p.vy += CONFETTI_GRAVITY * dt
                    p.vx *= 1f - 1.2f * dt
                    p.vy *= 1f - 0.8f * dt
                    p.x += sin(p.ageMs / 180f + p.spin) * 14f * dt
                }
                Kind.SPARKLE -> {
                    p.vx *= 1f - 3f * dt
                    p.vy *= 1f - 3f * dt
                }
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.spin += p.spinSpeed * dt
        }
        particles.removeIf { it.ageMs >= it.lifeMs || it.y > height + 16 || it.x < -32 || it.x > width + 32 }
    }

    private fun drawParticles(graphics: GuiGraphicsExtractor) {
        for (p in particles) {
            when (p.kind) {
                Kind.COIN -> drawCoin(graphics, p)
                Kind.CONFETTI -> drawConfetti(graphics, p)
                Kind.SPARKLE -> drawSparkle(graphics, p)
            }
        }
    }

    /** Small rotating strip whose height flips with a second phase so it flutters. */
    private fun drawConfetti(graphics: GuiGraphicsExtractor, p: Particle) {
        val fade = ((p.lifeMs - p.ageMs) / 400f).coerceIn(0f, 1f)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(p.x, p.y)
        pose.rotate(p.spin)
        pose.scale(1f, abs(cos(p.ageMs / 120f + p.spin)).coerceAtLeast(0.2f))
        graphics.fill(-1, -2, p.size - 1, 3, ARGB.multiplyAlpha(p.color, fade))
        pose.popMatrix()
    }

    /** Octagonal coin squashed horizontally by its spin so it reads as flipping. */
    private fun drawCoin(graphics: GuiGraphicsExtractor, p: Particle) {
        val fade = ((p.lifeMs - p.ageMs) / 300f).coerceIn(0f, 1f)
        val s = p.size
        val h = s / 2
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(p.x, p.y)
        pose.scale(abs(cos(p.spin)).coerceAtLeast(0.12f), 1f)
        val edge = ARGB.multiplyAlpha(COIN_EDGE, fade)
        graphics.fill(-h + 1, -h, s - h - 1, s - h, edge)
        graphics.fill(-h, -h + 1, s - h, s - h - 1, edge)
        graphics.fill(-h + 1, -h + 1, s - h - 1, s - h - 1, ARGB.multiplyAlpha(p.color, fade))
        graphics.fill(-h + 1, -h + 1, -h + 2, -h + 3, ARGB.multiplyAlpha(COIN_SHINE, fade))
        pose.popMatrix()
    }

    private fun drawSparkle(graphics: GuiGraphicsExtractor, p: Particle) {
        val twinkle = sin(PI * (p.ageMs / p.lifeMs)).toFloat().coerceIn(0f, 1f)
        val arm = (1 + 3 * twinkle).toInt()
        val color = ARGB.multiplyAlpha(p.color, twinkle)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(p.x, p.y)
        graphics.fill(-arm, 0, arm + 1, 1, color)
        graphics.fill(0, -arm, 1, arm + 1, color)
        pose.popMatrix()
    }

    private companion object {
        const val MAX_PARTICLES = 600
        const val RAY_SEGMENTS = 5
        const val FLASH_MS = 300L
        const val ACCENT_FLASH_MS = 160L
        const val COIN_GRAVITY = 620f
        const val CONFETTI_GRAVITY = 160f
        const val COIN_CONE = 70 * PI / 180
        const val CONFETTI_CONE = 80 * PI / 180
        const val TWO_PI = (2 * PI).toFloat()

        const val GOLD = 0xFFD700
        const val PALE_GOLD = 0xFFF3B0
        const val SANGRIA = 0xD40808
        const val ACCENT_RED = 0xFF3030
        val GOLD_TEXT = 0xFFFFD700.toInt()
        val PALE_GOLD_TEXT = 0xFFFFF6B0.toInt()
        val BIG_WIN_OUTLINE = 0xFF5A3A00.toInt()
        val COIN_FACE = 0xFFFFD43B.toInt()
        val COIN_EDGE = 0xFFB07A10.toInt()
        val COIN_SHINE = 0xFFFFFBE0.toInt()
        val BULB_ON = 0xFFFFF7C0.toInt()
        val BULB_OFF = 0xFF6A5010.toInt()
        val SANGRIA_BULB = 0xFFFF3A3A.toInt()
        val JACKPOT_OUTLINE = 0xFF3A0005.toInt()
        val CONFETTI_COLORS = intArrayOf(
            0xFFD40808.toInt(), 0xFFFFD700.toInt(), 0xFFFFFFFF.toInt(),
            0xFFFF55FF.toInt(), 0xFF55FFFF.toInt(), 0xFF55FF55.toInt(),
        )

        val OUTLINE_OFFSETS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

        fun signedRandom(): Float = Random.nextFloat() * 2f - 1f

        fun perimeter(left: Int, top: Int, right: Int, bottom: Int, spacing: Int): List<Pair<Int, Int>> = buildList {
            var x = left
            while (x < right) { add(x to top); x += spacing }
            var y = top
            while (y < bottom) { add(right to y); y += spacing }
            x = right
            while (x > left) { add(x to bottom); x -= spacing }
            y = bottom
            while (y > top) { add(left to y); y -= spacing }
        }

        fun farthestCorner(cell: CellRect, width: Int, height: Int): Float =
            hypot(max(cell.centerX, width - cell.centerX), max(cell.centerY, height - cell.centerY))

        fun easeOutCubic(t: Float): Float {
            val u = 1f - t
            return 1f - u * u * u
        }

        fun easeOutBack(t: Float): Float {
            val c1 = 1.70158f
            val c3 = c1 + 1f
            val u = t - 1f
            return 1f + c3 * u * u * u + c1 * u * u
        }
    }
}
