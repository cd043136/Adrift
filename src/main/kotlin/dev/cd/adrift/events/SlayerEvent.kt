package dev.cd.adrift.events

import com.odtheking.odin.events.core.Event
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

interface SlayerEvent : Event {

    class Spawn(val boss: Entity) : SlayerEvent

    /** [bossPos] is where the boss died; the reveal is anchored there, not at the player. */
    class Death(val bossPos: Vec3) : SlayerEvent
}
