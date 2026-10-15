package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import dev.cd.adrift.events.SlayerEvent
import dev.cd.adrift.utils.Category
import dev.cd.adrift.utils.VampireDrop
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import kotlin.time.Duration.Companion.seconds

/**
 * Opens a slot-machine reveal when your vampire boss dies. The reels land on
 * the actual drops parsed from chat; when nothing rare drops they land on
 * Coven Seal (the guaranteed token).
 *
 * Anti-spoil: while collecting (short window after death) and while spinning,
 * incoming `RARE DROP!` / `VERY RARE DROP!` / `CRAZY RARE DROP!` lines are
 * hidden and re-sent to chat once the animation finishes (or is skipped).
 */
object BossSlots : Module(
    "Boss Slots",
    category = Category.RIFT,
    description = "Slot machine reveal for vampire boss drops",
) {
    private val spinDuration by NumberSetting(
        "Spin Duration",
        default = 3.0,
        min = 1.0,
        max = 10.0,
        increment = 0.5,
        desc = "Base reel spin time in seconds",
    )
    private val hideDrops by BooleanSetting(
        "Hide Drop Messages",
        default = true,
        desc = "Hide RARE DROP! messages during the spin and re-send them after",
    )
    private val playSounds by BooleanSetting(
        "Play Sounds",
        default = true,
        desc = "Play slot tick sounds",
    )
    private val hideItems by BooleanSetting(
        "Hide Dropped Items",
        default = true,
        desc = "Hide item entities near the dead boss until the spin finishes",
    )

    private const val QUEST_FAILED = "SLAYER QUEST FAILED!"
    private const val MAX_HIDDEN = 10

    private var collecting = false
    private var spinning = false
    private var screen: BossDropsSpinner? = null

    /** Rift state cached on tick so the render-thread mixin never touches client state. */
    @Volatile
    private var inRiftCached = false

    private val pendingDrops = mutableListOf<VampireDrop>()
    private val hidden = mutableListOf<Component>()

    /**
     * Called from the entity-render mixin. Hides every item entity while in
     * the Rift, no fight tracking involved.
     */
    @JvmStatic
    fun shouldHideItemEntities(entity: Entity): Boolean {
        if (!enabled || !hideItems) return false
        if (!inRiftCached) return false
        return entity is ItemEntity
    }

    init {
        on<SlayerEvent.Spawn> {
            if (LocationUtils.currentArea != Island.Rift) return@on
            pendingDrops.clear()
            hidden.clear()
        }

        on<SlayerEvent.Death> {
            if (!enabled || LocationUtils.currentArea != Island.Rift) return@on
            if (spinning || mc.screen != null) return@on
            pendingDrops.clear()
            hidden.clear()
            collecting = true
            println("[Adrift] BossSlots: boss died, revealing immediately")
            mc.execute { openScreen() }
        }

        on<TickEvent.Server> {
            inRiftCached = LocationUtils.currentArea == Island.Rift
        }

        on<MessageEvent.Chat> {
            if (!enabled || LocationUtils.currentArea != Island.Rift) return@on

            if (!collecting && !spinning) return@on

            if (message.contains(QUEST_FAILED)) {
                collecting = false
                pendingDrops.clear()
                return@on
            }

            val lines = message.split("\n")
            val found = lines.mapNotNull(VampireDrop::fromChatLine)
            if (found.isEmpty()) return@on

            pendingDrops.addAll(found)
            // Drops arrive during the animation; steer the reels that are
            // still spinning toward the real result.
            val active = screen
            if (active != null) {
                val drops = pendingDrops.toList()
                mc.execute { active.updateWinningDrops(drops) }
            }
            if (hideDrops && hidden.size < MAX_HIDDEN) {
                cancel()
                hidden.add(component)
            }
        }

        on<LevelEvent.Load> {
            collecting = false
            spinning = false
            screen = null
            inRiftCached = false
            pendingDrops.clear()
            hidden.clear()
        }
    }

    private fun openScreen() {
        if (!enabled || spinning) return
        if (LocationUtils.currentArea != Island.Rift) {
            pendingDrops.clear()
            hidden.clear()
            return
        }
        if (mc.player == null) return
        if (mc.screen != null) {
            // Player opened something (chest, chat); don't override it.
            // Re-send anything we hid so messages are never lost.
            resendHidden(hidden.toList())
            hidden.clear()
            pendingDrops.clear()
            return
        }
        val wins = VampireDrop.resolveWinningDrops(pendingDrops.toList())
        pendingDrops.clear()
        spinning = true
        val queued = hidden.toList()
        hidden.clear()
        val spinner = BossDropsSpinner(wins, spinDuration.seconds, playSounds) {
            resendHidden(queued)
            spinning = false
            collecting = false
            screen = null
            println("[Adrift] BossSlots: reveal finished, re-sent ${queued.size} hidden message(s)")
        }
        screen = spinner
        mc.setScreen(spinner)
    }

    private fun resendHidden(messages: List<Component>) {
        if (messages.isEmpty()) return
        messages.forEach { mc.gui.chat.addClientSystemMessage(it) }
    }
}
