package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.renderBoundingBox
import dev.cd.adrift.utils.Category
import dev.cd.adrift.utils.SlayerUtils
import net.minecraft.client.player.RemotePlayer
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity

object BossHighlight : Module(
    "Boss Highlight",
    category = Category.RIFT,
    description = "Dynamic boss highlight"
) {
    private const val ATTACK_RANGE = 3.0
    private const val STEAK_SYMBOL = "҉"

    private val onOtherBoss by BooleanSetting(
        "Highlight other boss",
        default = false,
        desc = "Enable highlight on other bosses"
    )
    private val dynamicColouring by BooleanSetting(
        "Dynamic colour",
        default = false,
        desc = "Change colour when within attack range"
    )
    private val steakColouring by BooleanSetting(
        "Steak colour",
        default = false,
        desc = "Change colour when it's steakable, overrides Dynamic colour"
    )
    private val mode by SelectorSetting(
        name = "Render Mode",
        default = "Box",
        options = listOf("Filled", "Box", "Filled Box", "Outline", "Custom"),
        desc = ""
    )

    private val defaultColour by ColorSetting(
        "Default colour",
        default = Color("ffffff40"),
        allowAlpha = true,
        desc = ""
    )
    private val attackableColour by ColorSetting(
        "Nearby colour",
        default = Color("ff555540"),
        allowAlpha = true,
        desc = "Colour when attackable"
    ).withDependency { dynamicColouring }
    private val steakableColour by ColorSetting(
        "Steakable colour",
        default = Color("59110140"),
        allowAlpha = true,
        desc = "Colour when steakable"
    ).withDependency { steakColouring }

    init {
        on<RenderEvent.Extract> {
            if (!isBoxMode()) return@on
            targets().forEach { drawStyledBox(it.renderBoundingBox, baseColorFor(it), mode, true) }
        }
    }

    fun targets(): List<Entity> = buildList {
        SlayerUtils.boss?.takeIf { it.isAlive }?.let(::add)
        if (onOtherBoss) SlayerUtils.lootshareBoss
            ?.takeIf {
                it.isAlive && it !== SlayerUtils.boss }
            ?.let(::add)
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
    fun baseColorFor(boss: Entity): Color {
        if (!dynamicColouring && !steakColouring) return defaultColour
        val player = mc.player ?: return defaultColour

        if (steakColouring && boss is RemotePlayer && boss.isSteakable()) return steakableColour
        return if (dynamicColouring && player.distanceTo(boss) <= ATTACK_RANGE) attackableColour else defaultColour
    }

    @JvmStatic
    fun fillColorFor(boss: Entity): Int = baseColorFor(boss).rgba

    @JvmStatic
    fun glowColorFor(boss: Entity): Int = ARGB.opaque(baseColorFor(boss).rgba)

    private fun RemotePlayer.isSteakable(): Boolean {
        val bossNametag = this.level().getEntity(this.id + 1)
        return bossNametag?.plainTextName?.contains(STEAK_SYMBOL) ?: false
    }
}
