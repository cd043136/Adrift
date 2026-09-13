package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.renderBoundingBox
import dev.cd.adrift.utils.Category
import dev.cd.adrift.utils.SlayerUtils
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity

object BossHighlight : Module(
    "Boss Highlight",
    category = Category.RIFT,
    description = "Dynamic boss highlight"
) {
    private const val FILL_ALPHA = 0x20
    private const val ATTACK_RANGE = 3.0

    private val onOtherBoss by BooleanSetting(
        "Highlight other boss",
        default = false,
        desc = "Enable highlight on other bosses"
    )
    private val dynamicColour by BooleanSetting(
        "Dynamic colour",
        default = false,
        desc = "Change colour when within attack range"
    )
    private val mode by SelectorSetting(
        name = "Render Mode",
        default = "Box",
        options = listOf("Filled", "Box", "Filled Box", "Outline", "Custom"),
        desc = ""
    )
    // cols
    private val defaultColour by ColorSetting(
        "Default colour",
        default = Colors.WHITE,
        allowAlpha = true,
        desc = ""
    )
    private val attackableColour by ColorSetting(
        "Nearby colour",
        default = Colors.MINECRAFT_RED,
        allowAlpha = true,
        desc = "Colour when attackable"
    ).withDependency { dynamicColour }

    init {
        on<RenderEvent.Extract> {
            if (!isBoxMode()) return@on
            targets().forEach { drawStyledBox(it.renderBoundingBox, baseColorFor(it), mode, true) }
        }
    }

    fun targets(): List<Entity> = buildList {
        SlayerUtils.boss?.takeIf { it.isAlive }?.let(::add)
        if (onOtherBoss) SlayerUtils.lootshareBoss?.takeIf { it.isAlive }?.let(::add)
    }

    @JvmStatic
    fun isBoxMode(): Boolean = mode <= 2

    @JvmStatic
    fun isCustomMode(): Boolean = mode == 4

    @JvmStatic
    fun isEntityActive(): Boolean = enabled && (mode == 3 || mode == 4)

    @JvmStatic
    fun isEntityTarget(entity: Entity?): Boolean {
        if (entity == null || !enabled || (mode != 3 && mode != 4)) return false
        return targets().any { it === entity }
    }

    @JvmStatic
    fun baseColorFor(entity: Entity): Color {
        if (!dynamicColour) return defaultColour
        val player = mc.player ?: return defaultColour
        return if (player.distanceTo(entity) <= ATTACK_RANGE) attackableColour else defaultColour
    }

    @JvmStatic
    fun fillColorFor(entity: Entity): Int = ARGB.color(FILL_ALPHA, baseColorFor(entity).rgba)

    @JvmStatic
    fun glowColorFor(entity: Entity): Int = ARGB.opaque(baseColorFor(entity).rgba)
}
