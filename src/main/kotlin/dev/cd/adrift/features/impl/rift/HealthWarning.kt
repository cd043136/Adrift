package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import dev.cd.adrift.utils.Category
import dev.cd.adrift.utils.SlayerUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.resources.Identifier

object HealthWarning : Module(
    "Low Health Warning",
    category = Category.RIFT,
    description = "Warns when your hp is low"
) {
    private val vignette by BooleanSetting(
        "Vignette",
        default = true,
        desc = "Enable vignette"
    )
    private val vignetteCol by ColorSetting(
        "Vignette Colour",
        default = Color("ffff0000"),
        desc = "Hue preserved at the screen edges",
        allowAlpha = false
    ).withDependency { vignette }
    private val playSound by SelectorSetting(
        "Sound",
        default = "None",
        options = listOf("None", "Once", "Repeat"),
        desc = "Plays a pling sound when low hp"
    )
    private const val HP_THRESHOLD = 6f
    private const val INTERVAL = 4 // for repeating plings
    private val VIGNETTE_LOCATION = Identifier.withDefaultNamespace("textures/misc/vignette.png")

    private var tickCounter = 0
    private var soundPlayed = false

    init {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath("adrift", "health_warning"),
            ::extractOverlay
        )

        on<TickEvent.End> {
            if (!inBossFight() || playSound == 0) {
                tickCounter = 0
                soundPlayed = false
                return@on
            }

            val player = mc.player
            if (player == null || player.health >= HP_THRESHOLD) {
                tickCounter = 0
                soundPlayed = false
                return@on
            }

            if (playSound == 1) {
                if (soundPlayed) return@on
                soundPlayed = true
            }

            else {
                tickCounter++

                if (tickCounter < INTERVAL) return@on
                tickCounter = 0
            }

            mc.soundManager.play(
                SimpleSoundInstance.forUI(
                    SoundEvents.NOTE_BLOCK_PLING.value(),
                    1f,
                    10f
                )
            )
        }
    }

    private fun inBossFight(): Boolean =
        SlayerUtils.boss != null || SlayerUtils.lootshareBoss != null

    private fun extractOverlay(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (!enabled || !vignette || !inBossFight()) return

        val player = mc.player ?: return
        if (player.health >= HP_THRESHOLD) return

        val screenWidth = graphics.guiWidth()
        val screenHeight = graphics.guiHeight()
        val strength = 1.0f
        val size = Mth.lerp(strength, 2.0f, 1.0f)

        val color = ARGB.colorFromFloat(
            1.0f,
            1.0f - vignetteCol.redFloat * strength,
            1.0f - vignetteCol.greenFloat * strength,
            1.0f - vignetteCol.blueFloat * strength
        )

        graphics.pose().pushMatrix()
        graphics.pose().translate(screenWidth / 2.0f, screenHeight / 2.0f)
        graphics.pose().scale(size, size)
        graphics.pose().translate(-screenWidth / 2.0f, -screenHeight / 2.0f)
        graphics.blit(
            RenderPipelines.VIGNETTE,
            VIGNETTE_LOCATION,
            0,
            0,
            0.0f,
            0.0f,
            screenWidth,
            screenHeight,
            screenWidth,
            screenHeight,
            color
        )
        graphics.pose().popMatrix()
    }
}
