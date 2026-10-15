package dev.cd.adrift.features.impl.rift

import dev.cd.adrift.utils.VampireDrop
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.Duration

/**
 * Slot-machine reveal for vampire boss drops, in the spirit of SkyOcean's
 * SlotMachineSpinner but item-only (no custom textures).
 *
 * Three reels spin with staggered stop times ([baseDuration] + 1s per reel).
 * The final position shows the actual drops: a single drop lands as a triple,
 * two drops (Main + Extra, or anything + Sangria) land as [first, first,
 * second], three land as-is. Hidden drop chat is re-sent via [onFinish],
 * which also runs when the screen is closed early or skipped.
 */
class BossDropsSpinner(
    winningDrops: List<VampireDrop>,
    private val baseDuration: Duration,
    private val playSounds: Boolean,
    private val onFinish: () -> Unit,
) : Screen(Component.literal("Boss Slots")) {
    private val finals: List<VampireDrop> = resolveFinals(winningDrops)
    private val reels = mutableListOf<List<VampireDrop>>()
    private val stacks = VampireDrop.entries.associateWith { ItemStack(it.item) }

    private val startTime = System.currentTimeMillis() + 200L
    private val waitPerReelMs = 1000L
    private val holdAfterMs = 3000L
    private val rowHeight = 22
    private val reelGap = 48

    private val lastScrollIndex = IntArray(3) { -1 }
    private val reelStopped = BooleanArray(3)
    private var fanfarePlayed = false
    private var lastSoundAt = 0L
    private var finished = false

    private val totalSpinMs: Long
        get() = baseDuration.inWholeMilliseconds + (2 * waitPerReelMs)

    override fun init() {
        super.init()
        reels.clear()
        for (i in 0..2) {
            val length = 250 + (i * 20)
            val reel = MutableList(length) { randomDrop() }
            reel[length - 2] = finals[i]
            reels.add(reel)
        }
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

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val elapsed = System.currentTimeMillis() - startTime
        val font = Minecraft.getInstance().font
        val cx = width / 2
        val cy = height / 2 + 8

        graphics.fill(0, 0, width, height, 0x80000000.toInt())
        graphics.fill(cx - 84, cy - 84, cx + 84, cy + 92, 0xFF101010.toInt())
        graphics.fill(cx - 84, cy - 84, cx + 84, cy - 83, 0xFFD70000.toInt())
        graphics.fill(cx - 84, cy + 91, cx + 84, cy + 92, 0xFFD70000.toInt())

        graphics.centeredText(font, Component.literal("VAMPIRE SLOTS").withStyle(finals.first().color), cx, cy - 74, 0xFFFFFF)

        for (i in 0..2) {
            renderReel(graphics, i, elapsed, cx, cy)
        }

        if (allStopped()) {
            graphics.centeredText(font, header(), cx, cy + 62, 0xFFFFFF)
            graphics.centeredText(font, footer(), cx, cy + 74, 0xFFFFFF)
        } else {
            graphics.centeredText(
                font,
                Component.literal("Rolling...").withStyle(net.minecraft.ChatFormatting.GRAY),
                cx,
                cy + 68,
                0xFFFFFF,
            )
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    private fun renderReel(graphics: GuiGraphicsExtractor, index: Int, elapsed: Long, cx: Int, cy: Int) {
        if (reels.isEmpty()) return
        val spinMs = baseDuration.inWholeMilliseconds + (index * waitPerReelMs)
        val raw = (elapsed.toFloat() / spinMs.toFloat()).coerceIn(0f, 1f)
        val eased = easeOutQuad(raw)

        val x = cx + ((index - 1) * reelGap)
        val reel = reels[index]
        val totalPx = (reel.size - 2) * rowHeight
        val scrollY = totalPx * eased
        val baseIndex = (scrollY / rowHeight).toInt()

        if (elapsed in 0..spinMs && baseIndex > lastScrollIndex[index]) {
            if (lastScrollIndex[index] != -1) playTick()
            lastScrollIndex[index] = baseIndex
        }
        if (elapsed >= spinMs && !reelStopped[index]) {
            reelStopped[index] = true
            playStop(index)
            if (reelStopped.all { it }) playFanfare()
        }

        val pixelOffset = (scrollY % rowHeight).toInt()
        // Winning-line backdrop behind the center row.
        graphics.fill(x - 13, cy - 11, x + 13, cy + 11, 0x40FFFFFF)

        for (offset in -1..1) {
            val target = (baseIndex + offset).coerceIn(0, reel.size - 1)
            val stack = stacks[reel[target]] ?: continue
            val itemY = cy + pixelOffset - (offset * rowHeight)
            graphics.item(stack, x - 8, itemY - 8)
        }
    }

    private fun playTick() {
        if (!playSounds) return
        val now = System.currentTimeMillis()
        if (now - lastSoundAt < 60L) return
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

    private fun playStop(index: Int) {
        if (!playSounds) return
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.ITEM_PICKUP, 1.0f + (index * 0.2f), 0.8f),
        )
    }

    private fun playFanfare() {
        if (!playSounds || fanfarePlayed) return
        fanfarePlayed = true
        // Seals-only is the common case: keep it quiet.
        if (finals.all { it == VampireDrop.COVEN_SEAL }) return
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 0.8f),
        )
    }

    private fun header(): Component {
        val best = finals.minBy { it.rank }
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
        val distinct = finals.distinct()
        val message = Component.empty()
        distinct.forEachIndexed { i, drop ->
            if (i > 0) message.append(Component.literal(" + ").withStyle(net.minecraft.ChatFormatting.GRAY))
            message.append(Component.literal(drop.displayName).withStyle(drop.color))
        }
        return message
    }

    private fun allStopped(): Boolean =
        reels.isNotEmpty() && (System.currentTimeMillis() - startTime) >= totalSpinMs

    companion object {
        /**
         * Maps 1-3 winning drops onto 3 reels. Single drop -> triple match.
         * Two drops (Main + Extra) -> [first, first, second] so both real
         * drops are visible. Three -> as-is by rarity.
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
            val total = VampireDrop.entries.sumOf { it.weight }
            var r = ThreadLocalRandom.current().nextDouble(total)
            for (drop in VampireDrop.entries) {
                r -= drop.weight
                if (r <= 0.0) return drop
            }
            return VampireDrop.COVEN_SEAL
        }

        fun easeOutQuad(t: Float): Float = 1f - (1f - t) * (1f - t)
    }
}
