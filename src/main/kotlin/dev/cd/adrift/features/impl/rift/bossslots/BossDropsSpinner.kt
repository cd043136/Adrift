package dev.cd.adrift.features.impl.rift.bossslots

import com.mojang.blaze3d.platform.NativeImage
import dev.cd.adrift.utils.VampireDrop
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.Duration

/**
 * Fullscreen CSGO-style case opening reveal for vampire boss drops, in the
 * spirit of SkyOcean's SlotMachineSpinner but rendering wiki item sprites
 * (see VampireDrop) instead of vanilla items.
 *
 * A single horizontal strip of [stripLength] cells scrolls left under a center
 * needle with quartic ease-out over [baseDuration]. The real drops are
 * pre-placed in a single winner cell near the end of the strip: singles
 * render full-size, pairs sit diagonal, triples share a row, with the
 * rarity bar split per drop. The needle stops at a random point inside
 * the winner cell (near an edge, not just dead center) so the result isn't
 * predictable. Hidden drop chat is re-sent via [onSpinStopped] when the reel
 * stops, and via [onFinish] when the screen is closed before the reel finishes.
 * Top results get a [SpinnerCelebration] when [winEffects] is on.
 */
class BossDropsSpinner(
    winningDrops: List<VampireDrop>,
    private val baseDuration: Duration,
    private val playSounds: Boolean,
    private val winEffects: Boolean,
    private val onSpinStopped: () -> Unit,
    private val onFinish: () -> Unit,
) : Screen(Component.literal("Boss Slots")) {
    private val winners: MutableList<VampireDrop> =
        VampireDrop.resolveWinningDrops(winningDrops).toMutableList()
    private val strip = mutableListOf<MutableList<VampireDrop>>()
    private val texDims = mutableMapOf<VampireDrop, Pair<Int, Int>>()

    private val startTime = System.currentTimeMillis() + 200L
    private var holdAfterMs = WinTier.NONE.holdMs

    private val cellSize = 72
    private val spriteSize = 48
    private val gap = 6
    private val stride = cellSize + gap
    private val stripLength = 80

    private var winStartIdx = 0
    private var needleOffsetPx = 0f
    private var lastNeedleIndex = -1
    private var stopped = false
    private var fanfarePlayed = false
    private var lastSoundAt = 0L
    private var finished = false
    private var spinStoppedNotified = false
    private var sangriaJinglePlayed = false
    private var celebrated = false
    private var celebration: SpinnerCelebration? = null

    private val totalSpinMs: Long
        get() = baseDuration.inWholeMilliseconds

    private val stripH: Int
        get() = cellSize + 16

    override fun init() {
        super.init()
        strip.clear()
        repeat(stripLength) { strip.add(randomFillerCell()) }
        // Winner cell near the end, leaving a short visible tail so the
        // needle arrival reads as a decel rather than an abrupt edge stop.
        winStartIdx = (stripLength - 10 - ThreadLocalRandom.current().nextInt(0, 4))
            .coerceIn(0, stripLength - 5)
        placeWinners()
        rollNeedleOffset()
        lastNeedleIndex = -1
        stopped = false
    }

    private fun placeWinners() {
        // The winner cell aliases the live winners list so late-binding loot
        // updates it in place; rendering draws all of them combined.
        strip[winStartIdx.coerceIn(0, strip.size - 1)] = winners
    }

    /**
     * Late-binds the real drops while the strip is still spinning (drops are
     * collected during the animation). Once stopped the result is locked.
     */
    fun updateWinningDrops(drops: List<VampireDrop>) {
        val updated = VampireDrop.resolveWinningDrops(drops)
        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed >= totalSpinMs || stopped || strip.isEmpty()) return
        winners.clear()
        winners.addAll(updated)
        placeWinners()
        rollNeedleOffset()
    }

    override fun isPauseScreen(): Boolean = false

    override fun tick() {
        super.tick()
        if (finished) return
        if (System.currentTimeMillis() - startTime > totalSpinMs + holdAfterMs) {
            finished = true
            finishAndClose()
        }
    }

    override fun removed() {
        // Closing before the reel lands still reveals the dye.
        playSangriaJingle()
        if (!finished) {
            finished = true
            runFinish()
        }
        super.removed()
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        // Let players skip the hold early, but not the spin itself.
        if (!finished && allStopped()) {
            finished = true
            finishAndClose()
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    private fun finishAndClose() {
        runFinish()
        Minecraft.getInstance().setScreen(null)
    }

    private fun runFinish() {
        try {
            onFinish()
        } catch (_: Exception) {
        }
    }

    private fun notifySpinStopped() {
        if (spinStoppedNotified) return
        spinStoppedNotified = true
        try {
            onSpinStopped()
        } catch (_: Exception) {
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val elapsed = System.currentTimeMillis() - startTime
        val font = Minecraft.getInstance().font
        val cy = height / 2

        graphics.fill(0, 0, width, height, 0xA0000000.toInt())
        celebration?.renderBackdrop(graphics, width, height, winnerCell(cy))

        // The celebration banner takes over the title once it starts.
        if (celebration == null) {
            graphics.pose().pushMatrix()
            graphics.pose().translate((width / 2).toFloat(), height * 0.12f)
            graphics.pose().scale(3f, 3f)
            graphics.centeredText(
                font,
                Component.literal("VAMPIRE CASE"),
                0,
                0,
                0xFFFFFFFF.toInt(),
            )
            graphics.pose().popMatrix()
        }

        renderStrip(graphics, elapsed, cy)

        if (allStopped()) {
            graphics.centeredText(font, header(), width / 2, cy + 110, 0xFFFFFFFF.toInt())
            graphics.centeredText(font, footer(), width / 2, cy + 124, 0xFFFFFFFF.toInt())
            graphics.centeredText(
                font,
                Component.literal("Click to close").withStyle(net.minecraft.ChatFormatting.GRAY),
                width / 2,
                cy + 140,
                0xFFFFFFFF.toInt(),
            )
        } else {
            graphics.centeredText(
                font,
                Component.literal("Opening...").withStyle(net.minecraft.ChatFormatting.GRAY),
                width / 2,
                cy + 116,
                0xFFFFFFFF.toInt(),
            )
        }

        celebration?.let {
            // New stratum so the effects draw over the strip and its text.
            graphics.nextStratum()
            it.renderOverlay(graphics, font, width, height, winnerCell(cy), height * 0.12f + 12f)
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    private fun renderStrip(graphics: GuiGraphicsExtractor, elapsed: Long, cy: Int) {
        if (strip.isEmpty()) return
        val raw = (elapsed.toFloat() / totalSpinMs.toFloat()).coerceIn(0f, 1f)
        val eased = easeOutQuart(raw)

        val target = winNeedlePx() - width / 2f
        val scrollX = target * eased

        // Needle index drives tick sounds: one tick per cell passing center.
        val needleIdx = ((scrollX + width / 2f) / stride).toInt()
        if (elapsed in 0..totalSpinMs && needleIdx > lastNeedleIndex) {
            if (lastNeedleIndex != -1) playTick()
            lastNeedleIndex = needleIdx
        }
        if (elapsed >= totalSpinMs && !stopped) {
            stopped = true
            playStop()
            playFanfare()
            playSangriaJingle()
            celebrate()
            notifySpinStopped()
        }

        val top = cy - stripH / 2
        val bottom = cy + stripH / 2
        val cx = width / 2

        // Panel backdrop + top/bottom rails, CSGO-style.
        graphics.fill(0, top, width, bottom, 0xC0141416.toInt())
        val rail = 0xFFFFD700.toInt()
        graphics.fill(0, top, width, top + 1, 0x40FFFFFF)
        graphics.fill(0, bottom - 1, width, bottom, 0x40FFFFFF)

        val cellTop = top + 6
        val cellBottom = bottom - 6
        for (i in strip.indices) {
            val cellLeft = (i * stride + cellSize / 2f - scrollX - cellSize / 2f).toInt()
            if (cellLeft + cellSize < -stride || cellLeft > width + stride) continue
            if (i == winStartIdx) {
                drawSharedCell(graphics, cellLeft, cellTop, cellBottom, cy, strip[i], highlight = stopped)
                continue
            }
            drawSharedCell(graphics, cellLeft, cellTop, cellBottom, cy, strip[i], highlight = false)
        }

        // Side fade so focus stays center.
        graphics.fill(0, top, 56, bottom, 0xAA000000.toInt())
        graphics.fill(width - 56, top, width, bottom, 0xAA000000.toInt())

        // Center needle.
        graphics.fill(cx - 1, top - 10, cx + 1, bottom + 10, rail)
        graphics.fill(cx - 5, top - 14, cx + 5, top - 10, rail)
        graphics.fill(cx - 5, bottom + 10, cx + 5, bottom + 14, rail)
    }

    /**
     * Picks where the needle stops: a random point inside the single winner
     * cell. A small margin keeps the needle visibly inside the box rather
     * than exactly on its edge.
     */
    private fun rollNeedleOffset() {
        val rng = ThreadLocalRandom.current()
        val margin = 6f
        needleOffsetPx = margin + rng.nextFloat() * (cellSize - 2 * margin)
    }

    private fun winNeedlePx(): Float {
        if (strip.isEmpty() || winners.isEmpty()) return 0f
        return winStartIdx * stride + needleOffsetPx
    }

    /** Bounds of the winner cell once the reel has stopped under the needle. */
    private fun winnerCell(cy: Int): CellRect {
        val left = (winStartIdx * stride - (winNeedlePx() - width / 2f)).toInt()
        val top = cy - stripH / 2 + 6
        return CellRect(left, top, left + cellSize, cy + stripH / 2 - 6)
    }

    /** Starts the tiered casino effects once per spin; the hold stretches to fit them. */
    private fun celebrate() {
        if (celebrated) return
        celebrated = true
        val tier = WinTier.of(winners)
        if (!winEffects || tier == WinTier.NONE) return
        holdAfterMs = tier.holdMs
        celebration = SpinnerCelebration(tier)
        if (playSounds) CelebrationSounds.play(tier)
    }

    /**
     * Draws 1-3 drops sharing one box: singles render full-size, pairs sit
     * diagonal (top-left + bottom-right, like stacked loot), triples share a
     * row. The rarity bar splits into one segment per drop.
     */
    private fun drawSharedCell(
        graphics: GuiGraphicsExtractor,
        cellLeft: Int,
        cellTop: Int,
        cellBottom: Int,
        cy: Int,
        drops: List<VampireDrop>,
        highlight: Boolean,
    ) {
        val first = drops.firstOrNull() ?: return
        val bg = if (highlight) 0xFF3A3524.toInt() else 0xFF232229.toInt()
        graphics.fill(cellLeft, cellTop, cellLeft + cellSize, cellBottom, bg)

        val n = drops.size
        when {
            n <= 1 -> {
                drawSprite(graphics, first, cellLeft + (cellSize - spriteSize) / 2, cy - spriteSize / 2)
            }
            n == 2 -> {
                val size = 30
                val lift = 9
                drawSprite(graphics, drops[0], cellLeft + 5, cy - size / 2 - lift, size)
                drawSprite(graphics, drops[1], cellLeft + cellSize - 5 - size, cy - size / 2 + lift, size)
            }
            else -> {
                val size = ((cellSize - 12 - (n - 1) * 4) / n).coerceAtLeast(12)
                val totalW = n * size + (n - 1) * 4
                var sx = cellLeft + (cellSize - totalW) / 2
                val sy = cy - size / 2
                for (drop in drops) {
                    drawSprite(graphics, drop, sx, sy, size)
                    sx += size + 4
                }
            }
        }

        // Split rarity bar: one segment per drop.
        for (j in drops.indices) {
            val segLeft = cellLeft + (j * cellSize) / n
            val segRight = if (j == n - 1) cellLeft + cellSize else cellLeft + ((j + 1) * cellSize) / n
            graphics.fill(segLeft, cellBottom - 4, segRight, cellBottom, drops[j].rarityColor)
        }

        if (highlight) {
            val gold = 0xFFFFD700.toInt()
            val b = 2
            graphics.fill(cellLeft - b, cellTop - b, cellLeft + cellSize + b, cellTop, gold)
            graphics.fill(cellLeft - b, cellBottom, cellLeft + cellSize + b, cellBottom + b, gold)
            graphics.fill(cellLeft - b, cellTop, cellLeft, cellBottom, gold)
            graphics.fill(cellLeft + cellSize, cellTop, cellLeft + cellSize + b, cellBottom, gold)
        }
    }

    private fun drawSprite(graphics: GuiGraphicsExtractor, drop: VampireDrop, x: Int, y: Int) {
        drawSprite(graphics, drop, x, y, spriteSize)
    }

    private fun drawSprite(graphics: GuiGraphicsExtractor, drop: VampireDrop, x: Int, y: Int, size: Int) {
        val (texW, texH) = texSize(drop)
        // The wiki sprites are 160-300px; blit's width/height select a UV
        // region (not a scaled draw), so draw the full texture and scale the
        // pose down to the target size instead of sampling just the top-left.
        graphics.pose().pushMatrix()
        graphics.pose().translate(x.toFloat(), y.toFloat())
        graphics.pose().scale(size / texW.toFloat(), size / texH.toFloat())
        graphics.blit(
            RenderPipelines.GUI_TEXTURED,
            drop.textureId,
            0,
            0,
            0.0f,
            0.0f,
            texW,
            texH,
            texW,
            texH,
            0xFFFFFFFF.toInt(),
        )
        graphics.pose().popMatrix()
    }

    /**
     * Reads the real PNG dimensions once per drop so blit UVs stay correct
     * whatever resolution the shipped sprites are. Falls back to 16x16.
     */
    private fun texSize(drop: VampireDrop): Pair<Int, Int> = texDims.getOrPut(drop) {
        try {
            Minecraft.getInstance().resourceManager.getResource(drop.textureId).orElseThrow().open().use {
                val image = NativeImage.read(it)
                val dims = image.width to image.height
                image.close()
                dims
            }
        } catch (_: Exception) {
            16 to 16
        }
    }

    private fun playTick() {
        if (!playSounds) return
        val now = System.currentTimeMillis()
        if (now - lastSoundAt < 50L) return
        lastSoundAt = now
        val sound = if (ThreadLocalRandom.current().nextBoolean()) {
            SoundEvents.VAULT_INSERT_ITEM
        } else {
            SoundEvents.VAULT_INSERT_ITEM_FAIL
        }
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(sound, 2.0f, 0.6f),
        )
    }

    private fun playStop() {
        if (!playSounds) return
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.ITEM_PICKUP, 1.0f, 0.8f),
        )
    }

    private fun playFanfare() {
        if (!playSounds || fanfarePlayed) return
        fanfarePlayed = true
        // Seals-only is the common case: keep it quiet.
        if (winners.all { it == VampireDrop.COVEN_SEAL }) return
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 0.8f),
        )
    }

    private fun playSangriaJingle() {
        if (!playSounds || sangriaJinglePlayed || VampireDrop.SANGRIA !in winners) return
        sangriaJinglePlayed = true
        CelebrationSounds.playSangriaJingle()
    }

    private fun header(): Component {
        val best = winners.minBy { it.rank }
        val text = when (best) {
            VampireDrop.SANGRIA -> "CRAZY RARE DROP!"
            VampireDrop.THE_ONE,
            VampireDrop.BURGER,
            VampireDrop.UNFANGED,
            -> "VERY RARE DROP!"
            VampireDrop.COVEN_SEAL -> "NO RARE DROPS"
            else -> "RARE DROP!"
        }
        return Component.literal(text).withStyle(best.color)
    }

    private fun footer(): Component {
        val distinct = winners.distinct()
        val message = Component.empty()
        distinct.forEachIndexed { i, drop ->
            if (i > 0) message.append(Component.literal(" + ").withStyle(net.minecraft.ChatFormatting.GRAY))
            message.append(Component.literal(drop.displayName).withStyle(drop.color))
        }
        return message
    }

    private fun allStopped(): Boolean =
        strip.isNotEmpty() && (System.currentTimeMillis() - startTime) >= totalSpinMs

    companion object {
        /**
         * Maps 1-3 winning drops onto 3 reels. Single drop -> triple match.
         * Two drops (Main + Extra) -> [first, first, second] so both real
         * drops are visible. Three -> as-is by rarity.
         *
         * Kept for compatibility; the case-opening strip uses
         * [VampireDrop.resolveWinningDrops] directly so 1-3 drops land as one
         * adjacent cluster on a single line.
         */
        fun resolveFinals(winning: List<VampireDrop>): List<VampireDrop> {
            val drops = VampireDrop.resolveWinningDrops(winning)
            return when (drops.size) {
                1 -> listOf(drops[0], drops[0], drops[0])
                2 -> listOf(drops[0], drops[0], drops[1])
                else -> drops.take(3)
            }
        }

        fun randomDrop(): VampireDrop {
            // Filler odds mirror real per-kill outcome chances (T5), not the
            // raw loot-table weights (those are per-pool: Main totals 67,
            // seal is a guaranteed Token, sangria a 0.01% side roll).
            val total = VampireDrop.entries.sumOf { it.fillerWeight }
            var r = ThreadLocalRandom.current().nextDouble(total)
            for (drop in VampireDrop.entries) {
                r -= drop.fillerWeight
                if (r <= 0.0) return drop
            }
            return VampireDrop.COVEN_SEAL
        }

        /** Filler cells are usually singles, rarely doubles for strip variety. */
        fun randomFillerCell(): MutableList<VampireDrop> =
            if (ThreadLocalRandom.current().nextDouble() < 0.07) {
                // Doubles are strictly Main + rune: two Mains (or two
                // Quantums) can never drop together on a real kill.
                mutableListOf(randomMain(), VampireDrop.SOULTWIST)
            } else {
                mutableListOf(randomDrop())
            }

        /** Weighted pick among Main-pool drops for legal filler pairs. */
        private fun randomMain(): VampireDrop {
            val mains = VampireDrop.entries.filter { it.pool == VampireDrop.Pool.MAIN }
            val total = mains.sumOf { it.fillerWeight }
            var r = ThreadLocalRandom.current().nextDouble(total)
            for (drop in mains) {
                r -= drop.fillerWeight
                if (r <= 0.0) return drop
            }
            return VampireDrop.QUANTUM
        }

        fun easeOutQuad(t: Float): Float = 1f - (1f - t) * (1f - t)

        fun easeOutQuart(t: Float): Float {
            val u = 1f - t
            return 1f - u * u * u * u
        }

        fun rarityColor(drop: VampireDrop): Int = drop.rarityColor
    }
}
