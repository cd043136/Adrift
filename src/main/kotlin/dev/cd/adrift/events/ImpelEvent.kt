package dev.cd.adrift.events

/** What an impel asks for, as named in its subtitle. */
enum class ImpelAction(val label: String) {
    CLICK_UP("CLICK UP"),
    CLICK_DOWN("CLICK DOWN"),
    JUMP("JUMP"),
    SNEAK("SNEAK");

    companion object {
        fun of(label: String): ImpelAction? = entries.firstOrNull { it.label == label }
    }
}

/** Our own impels, posted on the main thread by [dev.cd.adrift.utils.ImpelTracker]. */
object ImpelEvent {
    /**
     * The server accepted our [action], [elapsedMs] after the impel's first subtitle arrived. This is the server's
     * confirmation, so it trails the input by a round trip.
     */
    class Success(val action: ImpelAction, val elapsedMs: Long) : Event()
}
