package dev.cd.adrift.events

import com.odtheking.odin.events.core.Event
import net.minecraft.world.entity.Entity

interface SlayerEvent : Event {

    class Spawn(val boss: Entity) : SlayerEvent
}
