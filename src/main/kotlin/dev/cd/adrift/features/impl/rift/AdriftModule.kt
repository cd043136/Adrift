package dev.cd.adrift.features.impl.rift

import com.google.gson.JsonParser
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.modMessage
import dev.cd.adrift.utils.Category
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object AdriftModule : Module(
    "======== Adrift ========",
    category = Category.RIFT,
    description = "General Adrift settings",
    toggled = true
) {
    private val checkUpdates by BooleanSetting(
        "Check for updates",
        default = true,
        desc = "Every 20 mins"
    )

    private const val PROJECT = "adrift-sb"
    private const val CHECK_INTERVAL_MS = 20 * 60 * 1000L

    private val currentVersion = modVersion("adrift")
    private val gameVersion = modVersion("minecraft")

    private val LOGGER = LoggerFactory.getLogger("Adrift")
    private val http =HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    private var lastCheck = 0L // 0 = not checked since launch
    @Volatile private var checking = false
    @Volatile private var announced: String? = null // only mention each new version once per launch

    init {
        on<TickEvent.End> {
            if (!checkUpdates || checking || !onHypixel()) return@on
            val now = System.currentTimeMillis()
            if (lastCheck != 0L && now - lastCheck < CHECK_INTERVAL_MS) return@on
            lastCheck = now
            check()
        }

        if (enabled) EventBus.subscribe(this)
    }

    private fun onHypixel() = mc.player != null && mc.currentServer?.ip?.contains("hypixel", ignoreCase = true) == true

    private fun check() {
        checking = true
        val loaders = URLEncoder.encode("[\"fabric\"]", Charsets.UTF_8)
        val request = HttpRequest.newBuilder(URI("https://api.modrinth.com/v2/project/$PROJECT/version?loaders=$loaders"))
            .header("User-Agent", "Adrift/$currentVersion (modrinth.com/mod/$PROJECT)")
            .timeout(Duration.ofSeconds(10))
            .build()

        LOGGER.info("Checking Modrinth for updates (adrift {}, minecraft {})", currentVersion, gameVersion)
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept { response ->
                if (response.statusCode() != 200) {
                    LOGGER.warn("Update check got HTTP {}", response.statusCode())
                    return@thenAccept
                }
                
                val latest = latestFor(response.body())
                LOGGER.info("Update check: latest for {} is {}, running {}", gameVersion, latest, currentVersion)
                
                if (latest == null) return@thenAccept
                if (compareVersions(latest, currentVersion) > 0 && latest != announced) {
                    announced = latest
                    mc.execute {
                        try { notify(latest) } catch (e: Exception) { LOGGER.warn("Update notice failed", e) }
                    }
                }
            }
            .exceptionally { e -> LOGGER.warn("Update check failed", e); null }
            .whenComplete { _, _ -> checking = false }
    }

    private fun latestFor(body: String): String? {
        val versions = JsonParser.parseString(body).asJsonArray.map { it.asJsonObject }
        val exact = versions.firstOrNull { v -> v.getAsJsonArray("game_versions").any { it.asString == gameVersion } }
        val family = gameVersion.split('.').take(2).joinToString(".")
        val match = exact ?: versions.firstOrNull { v ->
            v.getAsJsonArray("game_versions").any { it.asString == family || it.asString.startsWith("$family.") }
        }
        return match?.get("version_number")?.asString
    }

    private fun notify(latest: String) {
        val url = "https://modrinth.com/mod/$PROJECT/versions"
        val link = Component.literal("§a§n[Open Modrinth]").withStyle {
            it.withClickEvent(ClickEvent.OpenUrl(URI(url)))
                .withHoverEvent(HoverEvent.ShowText(Component.literal("§7$url")))
        }

        val rule = Component.literal(" ".repeat(64)).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.STRIKETHROUGH)
        val message = Component.literal(" \n")
            .append(rule).append("\n \n")
            .append("§3Adrift §8»§r §7Update available: §c$currentVersion §7→ §a$latest ").append(link)
            .append("\n \n").append(rule).append("\n ")
        modMessage(message, "")
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = Regex("\\d+").findAll(a).map { it.value.toInt() }.toList()
        val pb = Regex("\\d+").findAll(b).map { it.value.toInt() }.toList()
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    private fun modVersion(id: String): String =
        FabricLoader.getInstance().getModContainer(id).map { it.metadata.version.friendlyString }.orElse("0")
}
