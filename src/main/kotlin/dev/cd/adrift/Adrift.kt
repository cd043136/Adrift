package dev.cd.adrift

import com.odtheking.odin.config.ModuleConfig
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.features.ModuleManager
import dev.cd.adrift.commands.adriftCommand
import dev.cd.adrift.features.impl.rift.HealthWarning
import dev.cd.adrift.features.impl.rift.VampireHelper
import dev.cd.adrift.features.impl.skyblock.TestModule
import dev.cd.adrift.utils.SlayerUtils
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback

object Adrift : ClientModInitializer {

    override fun onInitializeClient() {
        println("Adrift initialized!")

        // Register commands by adding to the array
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            arrayOf(adriftCommand).forEach { commodore -> commodore.register(dispatcher) }
        }

        // Register objects to event bus by adding to the list
        listOf(this, SlayerUtils).forEach { EventBus.subscribe(it) }

        // Register modules by adding to the list
        ModuleManager.registerModules(ModuleConfig("Adrift.json"), TestModule, VampireHelper, HealthWarning)
    }
}
