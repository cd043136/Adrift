package dev.cd.adrift.features.party

import dev.cd.adrift.events.SlayerEvent
import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module
import dev.cd.adrift.features.tracking.Splits
import dev.cd.adrift.utils.Location
import dev.cd.adrift.utils.sendCommand

/** Party chat messages about your own boss, for lootsharing. */
object PartyAnnounce : Module(
    "Announce",
    Category.PARTY,
    "Sends your boss's spawn, mania phases and kill time to party chat.",
    toggle = "Announce",
) {
    private val spawn by bool("Announce Boss Spawn", false, "Sends your boss's coordinates when it spawns.")
    private val killTime by bool("Kill Time", false, "Sends how long the boss took to kill.")
    private val mania = bool("Announce Mania", false, "Announces the mania phases you pick.")
    private val firstMania by bool("Mania #1", false, "Announces the first mania.", parent = mania)
    private val secondMania by bool("Mania #2", false, "Announces the second mania.", parent = mania)
    private val thirdMania by bool("Mania #3", false, "Announces the third mania (T5).", parent = mania)

    private val anyOn get() = spawn || killTime || mania()

    override val enabled get() = super.enabled && anyOn

    init {
        upgradedFrom { anyOn }
        on<SlayerEvent.Spawn> {
            if (!spawn || !Location.inRift) return@on
            val pos = boss.blockPosition()
            sendCommand("pc x: ${pos.x}, y: ${pos.y}, z: ${pos.z} ; Boss Spawned")
        }

        on<SlayerEvent.ManiaStart> {
            val announce = mania() && when (number) {
                1 -> firstMania
                2 -> secondMania
                3 -> thirdMania
                else -> false
            }
            if (announce) sendCommand("pc Mania Phase $number")
        }

        on<SlayerEvent.Complete> {
            if (killTime) sendCommand("pc Boss took ${Splits.formatTime(ticks * Splits.TICK_SECONDS)} to kill")
        }
    }
}
