package dev.cd.adrift.utils

import dev.cd.adrift.events.ChatEvent
import dev.cd.adrift.events.EventBus
import dev.cd.adrift.events.LevelEvent
import dev.cd.adrift.events.SlayerEvent
import dev.cd.adrift.events.TickEvent
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.minecraft.client.player.RemotePlayer
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
//? if >=26.2 {
import net.minecraft.world.entity.EntityTypes
//?} else
//import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.Level

/**
 * Finds our vampire boss (and one boss we're helping with), its timer stand, and follows the fight: spawn, mania
 * phases and the kill, posted as [SlayerEvent]s so features don't each track them.
 */
object SlayerUtils {
    private val SPAWN_REGEX = Regex("SLAYER BOSS! The Bloodfiend (?:I|II|III|IV|V) spawned!")

    private const val BOSS_NAME = "Bloodfiend "
    private const val CANDIDATE_WINDOW_MS = 10_000L
    private const val QUEST_COMPLETE = "SLAYER QUEST COMPLETE!"
    private const val QUEST_FAILED = "SLAYER QUEST FAILED!"
    /** How long after the boss vanishes to wait for the quest outcome. */
    private const val OUTCOME_TIMEOUT_TICKS = 200

    private data class SpawnCandidate(val id: Int, val at: Long)
    private val pending = ArrayDeque<SpawnCandidate>()
    private val verified = ArrayDeque<SpawnCandidate>()

    private var awaitingSpawnUntil = 0L

    // Also read on the netty thread, to skip work while there's no fight
    @Volatile var boss: RemotePlayer? = null
    var timer: ArmorStand? = null
    @Volatile var lootshareBoss: RemotePlayer? = null

    /** Whether our boss is in a mania phase right now. */
    var inMania = false
        private set

    // Fight state, main thread only (fighting and awaitingOutcome are also read on the netty thread)
    @Volatile private var fighting = false
    private var fightTicks = 0
    private var maniaCount = 0
    @Volatile private var awaitingOutcome = false
    private var outcomeTicks = 0

    fun register() {
        EventBus.on<TickEvent.Server> {
            // Nothing to follow outside the Rift once the last fight is wrapped up
            if (!Location.inRift && boss == null && lootshareBoss == null && !fighting && !awaitingOutcome) return@on
            mc.execute { tick() }
        }
        EventBus.on<LevelEvent.Load> { reset() }

        EventBus.onReceive<ClientboundAddEntityPacket> { // new slayer armorstand shenanigans
            //? if >=26.2 {
            if (type != EntityTypes.PLAYER) return@onReceive
            //?} else
            //if (type != EntityType.PLAYER) return@onReceive
            if (!Location.inRift) return@onReceive
            val id = id
            val now = System.currentTimeMillis()
            mc.execute {
                if (!Location.inRift) return@execute
                pending.addLast(SpawnCandidate(id, now))
            }
        }

        EventBus.on<ChatEvent> {
            if (!Location.inRift) return@on

            if (message.matches(SPAWN_REGEX)) {
                if (boss?.isAlive == true) return@on // someone else's spawn, ours is already bound
                awaitingSpawnUntil = System.currentTimeMillis() + CANDIDATE_WINDOW_MS
                tryBind()
                return@on
            }

            if (!fighting && !awaitingOutcome) return@on
            val lines = message.split("\n").map { it.trim() }
            when {
                lines.any { it == QUEST_COMPLETE } -> {
                    val ticks = fightTicks
                    endFight()
                    SlayerEvent.Complete(ticks).post()
                }
                lines.any { it == QUEST_FAILED } -> {
                    endFight()
                    SlayerEvent.Fail.post()
                }
            }
        }

        // only support rift for now
        AttackEntityCallback.EVENT.register { _, level, _, entity, _ ->
            onAttack(level, entity)
            InteractionResult.PASS
        }
    }

