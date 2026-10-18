package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import dev.cd.adrift.events.SlayerEvent
import dev.cd.adrift.utils.Category
import dev.cd.adrift.utils.VampireDrop
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.Duration.Companion.seconds

/**
 * Opens a CSGO-style case opening reveal when your vampire boss dies. The strip lands on
 * the actual drops parsed from chat; when nothing rare drops they land on
 * Coven Seal (the guaranteed token).
 *
 * Anti-spoil: while collecting (short window after death) and while spinning,
 * ALL incoming server chat is hidden via `MessageEvent.Chat` cancel (packet
 * itself is left alone so other mods still see it) and re-sent in order once
 * the reel stops (or the screen is closed early). This covers the rare drop line(s)
 * plus everything after them up to the last hidden message.
 */
object BossSlots : Module(
    "Boss Slots",
    category = Category.RIFT,
    description = "CSGO-style case opening reveal for vampire boss drops",
) {
    private val hideDrops by BooleanSetting(
        "Hide Drop Messages",
        default = true,
        desc = "Hide all chat during the spin and re-send it in order when the reel stops",
    )
    private val muteSounds by BooleanSetting(
        "Mute Sounds",
        default = true,
        desc = "Cancel incoming sound packets during the spin (not re-played)",
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
    private val spinDuration by NumberSetting(
        "Spin Duration",
        default = 7.0,
        min = 2.0,
        max = 10.0,
        increment = 0.5,
        desc = "Total time the spin animation takes",
        unit = "s"
    )

    private const val QUEST_FAILED = "SLAYER QUEST FAILED!"
    private const val MAX_HIDDEN = 50
    private const val COLLECT_TIMEOUT_MS = 15_000L

    private var collecting = false
    private var collectingSince = 0L
    private var spinning = false
    private var screen: BossDropsSpinner? = null

    /** Rift state cached on tick so the render-thread mixin never touches client state. */
    @Volatile
    private var inRiftCached = false

    private val pendingDrops = mutableListOf<VampireDrop>()
    private val hidden = mutableListOf<Component>()
    private var suppressingChat = false

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

    /** True while incoming server chat is being buffered instead of displayed. */
    @JvmStatic
    fun isSuppressingChat(): Boolean =
        enabled && hideDrops && suppressingChat

    /** True while incoming sound packets are being dropped. */
    private fun shouldMuteSounds(): Boolean =
        enabled && muteSounds && (collecting || spinning)

    /**
     * Buffers a client-rebuilt component (e.g. SlayerDrops rename) so it is
     * re-sent in order with everything else. Returns false when not
     * suppressing or the buffer is full (caller should display immediately).
     */
    fun stashForResend(component: Component): Boolean {
        if (!isSuppressingChat()) return false
        if (hidden.size >= MAX_HIDDEN) return false
        hidden.add(component)
        return true
    }

    /**
     * Swaps an already-buffered original for a rebuilt copy (e.g. renamed),
     * preserving its position so order stays intact. Returns false when the
     * original is not the latest buffered entry.
     */
    fun replaceLastBuffered(original: Component, replacement: Component): Boolean {
        if (hidden.isEmpty()) return false
        if (hidden.last() !== original && hidden.last().string != original.string) return false
        hidden[hidden.lastIndex] = replacement
        return true
    }

    init {
        on<SlayerEvent.Spawn> {
            if (LocationUtils.currentArea != Island.Rift) return@on
            pendingDrops.clear()
            hidden.clear()
            suppressingChat = false
            collectingSince = 0L
        }

        on<SlayerEvent.Death> {
            if (!enabled || LocationUtils.currentArea != Island.Rift) return@on
            if (spinning) return@on
            if (mc.screen != null) {
                // Reveal blocked (chest/chat open): resend any pre-buffered
                // RNG meter lines so they are never lost.
                if (collecting) {
                    collecting = false
                    collectingSince = 0L
                    drainHidden()
                    pendingDrops.clear()
                }
                return@on
            }
            // Don't wipe pre-buffered RNG meter lines that arrived just
            // before death detection (tick-based, so chat races ahead).
            // Spawn already cleared at fight start; openScreen clears after use.
            if (!collecting) {
                collectingSince = System.currentTimeMillis()
            }
            collecting = true
            println("[Adrift] BossSlots: boss died, revealing immediately")
            mc.execute { openScreen() }
        }

        on<TickEvent.Server> {
            inRiftCached = LocationUtils.currentArea == Island.Rift
            if (collecting && !spinning && collectingSince != 0L &&
                System.currentTimeMillis() - collectingSince > COLLECT_TIMEOUT_MS
            ) {
                // RNG meter pre-buffer never led to a reveal (false positive
                // or blocked screen): resend so nothing is lost.
                collecting = false
                collectingSince = 0L
                drainHidden()
                pendingDrops.clear()
            }
        }

        on<MessageEvent.Chat> {
            if (!enabled || LocationUtils.currentArea != Island.Rift) return@on

            // RNG meter reselection lines race ahead of death detection, so
            // they arrive before collecting starts and would otherwise always
            // leak. Hide only — never collect (drops come from RARE DROP /
            // WOW lines), e.g.
            // "RNG METER! Reselected the Unfanged Vampire Part for Vampire
            // Slayer! CLICK HERE to select a new drop!".
            if (VampireDrop.isRngMeterMessage(message)) {
                if (hideDrops && !isCancelled && hidden.size < MAX_HIDDEN) {
                    cancel()
                    hidden.add(component)
                    markChatSuppressing()
                    // Start collecting early so following drop chat stays
                    // hidden too; Death preserves this buffer (no wipe) and
                    // the tick timeout resends if no reveal follows.
                    if (!collecting && !spinning) {
                        collecting = true
                        collectingSince = System.currentTimeMillis()
                    }
                }
                return@on
            }

            // Sangria Dye has no RARE DROP line — only the WOW line, e.g.
            // "WOW! [MVP+] Player found a Sangria Dye!". Collect ours before
            // the guard since it can also race death detection.
            val dyeFound = VampireDrop.fromDyeMessage(message, mc.player?.gameProfile?.name)
            if (dyeFound.isNotEmpty()) {
                pendingDrops.addAll(dyeFound)
                val active = screen
                if (active != null) {
                    val drops = pendingDrops.toList()
                    mc.execute { active.updateWinningDrops(drops) }
                }
                if (hideDrops && !isCancelled && hidden.size < MAX_HIDDEN) {
                    cancel()
                    hidden.add(component)
                    markChatSuppressing()
                    if (!collecting && !spinning) {
                        collecting = true
                        collectingSince = System.currentTimeMillis()
                    }
                }
                return@on
            }

            if (!collecting && !spinning) return@on

            if (message.contains(QUEST_FAILED)) {
                collecting = false
                collectingSince = 0L
                pendingDrops.clear()
                // No spinner to do the resend; drain now so nothing is lost.
                // The fail line itself stays visible.
                if (!spinning) drainHidden()
                return@on
            }

            val found = VampireDrop.fromChatMessage(message, component)
            if (found.isNotEmpty()) {
                pendingDrops.addAll(found)
                // Drops arrive during the animation; steer the reels that are
                // still spinning toward the real result.
                val active = screen
                if (active != null) {
                    val drops = pendingDrops.toList()
                    mc.execute { active.updateWinningDrops(drops) }
                }
            }
            // Hide ALL server chat while active (rare drop + everything after
            // it), not just drop lines. Cancel only blocks vanilla display;
            // the packet already arrived so other mods still see this event.
            // Skip own buffering when another module already cancelled (e.g.
            // SlayerDrops stashed a renamed copy) to avoid double resend.
            if (isCancelled) return@on
            if (hideDrops && hidden.size < MAX_HIDDEN) {
                cancel()
                hidden.add(component)
                markChatSuppressing()
            }
        }

        on<LevelEvent.Load> {
            collecting = false
            collectingSince = 0L
            spinning = false
            screen = null
            inRiftCached = false
            pendingDrops.clear()
            hidden.clear()
            suppressingChat = false
        }

        onReceive<ClientboundSoundPacket> {
            if (shouldMuteSounds()) it.cancel()
        }

        onReceive<ClientboundSoundEntityPacket> {
            if (shouldMuteSounds()) it.cancel()
        }
    }

    private fun openScreen() {
        if (!enabled || spinning) return
        if (LocationUtils.currentArea != Island.Rift) {
            pendingDrops.clear()
            hidden.clear()
            suppressingChat = false
            collectingSince = 0L
            return
        }
        if (mc.player == null) return
        if (mc.screen != null) {
            // Player opened something (chest, chat); don't override it.
            // Re-send anything we hid so messages are never lost.
            drainHidden()
            pendingDrops.clear()
            collecting = false
            collectingSince = 0L
            return
        }
        val wins = VampireDrop.resolveWinningDrops(pendingDrops.toList())
        pendingDrops.clear()
        spinning = true
        markChatSuppressing()
        val spinner = BossDropsSpinner(
            wins,
            spinDuration.seconds,
            playSounds,
            onSpinStopped = { releaseHiddenChatAfterSpin() },
            onFinish = {
                val count = drainHidden()
                spinning = false
                collecting = false
                collectingSince = 0L
                screen = null
                if (count > 0) {
                    println("[Adrift] BossSlots: screen closed early, re-sent $count hidden message(s)")
                }
            },
        )
        screen = spinner
        mc.setScreen(spinner)
    }

    private fun markChatSuppressing() {
        if (hideDrops) suppressingChat = true
    }

    /** Re-sends buffered chat when the reel stops; safe to call multiple times. */
    private fun releaseHiddenChatAfterSpin() {
        mc.execute {
            val count = drainHidden()
            collecting = false
            collectingSince = 0L
            if (count > 0) {
                println("[Adrift] BossSlots: reel stopped, re-sent $count hidden message(s)")
            }
        }
    }

    /** Re-sends buffered chat in original order. Returns count re-sent. */
    private fun drainHidden(): Int {
        if (hidden.isEmpty()) {
            suppressingChat = false
            return 0
        }
        val messages = hidden.toList()
        hidden.clear()
        suppressingChat = false
        resendHidden(messages)
        return messages.size
    }

    private fun resendHidden(messages: List<Component>) {
        if (messages.isEmpty()) return
        messages.forEach { mc.gui.chat.addClientSystemMessage(it) }
    }

    // ---- Simulate command (test hook) ----

    /** Opens the case animation with the given drops, anywhere. */
    fun simulate(drops: List<VampireDrop>) {
        mc.execute {
            if (spinning) {
                modMessage("Case roll already in progress.")
                return@execute
            }
            if (mc.screen != null) {
                modMessage("Close the current screen before simulating a roll.")
                return@execute
            }
            spinning = true
            markChatSuppressing()
            val spinner = BossDropsSpinner(
                drops,
                spinDuration.seconds,
                playSounds,
                onSpinStopped = { releaseHiddenChatAfterSpin() },
                onFinish = {
                    drainHidden()
                    spinning = false
                    collecting = false
                    collectingSince = 0L
                    screen = null
                },
            )
            screen = spinner
            mc.setScreen(spinner)
        }
    }

    /**
     * Parses `/simulate` args. Matches the whole input against drop keys so
     * multi-word names ("the one", "coven seal") work, e.g.
     * `/simulate the_one`, `/simulate quantum soultwist`, `/simulate sangria`.
     * Blank or "random" rolls random loot.
     */
    fun simulate(rawArgs: String) {
        val lower = rawArgs.lowercase().replace("_", " ").replace("-", " ").trim()
        if (lower.isBlank() || lower == "random") {
            simulateRandom()
            return
        }
        val found = VampireDrop.entries.filter { drop ->
            drop.chatKeys.any { lower.contains(it) } ||
                lower.contains(drop.name.lowercase().replace("_", " ")) ||
                DROP_ALIASES[drop].orEmpty().any { lower.contains(it) }
        }
        if (found.isEmpty()) {
            modMessage("Unknown drop(s): \"$rawArgs\". Try quantum, the_one, burger, unfanged, guardian, bubba, fangtastic, soultwist, sangria, seal — or just /simulate for a random roll.")
            return
        }
        simulate(found)
    }

    /** Random realistic-ish roll: usually seals, sometimes a main, often paired with a rune. */
    fun simulateRandom() {
        val rng = ThreadLocalRandom.current()
        val drops = mutableListOf<VampireDrop>()
        if (rng.nextDouble() < 0.35) {
            val mains = VampireDrop.entries.filter { it.pool == VampireDrop.Pool.MAIN }
            val total = mains.sumOf { it.weight }
            var r = rng.nextDouble(total)
            for (drop in mains) {
                r -= drop.weight
                if (r <= 0.0) {
                    drops.add(drop)
                    break
                }
            }
            // Doubles: a main drop is often paired with a rune.
            if (rng.nextDouble() < 0.30) drops.add(VampireDrop.SOULTWIST)
        } else if (rng.nextDouble() < 0.10) {
            drops.add(VampireDrop.SOULTWIST)
        }
        if (rng.nextDouble() < 0.03) drops.add(VampireDrop.SANGRIA)
        simulate(drops)
    }
}

private val DROP_ALIASES: Map<VampireDrop, List<String>> = mapOf(
    VampireDrop.SANGRIA to listOf("dye"),
    VampireDrop.THE_ONE to listOf("the one"),
    VampireDrop.BURGER to listOf("burger"),
    VampireDrop.UNFANGED to listOf("part"),
    VampireDrop.GUARDIAN to listOf("lucky block", "block"),
    VampireDrop.BUBBA to listOf("blister"),
    VampireDrop.FANGTASTIC to listOf("fangtastic", "chocolate", "chip"),
    VampireDrop.QUANTUM to listOf("bundle", "book"),
    VampireDrop.SOULTWIST to listOf("rune"),
    VampireDrop.COVEN_SEAL to listOf("seal"),
)
