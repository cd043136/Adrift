package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.events.ChatMessageEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.PersonalBest
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.render.getStringWidth
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.sendCommand
import dev.cd.adrift.events.SlayerEvent
import dev.cd.adrift.utils.Category
import dev.cd.adrift.utils.SlayerUtils
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component

object VampireSplits : Module(
    "Vampire Splits",
    category = Category.RIFT,
    description = "Damage & mania splits for boss"
) {
    private val sendSplits by BooleanSetting(
        "Send Splits",
        default = true,
        desc = "Chat the final summary on kill."
    )
    private val damageColor by ColorSetting(
        "Damage Colour",
        default = Colors.MINECRAFT_GREEN,
        desc = "HUD colour for damage phases."
    )
    private val maniaColor by ColorSetting(
        "Mania Colour",
        default = Colors.MINECRAFT_RED,
        desc = "HUD colour for mania phases."
    )
    private val resetPb by ActionSetting("Reset PB", "Clears the total kill-time best.") {
        totalPb.reset()
        modMessage("Vampire PB reset")
    }

    // TODO: lootshare detection/solo only pb
    private val totalPb = PersonalBest(this, "Vampire Splits PB")

    private enum class Phase { DAMAGE, MANIA }

    private data class Split(
        val phase: Phase,
        val number: Int,
        var ticks: Int = 0,
        var running: Boolean = true
    ) {
        val label: String
            get() = when (phase) {
                Phase.DAMAGE -> "Damage $number"
                Phase.MANIA -> "Mania $number"
            }

        fun color(damage: Color, mania: Color): Color = when (phase) {
            Phase.DAMAGE -> damage
            Phase.MANIA -> mania
        }
    }

    private const val TICK_SECONDS = 0.05
    private const val ROW_HEIGHT = 9
    private const val PENDING_TIMEOUT_TICKS = 200
    private const val QUEST_COMPLETE = "SLAYER QUEST COMPLETE!"
    private const val QUEST_FAILED = "SLAYER QUEST FAILED!"

    private val splits = mutableListOf<Split>()
    private var damageCount = 0
    private var maniaCount = 0
    private var hasMania = false
    private var active = false
    private var pendingOutcome = false
    private var pendingTicks = 0

    private val hud by HUD("Vampire Splits", desc = "Damage/Mania splits for the current vampire boss.") { example ->
        val rows = if (example) {
            listOf(
                Triple("Damage 1", "12.34s", damageColor),
                Triple("Mania 1", "4.56s", maniaColor),
                Triple("Damage 2", "8.90s", damageColor)
            )
        }
        else {
            if (splits.isEmpty()) return@HUD 0 to 0
            splits.map { Triple(it.label, formatTime(it.ticks * TICK_SECONDS), it.color(damageColor, maniaColor)) }
        }

        val labelWidth = rows.maxOf { getStringWidth(it.first) }
        val timeWidth = getStringWidth("59.99s") + 2
        val totalWidth = labelWidth + 4 + timeWidth + 2

        rows.forEachIndexed { i, (label, time, color) ->
            text(label, 0, i * ROW_HEIGHT, color)
            val timeX = labelWidth + 4 + timeWidth - getStringWidth(time)
            text(time, timeX, i * ROW_HEIGHT, color)
        }
        totalWidth to ROW_HEIGHT * rows.size
    }

    init {
        on<SlayerEvent.Spawn> {
            reset()
            active = true
            startDamage()
        }

        on<TickEvent.Server> {
            if (pendingOutcome) {
                pendingTicks += 1
                if (pendingTicks > PENDING_TIMEOUT_TICKS) reset()
                return@on
            }
            if (!active) return@on

            val boss = SlayerUtils.boss
            if (boss == null || !boss.isAlive) {
                freezeActive()
                pendingOutcome = true
                pendingTicks = 0
                return@on
            }

            val inMania = SlayerUtils.timer?.plainTextName?.contains("MANIA") == true
            if (inMania && !hasMania) {
                hasMania = true
                maniaCount += 1
                freezeActive()
                splits += Split(Phase.MANIA, maniaCount)
            }

            else if (!inMania && hasMania) {
                hasMania = false
                freezeActive()
                startDamage()
            }

            if (splits.lastOrNull()?.running == true) splits.last().ticks += 1
        }

        on<ChatMessageEvent> {
            if (!active && !pendingOutcome) return@on

            val lines = value.split("\n").map { it.trim() }
            when {
                lines.any { it == QUEST_COMPLETE } -> {
                    pendingOutcome = false
                    finishFight()
                }
                lines.any { it == QUEST_FAILED } -> reset()
            }
        }

        on<LevelEvent.Load> { reset() }
        on<LevelEvent.Unload> { reset() }
    }

    private fun startDamage() {
        damageCount += 1
        splits += Split(Phase.DAMAGE, damageCount)
    }

    private fun freezeActive() {
        splits.lastOrNull()?.running = false
    }

    private fun finishFight() {
        if (!active) return

        active = false
        freezeActive()
        if (splits.isEmpty()) return

        val killSeconds = splits.sumOf { it.ticks * TICK_SECONDS }
        totalPb.time("total", killSeconds.toFloat(), "s§7!", "§6Total time §7took §6", sendMessage = true)

        printSummary(killSeconds)
    }

    private fun printSummary(killSeconds: Double) {
        val killStr = formatTime(killSeconds)
        val message = Component.empty()
        message.append(Component.literal("Boss Splits:\n"))

        for (entry in splits) {
            val style = if (entry.phase == Phase.DAMAGE) ChatFormatting.GREEN else ChatFormatting.RED
            message.append(
                Component.literal("${entry.label}: ${formatTime(entry.ticks * TICK_SECONDS)}\n")
                    .withStyle(style)
            )
        }
        modMessage(message, prefix = "")

        if (sendSplits) sendCommand("pc Boss took $killStr to kill")
    }

    private fun reset() {
        splits.clear()
        damageCount = 0
        maniaCount = 0
        hasMania = false
        active = false
        pendingOutcome = false
        pendingTicks = 0
    }

    private fun formatTime(seconds: Double): String {
        val totalCents = (seconds * 100.0 + 0.0001).toInt()
        return "%d.%02ds".format(totalCents / 100, totalCents % 100)
    }
}
