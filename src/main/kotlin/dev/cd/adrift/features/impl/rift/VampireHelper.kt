package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import dev.cd.adrift.events.SlayerEvent
import dev.cd.adrift.utils.Category

object VampireHelper : Module(
    "Vampire Helper",
    category = Category.RIFT,
    description = "T5 vampire slayer qol"
) {
    private val announceSpawn by BooleanSetting(
        "Announce Spawn",
        default = false,
        desc = "Send coords when your boss spawns"
    )

    init {
        on<SlayerEvent.Spawn> {
            if (LocationUtils.currentArea != Island.Rift || !announceSpawn) return@on

            val pos = boss.blockPosition()
            sendCommand("pc x: ${pos.x}, y: ${pos.y}, z: ${pos.z} ; Boss Spawned")
        }
    }
}
