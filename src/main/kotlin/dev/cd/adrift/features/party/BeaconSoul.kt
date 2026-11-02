package dev.cd.adrift.features.party

import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module
import dev.cd.adrift.utils.Location
import dev.cd.adrift.utils.schedule
import dev.cd.adrift.utils.sendCommand
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Items

object BeaconSoul : Module(
    "Beacon Soul",
    Category.PARTY,
    "2 player beacon soul helper: adds a button that sends the grid to party chat (keep the menu open until 8/8).",
    toggle = "Beacon Soul Helper",
) {
    private const val BEACON_GUI_NAME = "2 Players Soul ➜ Beacon"

    private val waitTicks by int("Delay", 10, 5..40, 1, "Ticks between messages (20 ticks = 1 second).", " ticks")
    // todo: modes (grid or coords)

    private var sending = false

    init {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (!enabled || !Location.inRift) return@register
            if (screen !is AbstractContainerScreen<*>) return@register

            val name = screen.title.string
            if (name != BEACON_GUI_NAME) return@register

            val button = Button.builder(Component.literal("Send Grid")) { btn ->
                if (sending) return@builder
                val sendLines = screen.menu.toBeaconGridRows() ?: return@builder
                val wait = waitTicks

                sending = true
                btn.active = false

                sendLines.forEachIndexed { i, row ->
                    schedule(i * wait) { sendCommand("pc $row") }
                }

                schedule(sendLines.size * wait + 5) {
                    sending = false
                    btn.active = true
                }
            }.bounds(5, 5, 100, 20)
                .tooltip(Tooltip.create(Component.literal(name)))
                .build()

            Screens.getWidgets(screen).add(button)
        }
    }

    private fun AbstractContainerMenu.toBeaconGridRows(): List<String>? {
        if (slots.size < 54) return null

        val out = mutableListOf<String>()
        // ignore first row and first column
        for (row in 1..5) {
            val msgRow = buildString {
                for (col in 1..8) {
                    val its = slots[9 * row + col].item
                    append(if (its.item == Items.DIAMOND_BLOCK) "X" else "-")
                }
                append(" $row")
            }
            out.add(msgRow)
        }
        return out
    }
}