    private fun reset() {
        val wasFighting = fighting || awaitingOutcome
        boss = null
        lootshareBoss = null
        timer = null
        pending.clear()
        verified.clear()
        awaitingSpawnUntil = 0L
        endFight()
        if (wasFighting) SlayerEvent.Fail.post()
    }

    private fun endFight() {
        fighting = false
        awaitingOutcome = false
        inMania = false
        fightTicks = 0
        maniaCount = 0
        outcomeTicks = 0
    }

    private fun tick() {
        lootshareBoss?.let {
            if (!it.isAlive) lootshareBoss = null
        }

        verifyPending()
        retrySpawnBind()

        val boss = this.boss

        if (timer?.isRemoved == true) timer = null

        // fetch timer stand: +1 = nametag ("Bloodfiend V 625❤"), +2 = timer ("03:59 SPECTRAL 0.9s"), +3 = "SLAYER BOSS"
        if (boss != null && timer == null) {
            val maybe = mc.level?.getEntity(boss.id + 2)
            if (maybe is ArmorStand && maybe.plainTextName.contains(":")) timer = maybe
        }

        // boss death check
        if (boss != null && !boss.isAlive) {
            this.boss = null
            this.timer = null
        }

        tickFight()
    }

    private fun tickFight() {
        if (awaitingOutcome) {
            if (++outcomeTicks > OUTCOME_TIMEOUT_TICKS) {
                endFight()
                SlayerEvent.Fail.post()
            }
            return
        }
        if (!fighting) return

        if (boss?.isAlive != true) {
            // No ManiaEnd: the fight is over, not back to a damage phase
            inMania = false
            fighting = false
            awaitingOutcome = true
            outcomeTicks = 0
            return
        }

        val mania = timer?.plainTextName?.contains("MANIA") == true
        if (mania && !inMania) {
            inMania = true
            SlayerEvent.ManiaStart(++maniaCount).post()
        } else if (!mania && inMania) {
            inMania = false
            SlayerEvent.ManiaEnd(maniaCount).post()
        }
        SlayerEvent.Tick(++fightTicks).post()
    }

    private fun verifyPending() {
        if (!Location.inRift) return
        val level = mc.level ?: return

        pruneExpired()

        val it = pending.iterator()
        while (it.hasNext()) {
            val c = it.next()
            val e = level.getEntity(c.id) as? RemotePlayer ?: continue
            if (e.plainTextName != BOSS_NAME) continue
            it.remove()
            verified.addLast(c)
        }
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        while (pending.firstOrNull()?.let { now - it.at > CANDIDATE_WINDOW_MS } == true) pending.removeFirst()
        while (verified.firstOrNull()?.let { now - it.at > CANDIDATE_WINDOW_MS } == true) verified.removeFirst()
    }

    private fun retrySpawnBind() {
        if (awaitingSpawnUntil == 0L) return
        if (System.currentTimeMillis() >= awaitingSpawnUntil) {
            awaitingSpawnUntil = 0L
            return
        }
        tryBind()
    }

    private fun tryBind() {
        val level = mc.level ?: return
        verifyPending()
        pruneExpired()

        // newest packet wins
        for (i in verified.indices.reversed()) {
            val e = level.getEntity(verified[i].id)
            if (e is RemotePlayer && e.plainTextName == BOSS_NAME) {
                boss = e
                timer = null
                pending.clear()
                verified.clear()
                awaitingSpawnUntil = 0L
                endFight()
                fighting = true
                SlayerEvent.Spawn(e).post()
                return
            }
        }
    }

    private fun onAttack(level: Level, entity: Entity) {
        if (!Location.inRift || lootshareBoss != null) return // 1 boss max, maybe figure out a better system later
        if (entity !is RemotePlayer || entity.plainTextName != BOSS_NAME || entity === boss) return

        val s = level.getEntity(entity.id + 1) ?: return
        if (s is ArmorStand && s.plainTextName.contains("Bloodfiend ")) {
            lootshareBoss = entity
        }
    }
}
