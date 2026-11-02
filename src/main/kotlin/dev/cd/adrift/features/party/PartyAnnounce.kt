package dev.cd.adrift.features.party

import dev.cd.adrift.events.SlayerEvent
import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module
import dev.cd.adrift.features.tracking.Splits
import dev.cd.adrift.utils.Location
import dev.cd.adrift.utils.sendCommand

/** Everything Adrift sends to party chat, in one place, for lootsharing. Only about your own boss. */
object PartyAnnounce : Module(
    "Party Announce",
    Category.PARTY,
    "Sends your boss's spawn, mania phases and kill time to party chat."
) {
    private val spawn by bool("Spawn", false, "Sends your boss's coordinates when it spawns.")
    private val firstMania by bool("Mania #1", false, "Announces the first mania.")
    private val secondMania by bool("Mania #2", false, "Announces the second mania.")
    private val thirdMania by bool("Mania #3", false, "Announces the third mania (T5).")
    private val killTime by bool("Kill Time", false, "Sends how long the boss took to kill.")

    init {
        on<SlayerEvent.Spawn> {
            if (!spawn || !Location.inRift) return@on
            val pos = boss.blockPosition()
            sendCommand("pc x: ${pos.x}, y: ${pos.y}, z: ${pos.z} ; Boss Spawned")
        }

        on<SlayerEvent.ManiaStart> {
            val announce = when (number) {
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
