package dev.cd.adrift.events

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet

object TickEvent {
    /** A server tick, from the server's ping packet. Posted on the netty thread. */
    object Server : Event()

    /** End of a client level tick, on the main thread. */
    object End : Event()
}

object LevelEvent {
    /** A new level was joined (including server switches), on the main thread. */
    object Load : Event()

    /** The current level was left, on the main thread. */
    object Unload : Event()
}

object PacketEvent {
    /** A packet from the server, before it is handled. Posted on the netty thread, or the main thread for bundled packets. */
    class Receive(val packet: Packet<*>) : CancellableEvent()
}

/**
 * A chat line from the server (not the action bar). [message] is the plain text without formatting codes.
 * Cancelling hides it.
 */
class ChatEvent(val message: String, val component: Component) : CancellableEvent()

/** World rendering, for submitting geometry. */
class RenderWorldEvent(val context: LevelRenderContext) : Event()
