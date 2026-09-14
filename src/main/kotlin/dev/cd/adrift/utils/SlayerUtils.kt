package dev.cd.adrift.utils

import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import dev.cd.adrift.events.SlayerEvent
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.player.RemotePlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.Level

object SlayerUtils {
    var boss: Entity? = null
    var timer: ArmorStand? = null
    var lootshareBoss: Entity? = null
    // TODO: new slayer system doesn't have `Spawned by:` thingy,
    //  maybe rely purely on attacks?
    init {
        on<TickEvent.Server> { tick() }
        on<LevelEvent.Load> { reset() }

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
    }

    private fun tick() {
        lootshareBoss?.let {
            if (!it.isAlive) lootshareBoss = null
        }

        // fetch boss
        if (boss == null) {
            val me = Minecraft.getInstance().player?.name?.string ?: return
            val world = Minecraft.getInstance().level ?: return

            world.entitiesForRendering().filterIsInstance<ArmorStand>().forEach {
                if (it.plainTextName == "Spawned by: $me") {
                    val timerS = world.getEntity(it.id - 1) ?: return@forEach
                    // val e2 = world.getEntity(it.id - 3)?.plainTextName ?: "NullName"
                    // val e1 = world.getEntity(it.id - 3)?.plainTextName ?: "NullName"

                    val b = world.getEntity(it.id - 3) ?: return@forEach
                    if (b !is ArmorStand && b.isAlive && timerS is ArmorStand && timerS.plainTextName.contains(":")) {
                        boss = b
                        timer = timerS
                        SlayerEvent.Spawn(b).postAndCatch()
                        return@tick
                    }
                }
            }
        }

        // boss death check
        boss?.let {
            if (!it.isAlive) {
                boss = null
                timer = null
            }
        }
    }

    private fun onAttack(level: Level, entity: Entity) {
        if (LocationUtils.currentArea != Island.Rift || lootshareBoss != null) return // 1 boss max, maybe figure out a better system later
        if (entity !is RemotePlayer || entity.plainTextName != "Bloodfiend " || entity == boss) return // hypixel,why is there a space???

        val s = level.getEntity(entity.id + 1) ?: return
        if (s is ArmorStand && s.plainTextName.contains("Bloodfiend ")) {
            val spawnedBy = level.getEntity(entity.id + 3)
                ?.plainTextName
                .takeIf {
                    it?.startsWith("Spawned by: ") ?: false
                }?.removePrefix("Spawned by: ") ?: return // should never happen but eh

            lootshareBoss = entity
            modMessage("Lootsharing $spawnedBy")
        }
    }
}
