package dev.cd.adrift.events

import net.minecraft.world.entity.Entity

/** Our own vampire boss's fight, posted on the main thread by [dev.cd.adrift.utils.SlayerUtils]. */
object SlayerEvent {
    class Spawn(val boss: Entity) : Event()

    /** A server tick of the fight, after any mania change; [ticks] since the spawn. */
    class Tick(val ticks: Int) : Event()

    /** Mania phase [number] (from 1) started. */
    class ManiaStart(val number: Int) : Event()

    /** Mania phase [number] ended and a damage phase started (not posted when the boss dies). */
    class ManiaEnd(val number: Int) : Event()

    /** The boss was killed [ticks] server ticks after spawning. */
    class Complete(val ticks: Int) : Event()

    /** The quest failed, or the fight was abandoned (world change, no outcome after the boss vanished). */
    object Fail : Event()
}
