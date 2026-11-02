package dev.cd.adrift.features.boss

import dev.cd.adrift.Adrift
import dev.cd.adrift.events.LevelEvent
import dev.cd.adrift.events.TickEvent
import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module
import dev.cd.adrift.utils.SlayerUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.ARGB

object Twinclaws : Module(
    "Twinclaws",
    Category.BOSS,
    "Louder Twinclaws countdown and an on-screen alert when Twinclaws starts."
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

    private val fixSound by bool("Fix Sound", true, "Replaces the quiet countdown plings with louder ones.")
    private val volume by double("Volume", 1.0, 0.5..2.0, 0.1, "Countdown volume.").showIf { fixSound }
    private val alert = bool("Alert", false, "Shows a title when Twinclaws starts.")
    private val alertDelay by int("Alert Delay", 0, 0..1000, 10, "Delay before the alert.", "ms").showIf { alert() }

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
            if (!fixSound || !inTwinclaws || sound.value().location() != PLING) return@onReceive
            event.cancel()
        }

        on<TickEvent.Server> { mc.execute { onServerTick() } }
        on<LevelEvent.Load> { reset() }

        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Adrift.id("twinclaws_alert"), ::renderAlert)
    }

    override fun onDisable() {
        reset()
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
        if (fixSound) playCountdown(name)
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

    private fun renderAlert(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (!enabled || !alert() || alertUntil <= System.currentTimeMillis()) return

        val font = mc.font
        val text = Component.literal("§6§lTWINCLAWS!")
        val titleWidth = font.width(text)
        graphics.pose().pushMatrix()
        graphics.pose().translate(graphics.guiWidth() / 2f, graphics.guiHeight() / 2f)
        graphics.pose().scale(3f, 3f)
        graphics.textWithBackdrop(font, text, -titleWidth / 2, -10, titleWidth, ARGB.white(255))
        graphics.pose().popMatrix()
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
