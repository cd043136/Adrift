package dev.cd.adrift.features.boss

import dev.cd.adrift.events.LevelEvent
import dev.cd.adrift.events.TickEvent
import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module
import dev.cd.adrift.hud.HudCanvas
import dev.cd.adrift.utils.SlayerUtils
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents

object Twinclaws : Module(
    "Twinclaws",
    Category.BOSS,
    "Louder Twinclaws countdown and an on-screen alert when Twinclaws starts.",
    toggle = "Twinclaws",
) {
    private val twinclawsTimerRegex = Regex("""TWINCLAWS (\d+(?:\.\d+)?)s""")

    /** Countdown thresholds (seconds left) and the pitch Hypixel plays at each. */
    private val twinclawSounds = listOf(
        1.6f to 1.4920635f,
        1.4f to 1.6984127f,
        1.2f to 1.8888888f,
        1.0f to 2.0952382f,
        0.8f to 2.2857144f,
        0.6f to 2.4920635f,
        0.4f to 2.6984127f,
        0.3f to 2.7936509f,
        0.2f to 2.8888888f,
        0.1f to 3.0f,
    )

    private const val ALERT_MS = 1500L

    private val fixSound = bool("Fix Sound", true, "Replaces the quiet countdown plings with louder ones.")
    private val volume by double("Volume", 1.0, 0.5..2.0, 0.1, "Countdown volume.", parent = fixSound)
    private val alertText = Component.literal("§6§lTWINCLAWS!")
    private val alert = hud("Alert", "Shows an alert when Twinclaws starts.", 0.5f, 0.43f, default = false, title = "Twinclaws Alert", scale = 3f) { preview ->
        if (!preview && alertUntil <= System.currentTimeMillis()) return@hud 0 to 0
        val pad = HudCanvas.BACKDROP
        text(alertText, pad, pad, backdrop = true)
        width(alertText) + pad * 2 to mc.font.lineHeight + pad * 2
    }
    private val alertDelay by int("Alert Delay", 0, 0..1000, 10, "Delay before the alert.", "ms", parent = alert)

    private val anyOn get() = fixSound() || alert()

    override val enabled get() = super.enabled && anyOn

    init {
        upgradedFrom { anyOn }
    }

    private val PLING = SoundEvents.NOTE_BLOCK_PLING.value().location()

    /** Read on the netty thread to drop the server's plings. */
    @Volatile private var inTwinclaws = false
    private var prevDisplayed: Float? = null
    private val played = HashSet<Float>()

    private var wasTwinclaws = false
    private var pendingAlertAt = 0L
    private var alertUntil = 0L

    init {
        onReceive<ClientboundSoundPacket> { event ->
            // todo: option to enable on other bosses maybe
            if (!fixSound() || !inTwinclaws || sound.value().location() != PLING) return@onReceive
            event.cancel()
        }

        on<TickEvent.Server> { mc.execute { onServerTick() } }
        on<LevelEvent.Load> { reset() }
        for (switch in listOf(fixSound, alert)) switch.onChange { if (!enabled) reset() }
    }

    private fun onServerTick() {
        if (!enabled) return
        val boss = SlayerUtils.boss ?: SlayerUtils.lootshareBoss ?: return reset()
        val name = mc.level?.getEntity(boss.id + 2)?.plainTextName
        if (name == null || !name.contains("TWINCLAWS")) {
            inTwinclaws = false
            prevDisplayed = null
            played.clear()
            wasTwinclaws = false
            pendingAlertAt = 0L
            return
        }
        inTwinclaws = true
        // Only our own boss gets the alert
        if (boss === SlayerUtils.boss) pollAlert()
        if (fixSound()) playCountdown(name)
    }

    private fun playCountdown(name: String) {
        val displayed = twinclawsTimerRegex.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: return
        val prev = prevDisplayed
        prevDisplayed = displayed
        if (prev == null) return
        if (displayed > prev) played.clear()

        val vol = volume.toFloat()
        for ((threshold, pitch) in twinclawSounds) {
            if (threshold !in displayed..<prev || !played.add(threshold)) continue
            mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), pitch, vol.coerceAtMost(1.0f)))
            // Sounds cap at full volume, so a second one makes it louder
            if (vol > 1.0f) mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), pitch, vol - 1.0f))
        }
    }

    private fun pollAlert() {
        if (!alert()) return
        val now = System.currentTimeMillis()
        if (!wasTwinclaws) {
            wasTwinclaws = true
            val delay = alertDelay.toLong()
            if (delay <= 0L) alertUntil = now + ALERT_MS else pendingAlertAt = now + delay
        } else if (pendingAlertAt != 0L && now >= pendingAlertAt) {
            pendingAlertAt = 0L
            alertUntil = now + ALERT_MS
        }
    }

    private fun reset() {
        inTwinclaws = false
        prevDisplayed = null
        played.clear()
        wasTwinclaws = false
        pendingAlertAt = 0L
        alertUntil = 0L
    }
}
