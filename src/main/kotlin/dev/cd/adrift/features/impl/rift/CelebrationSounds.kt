package dev.cd.adrift.features.impl.rift

import dev.cd.adrift.utils.SoundCue
import dev.cd.adrift.utils.UiSounds
import net.minecraft.core.Holder
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents

/** Sound cues for Boss Slots reveals, timed in client ticks. */
object CelebrationSounds {
    private const val JINGLE_VOLUME = 0.82f

    /** Note-block pitches (F#3..F#4 and F#4..F#5) used by the Sangria jingle. */
    private val LOW = floatArrayOf(
        0.4920635f, 0.52380955f, 0.5555556f, 0.5873016f, 0.61904764f, 0.6666667f, 0.6984127f,
        0.74603176f, 0.7936508f, 0.82539684f, 0.8888889f, 0.93650794f, 1.0f,
    )
    private val HIGH = floatArrayOf(
        1.0f, 1.0476191f, 1.1111112f, 1.1746032f, 1.2539682f, 1.3333334f, 1.4126984f,
        1.4920635f, 1.5873016f, 1.6666666f, 1.7777778f, 1.8730159f, 2.0f,
    )
    private val FINAL_CHORD = floatArrayOf(0.4920635f, 0.61904764f, 0.74603176f, 1.0f, 1.2539682f, 1.4920635f, 2.0f)

    /**
     * Hypixel's Sangria Dye jingle as captured by Log Sounds (157 plings over
     * 82 ticks). Boss Slots mutes the original while spinning, so it is
     * replayed when the dye is revealed.
     */
    val sangriaJingle: List<SoundCue> = buildList {
        pling(0, 1.0f, volume = 1.0f)
        for (i in 0..11) pling(i, LOW[i], HIGH[i])
        for (tick in intArrayOf(13, 16, 19)) pling(tick, 0.7936508f, 1.0f, 1.1746032f)
        for (i in 0..11) pling(24 + i, LOW[12 - i], HIGH[12 - i])
        for (tick in intArrayOf(37, 40, 43)) pling(tick, 0.8888889f, 1.1111112f, 1.3333334f)
        for (i in 0..12) pling(48 + i, LOW[i], HIGH[12 - i])
        for (j in 1..11) pling(60 + j, HIGH[j], LOW[12 - j])
        for (tick in 72..82 step 2) pling(tick, *FINAL_CHORD)
    }

    private val sangriaJingleTicks: Int = sangriaJingle.maxOf { it.tick }

    @Volatile
    private var sangriaJingleUntil = 0L

    /** True while a replayed jingle is still running; used to drop the server's late duplicate. */
    fun isSangriaJinglePlaying(): Boolean = System.currentTimeMillis() < sangriaJingleUntil

    fun playSangriaJingle() {
        sangriaJingleUntil = System.currentTimeMillis() + sangriaJingleTicks * 50L + 250L
        UiSounds.playSequence(sangriaJingle)
    }

    private fun MutableList<SoundCue>.pling(tick: Int, vararg pitches: Float, volume: Float = JINGLE_VOLUME) =
        cue(tick, SoundEvents.NOTE_BLOCK_PLING, *pitches, volume = volume)

    private fun MutableList<SoundCue>.cue(tick: Int, sound: Holder<SoundEvent>, vararg pitches: Float, volume: Float) =
        cue(tick, sound.value(), *pitches, volume = volume)

    private fun MutableList<SoundCue>.cue(tick: Int, sound: SoundEvent, vararg pitches: Float, volume: Float) {
        for (pitch in pitches) add(SoundCue(tick, sound, pitch, volume))
    }
}
