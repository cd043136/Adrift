package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.sendCommand
import dev.cd.adrift.utils.Category
import dev.cd.adrift.utils.SlayerUtils

object AnnounceMania : Module(
    "Announce Mania",
    category = Category.RIFT,
    description = "Announce mania phases"
) {
    private val firstMania by BooleanSetting(
        "Mania #1",
        default = false,
        desc = "First mania"
    )
    private val secondMania by BooleanSetting(
        "Mania #2",
        default = false,
        desc = "Second mania"
    )
    private val thirdMania by BooleanSetting(
        "Mania #3",
        default = false,
        desc = "Third mania (t5)"
    )
    var mania = 0
    var hasMania = false

    init {
        // only trigger on our own boss
        on<TickEvent.Server> {
            val t = SlayerUtils.timer

            if (t == null) {
                if (mania > 0) mania = 0
                return@on
            }

            if (t.plainTextName.contains("MANIA")) {
                if (!hasMania) {
                    hasMania = true
                    mania += 1

                    if (shouldAnnounce(mania)) sendCommand("pc Mania Phase $mania")
                }
            }

            else {
                if (hasMania) hasMania = false
            }
        }
    }

    private fun shouldAnnounce(n: Int): Boolean {
        if (n == 1 && firstMania) return true
        if (n == 2 && secondMania) return true
        if (n == 3 && thirdMania) return true
        return false
    }
}
