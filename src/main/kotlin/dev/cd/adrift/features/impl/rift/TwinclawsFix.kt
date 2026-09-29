package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Module
import dev.cd.adrift.utils.Category
import dev.cd.adrift.utils.SlayerUtils
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents

object TwinclawsFix : Module(
    "Twinclaws Fix",
    category = Category.RIFT,
    description = "Fix quiet twinclaws sound"
) {
    private val twinclawsTimerRegex = Regex("""TWINCLAWS (\d+(?:\.\d+)?)s""")

    private val twinclawSounds = listOf(
        Pair(1.6f, 1.4920635f),
        Pair(1.4f, 1.6984127f),
        Pair(1.2f, 1.8888888f),
        Pair(1.0f, 2.0952382f),
        Pair(0.8f, 2.2857144f),
        Pair(0.6f, 2.4920635f),
        Pair(0.4f, 2.6984127f),
        Pair(0.3f, 2.7936509f),
        Pair(0.2f, 2.8888888f),
        Pair(0.1f, 3.0f),
    )

    private var prevDisplayed: Float? = null
    private val played = mutableSetOf<Float>()

    init {
        onReceive<ClientboundSoundPacket> { event ->
            // todo: option to enable on other bosses maybe
            val temp = SlayerUtils.boss ?: SlayerUtils.lootshareBoss ?: return@onReceive
            val t = mc.level?.getEntity(temp.id + 2) ?: return@onReceive

            if (!t.plainTextName.contains("TWINCLAWS")) return@onReceive
            sound.value().location().toString()
                .takeIf { it.contains("note_block.pling") }
                ?: return@onReceive
            event.cancel()
        }

        on<TickEvent.Server> {
            val temp = SlayerUtils.boss ?: SlayerUtils.lootshareBoss ?: run { resetTwinclawState(); return@on }
            val t = mc.level?.getEntity(temp.id + 2) ?: run { resetTwinclawState(); return@on }

            if (!t.plainTextName.contains("TWINCLAWS")) {
                resetTwinclawState()
                return@on
            }
            val displayed = twinclawsTimerRegex.find(t.plainTextName)
                ?.groupValues?.get(1)?.toFloatOrNull()
                ?: return@on

            val prev = prevDisplayed
            if (prev == null) {
                prevDisplayed = displayed
                return@on
            }
            if (displayed > prev) played.clear()

            for ((threshold, pitch) in twinclawSounds) {
                if (threshold in displayed..<prev && played.add(threshold)) {
                    // todo: maybe adjustable volume?
                    // vol clamp bypass by playing both at the same tick
                    mc.execute {
                        mc.soundManager.play(
                            SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), pitch, 1.0f)
                        )
                        mc.soundManager.play(
                            SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), pitch, 0.5f)
                        )
                    }
                }
            }
            prevDisplayed = displayed
        }
    }

    private fun resetTwinclawState() {
        prevDisplayed = null
        played.clear()
    }
}