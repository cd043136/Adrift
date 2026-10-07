package dev.cd.adrift.utils

import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import dev.cd.adrift.events.SlayerEvent
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.player.RemotePlayer
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.Level

object SlayerUtils {
    private val SPAWN_REGEX = Regex("SLAYER BOSS! The Bloodfiend (?:I|II|III|IV|V) spawned!")

    private const val BOSS_NAME = "Bloodfiend "
    private const val CANDIDATE_WINDOW_MS = 10_000L

    private data class SpawnCandidate(val id: Int, val at: Long)
    private val pending = ArrayDeque<SpawnCandidate>()
    private val verified = ArrayDeque<SpawnCandidate>()

    private var awaitingSpawnUntil = 0L

    var boss: RemotePlayer? = null
    var timer: ArmorStand? = null
    var lootshareBoss: RemotePlayer? = null

    init {
        on<TickEvent.Server> { tick() }
        on<LevelEvent.Load> { reset() }

        onReceive<ClientboundAddEntityPacket> { // new slayer armorstand shenanigans
            if (LocationUtils.currentArea != Island.Rift || type != EntityType.PLAYER) return@onReceive
            pending.addLast(SpawnCandidate(id, System.currentTimeMillis()))
        }

        on<MessageEvent.Chat> {
            if (LocationUtils.currentArea != Island.Rift) return@on

            if (message.matches(SPAWN_REGEX)) {
                if (boss?.isAlive == true) return@on // someone else's spawn, ours is already bound
                awaitingSpawnUntil = System.currentTimeMillis() + CANDIDATE_WINDOW_MS
                tryBind()
            }
        }

        // only support rift for now
        AttackEntityCallback.EVENT.register { _, level, _, entity, _ ->
            onAttack(level, entity)
            InteractionResult.PASS
        }
    }

    fun init() {
        println("SlayerUtils initialised")
    }

    private fun reset() {
        boss = null
        lootshareBoss = null
        timer = null
        pending.clear()
        verified.clear()
        awaitingSpawnUntil = 0L
    }

    private fun tick() {
        lootshareBoss?.let {
            if (!it.isAlive) lootshareBoss = null
        }

        verifyPending()
        retrySpawnBind()

        val boss = this.boss

        // fetch timer stand
        if (boss != null && timer == null) {
            val world = Minecraft.getInstance().level
            val timerId = boss.id + 2

            if (world != null) {
                // +1=Bloodfiend I 625/625❤ | +2=03:59 SPECTRAL 0.9s | +3=SLAYER BOSS
                // val offset1 = world.getEntity(boss.id + 1)?.plainTextName ?: "null"
                // val offset2 = world.getEntity(boss.id + 2)?.plainTextName ?: "null"
                // val offset3 = world.getEntity(boss.id + 3)?.plainTextName ?: "null"
                // modMessage("+1=${offset1} | +2=${offset2} | +3=${offset3}")

                val maybe = world.getEntity(timerId)
                if (maybe is ArmorStand && maybe.plainTextName.contains(":")) timer = maybe
            }
        }

        // boss death check
        boss?.let {
            if (!it.isAlive) {
                this.boss = null
                this.timer = null
            }
        }
    }

    private fun verifyPending() {
        if (LocationUtils.currentArea != Island.Rift) return
        val level = Minecraft.getInstance().level ?: return
        
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
        val now = System.currentTimeMillis()
        if (awaitingSpawnUntil == 0L) return
        if (now >= awaitingSpawnUntil) {
            awaitingSpawnUntil = 0L
            return
        }
        tryBind()
    }

    private fun tryBind() {
        val level = Minecraft.getInstance().level ?: return
        verifyPending()
        pruneExpired()

        // newest packet wins
        for (i in verified.indices.reversed()) {
            val e = level.getEntity(verified[i].id)
            if (e is RemotePlayer && e.plainTextName == BOSS_NAME) {
                boss = e
                pending.clear()
                verified.clear()
                awaitingSpawnUntil = 0L
                SlayerEvent.Spawn(e).postAndCatch()
                return
            }
        }
    }

    private fun onAttack(level: Level, entity: Entity) {
        if (LocationUtils.currentArea != Island.Rift || lootshareBoss != null) return // 1 boss max, maybe figure out a better system later
        if (entity !is RemotePlayer || entity.plainTextName != BOSS_NAME || entity === boss) return

        val s = level.getEntity(entity.id + 1) ?: return
        if (s is ArmorStand && s.plainTextName.contains("Bloodfiend ")) {
            lootshareBoss = entity
        }
    }
}
