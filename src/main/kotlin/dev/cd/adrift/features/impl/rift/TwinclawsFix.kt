package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.clickgui.settings.impl.NumberSetting
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

    private val volume by NumberSetting(
        "Volume",
        default = 1.0,
        range = 0.5..2.0,
        increment = 0.1,
        desc = "Sound volume"
    )

    private val PLING = SoundEvents.NOTE_BLOCK_PLING.value().location()

    @Volatile private var inTwinclaws = false

    private var prevDisplayed: Float? = null
    private val played = mutableSetOf<Float>()

    init {
        onReceive<ClientboundSoundPacket> { event ->
            // todo: option to enable on other bosses maybe
            if (!inTwinclaws || sound.value().location() != PLING) return@onReceive
            event.cancel()
        }

        on<TickEvent.Server> { mc.execute { onServerTick() } }
    }

    override fun onDisable() {
        super.onDisable()
        resetTwinclawState()
    }

    private fun onServerTick() {
        val temp = SlayerUtils.boss ?: SlayerUtils.lootshareBoss ?: return resetTwinclawState()
        val name = mc.level?.getEntity(temp.id + 2)?.plainTextName ?: return resetTwinclawState()

        if (!name.contains("TWINCLAWS")) return resetTwinclawState()
        inTwinclaws = true

        val displayed = twinclawsTimerRegex.find(name)
            ?.groupValues?.get(1)?.toFloatOrNull()
            ?: return

        val prev = prevDisplayed
        if (prev == null) {
            prevDisplayed = displayed
            return
        }
        if (displayed > prev) played.clear()

        for ((threshold, pitch) in twinclawSounds) {
            if (threshold in displayed..<prev && played.add(threshold)) {
                val vol = volume.toFloat()

                mc.soundManager.play(
                    SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), pitch, vol.coerceAtMost(1.0f))
                )

                if (vol > 1.0f) {
                    mc.soundManager.play(
                        SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), pitch, vol - 1.0f)
                    )
                }
            }
        }
        prevDisplayed = displayed
    }

    private fun resetTwinclawState() {
        inTwinclaws = false
        prevDisplayed = null
        played.clear()
    }
}
