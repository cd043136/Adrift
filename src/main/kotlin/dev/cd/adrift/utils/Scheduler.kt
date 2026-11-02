package dev.cd.adrift.utils

import dev.cd.adrift.events.EventBus
import dev.cd.adrift.events.TickEvent
import java.util.concurrent.ConcurrentLinkedQueue

/** Runs code a number of client (or server) ticks from now, on the main thread. */
object Scheduler {
    private class Task(var ticks: Int, val action: () -> Unit)

    private val clientTasks = ConcurrentLinkedQueue<Task>()
    private val serverTasks = ConcurrentLinkedQueue<Task>()

    fun register() {
        EventBus.on<TickEvent.End> { run(clientTasks) }
        // Server ticks arrive on the netty thread; count there, run on the main thread
        EventBus.on<TickEvent.Server> { mc.execute { run(serverTasks) } }
    }

    private fun run(tasks: ConcurrentLinkedQueue<Task>) {
        if (tasks.isEmpty()) return
        val it = tasks.iterator()
        val due = ArrayList<Task>()
        while (it.hasNext()) {
            val task = it.next()
            if (--task.ticks <= 0) {
                it.remove()
                due += task
            }
        }
        for (task in due) runCatching(task.action).onFailure { dev.cd.adrift.Adrift.LOGGER.error("Scheduled task failed", it) }
    }

    fun schedule(ticks: Int, serverTick: Boolean = false, action: () -> Unit) {
        if (ticks <= 0) {
            mc.execute(action)
            return
        }
        (if (serverTick) serverTasks else clientTasks).add(Task(ticks, action))
    }
}

fun schedule(ticks: Int, serverTick: Boolean = false, action: () -> Unit) = Scheduler.schedule(ticks, serverTick, action)
