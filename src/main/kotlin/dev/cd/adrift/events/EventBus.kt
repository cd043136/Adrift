package dev.cd.adrift.events

import dev.cd.adrift.Adrift
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

abstract class Event {
    /** Posts to every listener of this event's exact class. Returns true if a listener cancelled it. */
    fun post(): Boolean = EventBus.post(this)
}

abstract class CancellableEvent : Event() {
    var cancelled = false
        private set

    fun cancel() {
        cancelled = true
    }
}

/**
 * Listeners keyed by the event's exact class, in registration order. Events can be posted from the netty thread
 * (packets, server ticks), so the listener lists are safe to iterate while others register.
 */
object EventBus {
    private val listeners = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<(Event) -> Unit>>()

    fun <T : Event> register(type: Class<T>, handler: (T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        listeners.computeIfAbsent(type) { CopyOnWriteArrayList() }.add(handler as (Event) -> Unit)
    }

    fun post(event: Event): Boolean {
        val list = listeners[event.javaClass] ?: return false
        for (listener in list) {
            try {
                listener(event)
            } catch (e: Exception) {
                Adrift.LOGGER.error("Error while handling {}", event.javaClass.simpleName, e)
            }
        }
        return event is CancellableEvent && event.cancelled
    }

    inline fun <reified T : Event> on(noinline handler: T.() -> Unit) = register(T::class.java, handler)

    /** Packets of type [P] arriving from the server; the handler gets the packet as receiver and the event to cancel. */
    inline fun <reified P> onReceive(noinline handler: P.(PacketEvent.Receive) -> Unit) =
        register(PacketEvent.Receive::class.java) { event -> (event.packet as? P)?.handler(event) }
}
