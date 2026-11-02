package dev.cd.adrift.hud

import dev.cd.adrift.Adrift
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements

/** Every [HudModule]; adding one here registers it with the HUD and puts it in the [HudEditorScreen]. */
object HudManager {
    private val modules = mutableListOf<HudModule>()
    val all: List<HudModule> get() = modules

    fun add(module: HudModule) {
        modules += module
        // bossSplits.hud -> boss_splits_hud
        val path = module.layout.key.replace(Regex("([a-z])([A-Z])"), "$1_$2").replace('.', '_').lowercase()
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Adrift.id(path), module)
    }
}
