package dev.cd.adrift.config

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.cd.adrift.Adrift
import dev.cd.adrift.utils.Color
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * Carries settings over from the Odin addon version of Adrift (config/odin/addons/Adrift.json) the first time
 * the standalone mod runs. Modules that were split or merged map onto their new homes; HUD positions don't
 * carry over since Odin stored them in pixels.
 */
object OdinMigration {
    private val source = FabricLoader.getInstance().configDir.resolve("odin").resolve("addons").resolve("Adrift.json")

    /** Old module name to new key prefix, where the settings kept their names. */
    private val renamed = mapOf(
        "Low Health Warning" to "lowHealthWarning",
        "Slayer Drops" to "slayerDrops",
        "Mania Helper" to "maniaHelper",
        "Boss Highlight" to "bossHighlight",
        "Beacon Soul Helper" to "beaconSoulHelper",
        "Vampire Splits" to "splits",
    )

    /** Settings whose names changed, by old module then old setting. */
    private val moved = mapOf(
        "Mania Helper" to mapOf("Mania Status" to "maniaHelper.statusHud"),
        "Beacon Soul Helper" to mapOf("Delay ticks" to "beaconSoulHelper.delay"),
        "Vampire Splits" to mapOf("Vampire Splits" to "splits.showHud", "Send TTK" to "partyAnnounce.killTime"),
        "Vampire Helper" to mapOf(
            "Ichor Line Colour" to "ichorLine.lineColour",
            "On Other Boss" to "ichorLine.onOtherBoss",
            "Announce Spawn" to "partyAnnounce.spawn",
            "Twinclaws Alert" to "twinclaws.alert",
            "Delay" to "twinclaws.alertDelay",
            "Boss HUD" to "bossHud.showHud",
        ),
        "Announce Mania" to mapOf("Mania #1" to "partyAnnounce.mania1", "Mania #2" to "partyAnnounce.mania2", "Mania #3" to "partyAnnounce.mania3"),
        "Twinclaws Fix" to mapOf("Volume" to "twinclaws.volume"),
    )

    fun migrateIfNeeded(hadConfig: Boolean) {
        if (hadConfig || !Files.isRegularFile(source)) return
        try {
            val modules = JsonParser.parseString(Files.readString(source)).asJsonArray.map { it.asJsonObject }
            for (module in modules) migrate(module)
            Config.save()
            Adrift.LOGGER.info("Imported settings from the Odin addon version of Adrift")
        } catch (e: Exception) {
            Adrift.LOGGER.warn("Could not import settings from {}", source, e)
        }
    }

    private fun migrate(module: JsonObject) {
        val name = module.get("name")?.asString ?: return
        val enabled = module.get("enabled")?.asBoolean ?: false
        val settings = module.getAsJsonObject("settings") ?: JsonObject()
        val prefix = renamed[name]
        if (prefix != null) set("$prefix.enabled", enabled)

        // Turning on a feature's new module only if the old one had it on
        fun enableIf(key: String, on: Boolean) {
            if (on) set(key, true)
        }

        for ((setting, value) in settings.entrySet()) {
            if (setting == "Keybind") continue
            val target = moved[name]?.get(setting) ?: prefix?.let { "$it.${camel(setting)}" } ?: continue
            // Settings of a module that was off shouldn't switch features on in their new modules
            if (!enabled && value.isBoolean() && target.substringBefore('.') != prefix) continue
            set(target, value)
        }

        when (name) {
            "Vampire Helper" -> {
                enableIf("ichorLine.enabled", enabled && settings.bool("Ichor Line"))
                enableIf("partyAnnounce.enabled", enabled && settings.bool("Announce Spawn"))
                enableIf("twinclaws.enabled", enabled && settings.bool("Twinclaws Alert"))
                enableIf("bossHud.enabled", enabled && settings.getAsJsonObject("Boss HUD")?.get("enabled")?.asBoolean == true)
            }
            "Announce Mania" -> enableIf("partyAnnounce.enabled", enabled)
            "Twinclaws Fix" -> {
                enableIf("twinclaws.enabled", enabled)
                set("twinclaws.fixSound", enabled)
            }
            "Vampire Splits" -> {
                enableIf("partyAnnounce.enabled", enabled && settings.bool("Send TTK"))
                settings.getAsJsonObject("Vampire Splits PB")?.get("total")?.takeIf { it.isJsonPrimitive }?.let {
                    Config.records["splits.totalPb"] = it.asFloat
                }
            }
        }
    }

    private fun JsonObject.bool(key: String) = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true

    private fun JsonElement.isBoolean() = isJsonPrimitive && asJsonPrimitive.isBoolean
            || isJsonObject && asJsonObject.has("enabled")

    private fun set(key: String, value: Boolean) = set(key, com.google.gson.JsonPrimitive(value))

    /** Applies an Odin value to the option at [key], converting it to the option's type. */
    private fun set(key: String, value: JsonElement) {
        val option = Config.option(key) ?: return
        // HUD settings are objects whose "enabled" is the switch
        val v = if (value.isJsonObject) value.asJsonObject.get("enabled") ?: return else value
        if (!v.isJsonPrimitive) return
        val p = v.asJsonPrimitive
        when (option) {
            is Config.BoolOption -> if (p.isBoolean) option.value = p.asBoolean
            is Config.IntOption -> if (p.isNumber) option.value = p.asDouble.toInt()
            is Config.DoubleOption -> if (p.isNumber) option.value = p.asDouble
            is Config.ColorOption -> if (p.isString) Color.fromHexOrNull(p.asString)?.let { option.value = it }
            is Config.ChoiceOption<*> -> if (p.isString) option.select(p.asString)
            is Config.ButtonOption -> {}
        }
    }

    private fun camel(label: String): String {
        val words = label.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        return words.mapIndexed { i, w -> if (i == 0) w.lowercase() else w.lowercase().replaceFirstChar(Char::uppercase) }.joinToString("")
    }
}
