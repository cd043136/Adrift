package dev.cd.adrift.utils

import com.odtheking.odin.utils.handlers.TickTask
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvent
import java.util.PriorityQueue

/** A UI sound played [tick] client ticks after the start of a [UiSounds.playSequence] call. */
data class SoundCue(val tick: Int, val sound: SoundEvent, val pitch: Float, val volume: Float = 1f)

/**
 * Non-positional UI sounds, optionally sequenced on the client tick (20/s) like
 * server-sent jingles. Sequences outlive the screen that started them.
 */
object UiSounds {
    private class Pending(val dueTick: Long, val cue: SoundCue)

    private val queue = PriorityQueue<Pending>(compareBy { it.dueTick })
    private var clientTicks = 0L

    init {
        TickTask(1) { advance() }
    }

    fun play(sound: SoundEvent, pitch: Float, volume: Float) {
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(sound, pitch, volume))
    }

    /** Plays tick-0 cues now and the rest on later client ticks. Client thread only. */
    fun playSequence(cues: List<SoundCue>) {
        for (cue in cues) {
            if (cue.tick <= 0) {
                play(cue.sound, cue.pitch, cue.volume)
            } else {
                queue.add(Pending(clientTicks + cue.tick, cue))
            }
        }
    }

    private fun advance() {
        clientTicks++
        while (true) {
            val next = queue.peek() ?: return
            if (next.dueTick > clientTicks) return
            queue.poll()
            play(next.cue.sound, next.cue.pitch, next.cue.volume)
        }
    }
}
