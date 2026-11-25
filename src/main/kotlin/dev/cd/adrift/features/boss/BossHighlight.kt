package dev.cd.adrift.features.boss

import dev.cd.adrift.events.RenderWorldEvent
import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module
import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.SlayerUtils
import dev.cd.adrift.utils.render.BoxStyle
import dev.cd.adrift.utils.render.drawStyledBoxes
import dev.cd.adrift.utils.renderBoundingBox
import net.minecraft.client.player.RemotePlayer
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity

object BossHighlight : Module(
    "Render",
    Category.BOSS,
    "Dynamic boss highlight, including a custom boss render.",
    toggle = "Boss Highlight",
) {
    private const val ATTACK_RANGE = 3.0
    private const val STEAK_SYMBOL = "҉"

    private enum class RenderMode(private val label: String, val boxStyle: BoxStyle?) {
        FILLED("Filled", BoxStyle.FILLED),
        BOX("Box", BoxStyle.OUTLINE),
        FILLED_BOX("Filled Box", BoxStyle.FILLED_OUTLINE),
        OUTLINE("Outline", null),
        CUSTOM("Custom", null);

        val isEntity: Boolean get() = this == OUTLINE || this == CUSTOM

        override fun toString() = label
    }

    private val mode by choice(
        "Render Mode", RenderMode.BOX,
        "Box styles draw around the boss; Outline makes it glow; Custom draws it as a flat silhouette with a glow."
    )
    private val onOtherBoss by bool("Highlight Other Boss", false, "Also highlights a boss you're helping someone with.")
    private val dynamicColouring = bool("Dynamic Colour", false, "Changes colour when the boss is within attack range.")
    private val steakColouring = bool("Steak Colour", false, "Changes colour when the boss is steakable; overrides Dynamic Colour.")
    private val defaultColour by color("Default Colour", Color("ffffff40"), "Highlight colour.")
    private val attackableColour by color("Nearby Colour", Color("ff555540"), "Colour while the boss is within attack range.", parent = dynamicColouring)
    private val steakableColour by color("Steakable Colour", Color("59110140"), "Colour while the boss is steakable.", parent = steakColouring)

    init {
        on<RenderWorldEvent> {
            val style = mode.boxStyle ?: return@on
            val targets = targets()
            if (targets.isEmpty()) return@on
            drawStyledBoxes(targets.map { it.renderBoundingBox to baseColorFor(it) }, style)
        }
    }

    /** Bosses to highlight; ours sits out while it breakdances in a disco mania. */
    fun targets(): List<Entity> = buildList {
        SlayerUtils.boss?.takeIf { it.isAlive && !ManiaHelper.isDancing(it) }?.let(::add)
        if (onOtherBoss) SlayerUtils.lootshareBoss?.takeIf { it.isAlive && it !== SlayerUtils.boss }?.let(::add)
    }

    @JvmStatic
    fun isBoxMode(): Boolean = mode.boxStyle != null

    @JvmStatic
    fun isCustomMode(): Boolean = mode == RenderMode.CUSTOM

    @JvmStatic
    fun isEntityActive(): Boolean = enabled && mode.isEntity

    @JvmStatic
    fun isEntityTarget(entity: Entity?): Boolean {
        if (entity !is RemotePlayer || !isEntityActive() || !entity.isAlive) return false
        if (ManiaHelper.isDancing(entity)) return false
        return entity === SlayerUtils.boss || (onOtherBoss && entity === SlayerUtils.lootshareBoss)
    }

    @JvmStatic
    fun hasEntityTargets(): Boolean {
        if (!isEntityActive()) return false
        if (SlayerUtils.boss?.isAlive == true) return true
        return onOtherBoss && SlayerUtils.lootshareBoss?.isAlive == true
    }

    /**
     * Whether Custom mode has a boss to draw, i.e. whether its depth-tested glow needs scene depth this frame.
     * Outline mode uses vanilla's glow, which ignores depth, and a breakdancing boss isn't drawn at all.
     */
    @JvmStatic
    fun hasCustomTargets(): Boolean {
        if (!enabled || !isCustomMode()) return false
        if (SlayerUtils.boss?.let { it.isAlive && !ManiaHelper.isDancing(it) } == true) return true
        return onOtherBoss && SlayerUtils.lootshareBoss?.let { it.isAlive && !ManiaHelper.isDancing(it) } == true
    }

    @JvmStatic
    fun baseColorFor(boss: Entity): Color {
        val steak = steakColouring()
        val dynamic = dynamicColouring()
        if (!dynamic && !steak) return defaultColour
        val player = mc.player ?: return defaultColour

        if (steak && boss is RemotePlayer && boss.isSteakable()) return steakableColour
        return if (dynamic && player.distanceTo(boss) <= ATTACK_RANGE) attackableColour else defaultColour
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
