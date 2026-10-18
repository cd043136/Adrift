package dev.cd.adrift.features.impl.rift

import dev.cd.adrift.utils.VampireDrop
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.util.ARGB
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
    ;

    companion object {
        fun of(drops: Collection<VampireDrop>): WinTier = when {
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
 */
class SpinnerCelebration(private val tier: WinTier) {
    private enum class Kind { COIN, SPARKLE }

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
    private var sparkleCarry = 0f

    private val ageMs: Long
        get() = System.currentTimeMillis() - startedAt

    /** Drawn before the strip so the rays shine out from behind it. */
    fun renderBackdrop(graphics: GuiGraphicsExtractor, width: Int, height: Int, cell: CellRect) {
        val reach = farthestCorner(cell, width, height) * easeOutCubic(min(ageMs / 350f, 1f))
        drawRays(graphics, cell, reach, 12, ageMs / 1000f * 0.4f) { i ->
            if (i % 2 == 0) ARGB.color(0x50, GOLD) else ARGB.color(0x2C, PALE_GOLD)
        }
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
        spawnParticles(cell, dt)
        updateParticles(dt, width, height)

        drawFlash(graphics, width, height)
        drawCellGlow(graphics, cell)
        drawCellBulbs(graphics, cell)
        drawParticles(graphics)
        drawBanner(graphics, font, width, titleCenterY)
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

    // ---- Overlay ----

    private fun drawFlash(graphics: GuiGraphicsExtractor, width: Int, height: Int) {
        val t = ageMs
        if (t >= FLASH_MS) return
        graphics.fill(0, 0, width, height, ARGB.white(0.55f * (1f - t / FLASH_MS.toFloat())))
    }

    private fun drawCellGlow(graphics: GuiGraphicsExtractor, cell: CellRect) {
        val pulse = 0.45f + 0.55f * (0.5f + 0.5f * sin(ageMs / 130f))
        for (pad in 3..8) {
            val alpha = (0x90 * (1f - (pad - 3) / 6f) * pulse).toInt()
            graphics.outline(
                cell.left - pad, cell.top - pad,
                cell.width + pad * 2, cell.height + pad * 2,
                ARGB.color(alpha, GOLD),
            )
        }
    }

    private fun drawCellBulbs(graphics: GuiGraphicsExtractor, cell: CellRect) {
        val pad = 12
        val step = (ageMs / 80L).toInt()
        val points = perimeter(cell.left - pad, cell.top - pad, cell.right + pad, cell.bottom + pad, 8)
        for (i in points.indices) {
            val lit = Math.floorMod(i - step, 3) == 0
            drawBulb(graphics, points[i].first, points[i].second, 3, if (lit) BULB_ON else BULB_OFF, lit)
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

    private fun spawnParticles(cell: CellRect, dt: Float) {
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

    /** Launches a coin upward within ±70° of vertical. */
    private fun addCoin(x: Float, y: Float, minSpeed: Float, maxSpeed: Float) {
        if (particles.size >= MAX_PARTICLES) return
        val angle = (-PI / 2 + (Random.nextDouble() * 2 - 1) * COIN_CONE).toFloat()
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
                Kind.SPARKLE -> drawSparkle(graphics, p)
            }
        }
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
        const val COIN_GRAVITY = 620f
        const val COIN_CONE = 70 * PI / 180
        const val TWO_PI = (2 * PI).toFloat()

        const val GOLD = 0xFFD700
        const val PALE_GOLD = 0xFFF3B0
        val GOLD_TEXT = 0xFFFFD700.toInt()
        val PALE_GOLD_TEXT = 0xFFFFF6B0.toInt()
        val BIG_WIN_OUTLINE = 0xFF5A3A00.toInt()
        val COIN_FACE = 0xFFFFD43B.toInt()
        val COIN_EDGE = 0xFFB07A10.toInt()
        val COIN_SHINE = 0xFFFFFBE0.toInt()
        val BULB_ON = 0xFFFFF7C0.toInt()
        val BULB_OFF = 0xFF6A5010.toInt()

        val OUTLINE_OFFSETS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

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
