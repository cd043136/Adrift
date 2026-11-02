package dev.cd.adrift.features.tracking

import dev.cd.adrift.config.Config
import dev.cd.adrift.events.LevelEvent
import dev.cd.adrift.events.SlayerEvent
import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module
import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.Colors
import dev.cd.adrift.utils.modMessage
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import java.util.concurrent.CopyOnWriteArrayList

object Splits : Module(
    "Splits",
    Category.TRACKING,
    "Damage and mania phase timers for your boss, with a kill-time personal best."
) {
    const val TICK_SECONDS = 0.05
    private const val ROW_HEIGHT = 9
    private const val PB_KEY = "splits.totalPb"

    private val printSplits by bool("Print Splits", false, "Prints the splits in chat after a kill.")
    private val announcePb by bool("Announce PB", true, "Prints the kill time and your personal best after a kill.")
    private val damageColor by color("Damage Colour", Colors.MINECRAFT_GREEN, "HUD colour for damage phases.", allowAlpha = false)
    private val maniaColor by color("Mania Colour", Colors.MINECRAFT_RED, "HUD colour for mania phases.", allowAlpha = false)

    init {
        button("Reset PB", "Reset", "Clears your kill-time personal best.") {
            Config.records.remove(PB_KEY)
            Config.save()
            modMessage("Vampire PB reset")
        }
    }

    private enum class Phase { DAMAGE, MANIA }

    private class Split(val phase: Phase, val number: Int) {
        @Volatile var ticks = 0
        @Volatile var running = true

        val label get() = if (phase == Phase.DAMAGE) "Damage $number" else "Mania $number"
    }

    // Written on the main thread, read by the HUD while rendering
    private val splits = CopyOnWriteArrayList<Split>()
    private var damageCount = 0

    init {
        hud("Show HUD", "Damage/mania splits for the current boss.", 0f, 0.4f) { preview ->
            val rows = if (preview) listOf(
                Triple("Damage 1", "12.34s", damageColor),
                Triple("Mania 1", "4.56s", maniaColor),
                Triple("Damage 2", "8.90s", damageColor),
            ) else {
                if (splits.isEmpty()) return@hud 0 to 0
                splits.map { Triple(it.label, formatTime(it.ticks * TICK_SECONDS), if (it.phase == Phase.DAMAGE) damageColor else maniaColor) }
            }

            val labelWidth = rows.maxOf { width(it.first) }
            val timeWidth = width("59.99s") + 2
            rows.forEachIndexed { i, (label, time, color) ->
                text(label, 0, i * ROW_HEIGHT, color)
                text(time, labelWidth + 4 + timeWidth - width(time), i * ROW_HEIGHT, color)
            }
            (labelWidth + 4 + timeWidth + 2) to ROW_HEIGHT * rows.size
        }

        on<SlayerEvent.Spawn> {
            reset()
            startDamage()
        }
        on<SlayerEvent.ManiaStart> {
            freezeActive()
            splits += Split(Phase.MANIA, number)
        }
        on<SlayerEvent.ManiaEnd> {
            freezeActive()
            startDamage()
        }
        on<SlayerEvent.Tick> {
            splits.lastOrNull()?.takeIf { it.running }?.let { it.ticks += 1 }
        }
        on<SlayerEvent.Complete> { finishFight(ticks) }
        on<SlayerEvent.Fail> { reset() }
        on<LevelEvent.Unload> { reset() }
    }

    override fun onDisable() {
        reset()
    }

    private fun startDamage() {
        splits += Split(Phase.DAMAGE, ++damageCount)
    }

    private fun freezeActive() {
        splits.lastOrNull()?.running = false
    }

    private fun finishFight(ticks: Int) {
        freezeActive()
        if (splits.isEmpty()) return

        val killSeconds = ticks * TICK_SECONDS
        val oldPb = Config.records[PB_KEY]
        if (oldPb == null || killSeconds < oldPb) {
            Config.records[PB_KEY] = killSeconds.toFloat()
            Config.save()
        }
        if (announcePb) {
            val pbText = if (oldPb == null || killSeconds < oldPb) "§7(§d§lNew PB§r§7) Old PB was §8${oldPb?.let { formatTime(it.toDouble()) } ?: "none"}"
            else "§8(§7${formatTime(oldPb.toDouble())}§8)"
            modMessage("§6Total time §7took §6${formatTime(killSeconds)}§7! $pbText")
        }

        if (printSplits) {
            val message = Component.literal("Boss Splits:")
            for (entry in splits) {
                val style = if (entry.phase == Phase.DAMAGE) ChatFormatting.GREEN else ChatFormatting.RED
                message.append(Component.literal("\n${entry.label}: ${formatTime(entry.ticks * TICK_SECONDS)}").withStyle(style))
            }
            modMessage(message, prefix = "")
        }
    }

    private fun reset() {
        splits.clear()
        damageCount = 0
    }

    fun formatTime(seconds: Double): String {
        val totalCents = (seconds * 100.0 + 0.0001).toInt()
        return "%d.%02ds".format(totalCents / 100, totalCents % 100)
    }
}
