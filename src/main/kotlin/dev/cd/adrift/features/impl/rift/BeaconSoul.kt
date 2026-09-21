package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.handlers.schedule
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import dev.cd.adrift.utils.Category
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Items

object BeaconSoul : Module(
    "Beacon Soul Helper",
    category = Category.RIFT,
    description = "2 player beacon soul helper (requires party, keep GUI open until 8/8)"
) {
    private const val BEACON_GUI_NAME = "2 Players Soul ➜ Beacon"

    private val waitTicks by NumberSetting(
        "Delay ticks",
        default = 10,
        min = 5,
        max = 40,
        increment = 1,
        desc = "Ticks between sending messages (1s = 20t)"
    )
    // todo: modes (grid or coords)

    private var sending = false

    init {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (!enabled || LocationUtils.currentArea != Island.Rift) return@register
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