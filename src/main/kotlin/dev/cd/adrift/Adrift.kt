package dev.cd.adrift

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.Command
import dev.cd.adrift.config.Config
import dev.cd.adrift.config.gui.ConfigScreen
import dev.cd.adrift.events.EventDispatcher
import dev.cd.adrift.features.Category
import dev.cd.adrift.features.boss.BossHighlight
import dev.cd.adrift.features.boss.BossHud
import dev.cd.adrift.features.boss.IchorLine
import dev.cd.adrift.features.boss.LowHealthWarning
import dev.cd.adrift.features.boss.ManiaHelper
import dev.cd.adrift.features.boss.Twinclaws
import dev.cd.adrift.features.party.BeaconSoul
import dev.cd.adrift.features.party.PartyAnnounce
import dev.cd.adrift.features.tracking.SlayerDrops
import dev.cd.adrift.features.tracking.Splits
import dev.cd.adrift.hud.HudEditorScreen
import dev.cd.adrift.utils.Location
import dev.cd.adrift.utils.Scheduler
import dev.cd.adrift.utils.SlayerUtils
import dev.cd.adrift.utils.modMessage
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Adrift : ClientModInitializer {
    const val MOD_ID = "adrift"
    val LOGGER: Logger = LoggerFactory.getLogger("Adrift")
    val VERSION: String = FabricLoader.getInstance().getModContainer(MOD_ID).map { it.metadata.version.friendlyString }.orElse("dev")

    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)

    private val keyCategory = KeyMapping.Category.register(id(MOD_ID))

    // Unbound by default so it doesn't clash with other mods' menus (Odin uses Right Shift); /adrift always works
    private val openConfigKey = KeyMappingHelper.registerKeyMapping(
        KeyMapping("key.$MOD_ID.open_config", InputConstants.UNKNOWN.value, keyCategory)
    )

    override fun onInitializeClient() {
        EventDispatcher.register()
        Scheduler.register()
        Location.register()
        SlayerUtils.register()

        // Tabs in order, then every module (each adds its own section), then the settings that aren't a feature
        Category.entries
        listOf(
            BossHighlight, IchorLine, Twinclaws, ManiaHelper, LowHealthWarning, BossHud,
            PartyAnnounce, BeaconSoul,
            Splits, SlayerDrops,
        )
        Config.category("General").section("Interface").button(
            "general.editHud", "HUD Layout", "Drag HUD elements around, and scroll over one to resize it.", "Edit"
        ) { HudEditorScreen(returnToConfig = true).open() }
        Config.load()

        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            while (openConfigKey.consumeClick()) ConfigScreen().open()
            Config.tick()
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register { _ -> Config.tick(force = true) }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal(MOD_ID)
                    .executes { _ ->
                        // open() defers to the next tick, so the closing chat screen doesn't replace ours
                        ConfigScreen().open()
                        Command.SINGLE_SUCCESS
                    }
                    .then(ClientCommands.literal("hud").executes { _ ->
                        HudEditorScreen().open()
                        Command.SINGLE_SUCCESS
                    })
                    // For testing outside Hypixel: makes every world count as the Rift until toggled back
                    .then(ClientCommands.literal("forcerift").executes { _ ->
                        Location.forceRift = !Location.forceRift
                        modMessage("Force Rift ${if (Location.forceRift) "§aon" else "§coff"}")
                        Command.SINGLE_SUCCESS
                    })
            )
        }
        LOGGER.info("Adrift {} initialised", VERSION)
    }
}
