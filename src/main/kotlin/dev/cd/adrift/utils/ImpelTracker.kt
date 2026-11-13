package dev.cd.adrift.utils

import dev.cd.adrift.events.EventBus
import dev.cd.adrift.events.ImpelAction
import dev.cd.adrift.events.ImpelEvent
import dev.cd.adrift.events.LevelEvent
import net.minecraft.ChatFormatting
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import kotlin.math.abs

/**
 * Follows our impels and posts [ImpelEvent.Success] when the server accepts one.
 *
 * While an impel is pending the server resends its subtitle ("Impel: CLICK UP 1.4s") every tick. When the action
 * is accepted the subtitles stop and one arrow-hit sound plays instead, on the next tick. The sound alone is not
 * enough (other things play it) and neither is the subtitle stopping (the boss or we may have died), so both are
 * required: the sound has to follow a recent subtitle.
 */
object ImpelTracker {
    private val SUBTITLE_REGEX = Regex("^Impel: (CLICK UP|CLICK DOWN|JUMP|SNEAK) [\\d.]+s$")

    private const val SUCCESS_SOUND = "entity.arrow.hit_player"
    private const val SUCCESS_VOLUME = 0.9f
    private const val SUCCESS_PITCH = 0.698f
    private const val TOLERANCE = 0.02f

    /**
     * How long after the last subtitle the sound still counts. It normally lands within a tick or two; this
     * leaves room for a lag spike while staying well under the gap between two impels.
     */
    private const val CONFIRM_WINDOW_MS = 500L

    // Impel state, main thread only. Times are packet arrival times, so client lag doesn't skew them
    private var action: ImpelAction? = null
    private var startedAt = 0L
    private var lastSubtitleAt = 0L

    fun register() {
        EventBus.onReceive<ClientboundSetSubtitleTextPacket> {
            val raw = text().string
            val plain = ChatFormatting.stripFormatting(raw) ?: raw
            val label = SUBTITLE_REGEX.matchEntire(plain)?.groupValues?.get(1) ?: return@onReceive
            val seen = ImpelAction.of(label) ?: return@onReceive
            val now = System.currentTimeMillis()
            mc.execute { onSubtitle(seen, now) }
        }

        EventBus.onReceive<ClientboundSoundPacket> {
            if (sound.value().location().path != SUCCESS_SOUND) return@onReceive
            if (abs(volume - SUCCESS_VOLUME) > TOLERANCE || abs(pitch - SUCCESS_PITCH) > TOLERANCE) return@onReceive
            val now = System.currentTimeMillis()
            mc.execute { onSuccessSound(now) }
        }

        EventBus.on<LevelEvent.Load> { action = null }
    }

    private fun onSubtitle(seen: ImpelAction, now: Long) {
        // A different action, or a gap in the subtitles, means a new impel
        if (action != seen || now - lastSubtitleAt > CONFIRM_WINDOW_MS) {
            action = seen
            startedAt = now
        }
        lastSubtitleAt = now
    }

    private fun onSuccessSound(now: Long) {
        val done = action ?: return
        action = null
        if (now - lastSubtitleAt > CONFIRM_WINDOW_MS) return
        ImpelEvent.Success(done, now - startedAt).post()
    }
}
