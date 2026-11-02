package dev.cd.adrift.features.boss

import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module
import dev.cd.adrift.utils.SlayerUtils
import net.minecraft.network.chat.Component

object BossHud : Module(
    "HUD",
    Category.BOSS,
    "Shows your boss's timer and nametag on screen.",
) {
    private const val LINE_HEIGHT = 10

    private val example = listOf(
        Component.literal("§c02:41 §5§lMANIA §b14.8s §6§lTWINCLAWS §b1.6s"),
        Component.literal("§4☠ §cBloodfiend V §a2,356§c❤"),
    )

    init {
        hud("Boss HUD", "Shows the boss's timer and nametag.", 0.5f, 0.3f, title = "Boss HUD") { preview ->
            val lines = if (preview) example
            else {
                val boss = SlayerUtils.boss?.takeIf { it.isAlive } ?: return@hud 0 to 0
                val level = mc.level ?: return@hud 0 to 0
                // +1 = nametag, +2 = timer
                listOfNotNull(level.getEntity(boss.id + 2)?.customName, level.getEntity(boss.id + 1)?.customName)
            }
            if (lines.isEmpty()) return@hud 0 to 0

            // Fixed width so the centre doesn't shift as the timer text changes length
            val width = example.maxOf { width(it) }
            lines.forEachIndexed { i, line -> text(line, (width - width(line)) / 2, i * LINE_HEIGHT) }
            width to LINE_HEIGHT * lines.size
        }
    }
}
