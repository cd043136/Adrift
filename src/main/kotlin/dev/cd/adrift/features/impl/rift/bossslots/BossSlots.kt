package dev.cd.adrift.features.impl.rift.bossslots

import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import dev.cd.adrift.events.SlayerEvent
import dev.cd.adrift.utils.Category
import dev.cd.adrift.utils.SlayerUtils
import dev.cd.adrift.utils.VampireDrop
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.core.Holder
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.Duration.Companion.seconds

/**
 * Reveals your vampire boss drops when it dies, either as a CSGO-style case
 * opening ([BossDropsSpinner]) or as an in-world [BatSwarm] that never takes
 * over the screen. Both land on the actual drops parsed from chat; when
 * nothing rare drops they land on Coven Seal (the guaranteed token).
 *
 * Anti-spoil: while collecting (short window after death) and while spinning,
 * ALL incoming server chat is hidden via `MessageEvent.Chat` cancel (packet
 * itself is left alone so other mods still see it) and re-sent in order once
 * the reel stops / the bats pop (or the screen is closed early). This covers the
 * rare drop line(s) plus everything after them up to the last hidden message.
 */
object BossSlots : Module(
    "Boss Slots",
    category = Category.RIFT,
    description = "Case opening or in-world bat swarm reveal for vampire boss drops",
) {
    private val mode by SelectorSetting(
        "Mode",
        default = "Case Opening",
        options = listOf("Case Opening", "Bat Swarm"),
        desc = "Case Opening takes over the screen; Bat Swarm plays in the world so you can keep moving",
    )
    private val hideDrops by BooleanSetting(
        "Hide Drop Messages",
        default = true,
        desc = "Hide all chat until the drops are revealed, then re-send it in order",
    )
    private val muteSounds by BooleanSetting(
        "Mute Sounds",
        default = true,
        desc = "Cancel incoming sound packets until the drops are revealed (not re-played)",
    )
    private val playSounds by BooleanSetting(
        "Play Sounds",
        default = true,
        desc = "Play reveal sounds and replay the Sangria Dye jingle when it is revealed",
    )
    private val winEffects by BooleanSetting(
        "Win Effects",
        default = true,
        desc = "Celebrate The One / Unfanged and Sangria Dye (casino banners in Case Opening, sounds only in Bat Swarm)",
    )
    private val hideItems by BooleanSetting(
        "Hide Dropped Items",
        default = true,
        desc = "Hide item entities while a boss is alive or its drops are being revealed",
    )
    private val spinDuration by NumberSetting(
        "Spin Duration",
        default = 7.0,
        min = 2.0,
        max = 10.0,
        increment = 0.5,
        desc = "Total time the spin animation takes",
        unit = "s"
    ).withDependency { mode == MODE_CASE }

    private const val MODE_CASE = 0
    private const val MODE_SWARM = 1

    private const val QUEST_FAILED = "SLAYER QUEST FAILED!"
    private const val MAX_HIDDEN = 50
    private const val COLLECT_TIMEOUT_MS = 15_000L

    private var collecting = false
    private var collectingSince = 0L
    private var spinning = false
    private var screen: BossDropsSpinner? = null
    private var swarm: BatSwarm? = null

    /** Rift state cached on tick so the render-thread mixin never touches client state. */
    @Volatile
    private var inRiftCached = false

    @Volatile
    private var hideItemsCached = false

    private val pendingDrops = mutableListOf<VampireDrop>()
    private val hidden = mutableListOf<Component>()
    private var suppressingChat = false

    /**
     * Called from the entity-render mixin. Hides every item entity while a
     * boss is alive or the case opening is on screen.
     */
    @JvmStatic
    fun shouldHideItemEntities(entity: Entity): Boolean {
        if (!enabled || !hideItems) return false
        if (!hideItemsCached) return false
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
     * Closing the spinner early stops muting while the server's own dye jingle
     * may still be arriving; drop it so it doesn't double up with the replay.
     */
    private fun isSangriaJingleEcho(sound: Holder<SoundEvent>): Boolean =
        enabled && muteSounds && CelebrationSounds.isSangriaJinglePlaying() &&
            sound.value().location() == SoundEvents.NOTE_BLOCK_PLING.value().location()

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
            val active = swarm
            if (active != null && !active.isRevealed) {
                // Next boss is up: scatter the bats and reveal now. The reveal
                // drains the hidden chat and clears state, so keep it intact.
                mc.execute { active.interrupt() }
                return@on
            }
            pendingDrops.clear()
            hidden.clear()
            suppressingChat = false
            collectingSince = 0L
        }

        on<SlayerEvent.Death> {
            if (!enabled || LocationUtils.currentArea != Island.Rift) return@on
            if (spinning) return@on
            if (mode == MODE_SWARM) {
                // In the world, so an open screen doesn't block it.
                if (!collecting) collectingSince = System.currentTimeMillis()
                collecting = true
                spinning = true
                markChatSuppressing()
                val pos = bossPos
                mc.execute { startSwarm(pos, pendingDrops.toList(), scripted = null) }
                return@on
            }
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
            hideItemsCached = inRiftCached &&
                (SlayerUtils.boss != null || mc.screen is BossDropsSpinner || spinning || collecting ||
                    swarm?.hidesItems == true)
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
                steerReveal()
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
                steerReveal()
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
            swarm = null
            inRiftCached = false
            hideItemsCached = false
            pendingDrops.clear()
            hidden.clear()
            suppressingChat = false
        }

        onReceive<ClientboundSoundPacket> {
            if (shouldMuteSounds() || isSangriaJingleEcho(sound)) it.cancel()
        }

        onReceive<ClientboundSoundEntityPacket> {
            if (shouldMuteSounds() || isSangriaJingleEcho(sound)) it.cancel()
        }

        on<RenderEvent.Extract> {
            val active = swarm ?: return@on
            active.render(this)
            if (active.finished && swarm === active) swarm = null
        }

        HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath("adrift", "bat_swarm_sky")) { graphics, _ ->
            swarm?.renderHud(graphics)
        }
    }

    /** Drops arrive during the animation; steer the reels / swarm still running toward the real result. */
    private fun steerReveal() {
        val drops = pendingDrops.toList()
        screen?.let { mc.execute { it.updateWinningDrops(drops) } }
        swarm?.let { mc.execute { it.updateDrops(drops) } }
    }

    /** Client thread only. [scripted] stands in for drop chat when simulating. */
    private fun startSwarm(pos: Vec3, preBuffered: List<VampireDrop>, scripted: List<VampireDrop>?) {
        val created = BatSwarm(pos, playSounds, ::onSwarmReveal, scripted)
        swarm = created
        // The dye's WOW line can race ahead of death detection.
        if (preBuffered.isNotEmpty()) created.updateDrops(preBuffered)
    }

    private fun onSwarmReveal(drops: List<VampireDrop>) {
        val count = drainHidden()
        spinning = false
        collecting = false
        collectingSince = 0L
        pendingDrops.clear()
        if (count > 0) println("[Adrift] BossSlots: bats popped, re-sent $count hidden message(s)")
        if (!playSounds) return
        if (VampireDrop.SANGRIA in drops) CelebrationSounds.playSangriaJingle()
        if (winEffects) CelebrationSounds.play(WinTier.of(drops))
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
        showSpinner(wins) { count ->
            if (count > 0) {
                println("[Adrift] BossSlots: screen closed early, re-sent $count hidden message(s)")
            }
        }
    }

    /** Opens the spinner; [onClosed] receives how many hidden messages were re-sent on close. */
    private fun showSpinner(drops: List<VampireDrop>, onClosed: (Int) -> Unit = {}) {
        val spinner = BossDropsSpinner(
            drops,
            spinDuration.seconds,
            playSounds,
            winEffects,
            onSpinStopped = { releaseHiddenChatAfterSpin() },
            onFinish = {
                val count = drainHidden()
                spinning = false
                collecting = false
                collectingSince = 0L
                screen = null
                onClosed(count)
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

    /** Plays the selected reveal with the given drops, anywhere (the swarm spawns 6 blocks ahead). */
    fun simulate(drops: List<VampireDrop>) {
        mc.execute {
            if (spinning) {
                modMessage("Reveal already in progress.")
                return@execute
            }
            if (mode == MODE_SWARM) {
                val player = mc.player ?: return@execute
                val look = player.lookAngle.multiply(1.0, 0.0, 1.0).normalize()
                spinning = true
                markChatSuppressing()
                startSwarm(player.position().add(look.scale(6.0)), emptyList(), scripted = drops)
                return@execute
            }
            if (mc.screen != null) {
                modMessage("Close the current screen before simulating a roll.")
                return@execute
            }
            spinning = true
            markChatSuppressing()
            showSpinner(drops)
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

        if (rng.nextDouble() < VampireDrop.SANGRIA_CHANCE) drops.add(VampireDrop.SANGRIA)
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
