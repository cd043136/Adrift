package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.modMessage
import dev.cd.adrift.utils.Category
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import java.io.BufferedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * While enabled, appends every incoming sound packet to `config/adrift/sounds.log`:
 * its name, volume and pitch, the start timestamp (epoch ms, reset when the module
 * is enabled) and the ms offset from that timestamp.
 */
object LogSounds : Module(
    "Log Sounds",
    category = Category.RIFT,
    description = "Logs incoming sound packets (name, volume, pitch, timing) to config/adrift/sounds.log"
) {
    private const val LOG_FILE = "sounds.log"
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private var writer: BufferedWriter? = null
    private var writerFailed = false
    private var startMs = 0L
    private val lock = Any()

    init {
        onReceive<ClientboundSoundPacket> {
            if (!enabled) return@onReceive
            logSound(sound.value().location().toString(), volume, pitch)
        }

        onReceive<ClientboundSoundEntityPacket> {
            if (!enabled) return@onReceive
            logSound(sound.value().location().toString(), volume, pitch)
        }
    }

    override fun onEnable() {
        super.onEnable()
        close()
        // Logging start reference; entries are logged as offsets from this timestamp.
        synchronized(lock) {
            writerFailed = false
            startMs = System.currentTimeMillis()
            val timestamp = formatter.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(startMs), ZoneId.systemDefault()))
            writeLine("--- Log started at $timestamp (start=$startMs) ---")
        }
    }

    override fun onDisable() {
        super.onDisable()
        close()
    }

    private fun logSound(name: String, volume: Float, pitch: Float) {
        writeLine("$name volume=$volume pitch=$pitch start=$startMs offset=${System.currentTimeMillis() - startMs}ms")
    }

    // Packet events are posted on the netty thread; lock so writer access can't interleave with enable/disable.
    private fun writeLine(line: String) {
        synchronized(lock) {
            if (writerFailed) return
            val w = writer ?: openWriter() ?: return
            try {
                w.write(line)
                w.newLine()
                w.flush()
            } catch (e: IOException) {
                close()
                writerFailed = true
                modMessage("Sounds log write failed: ${e.message}")
            }
        }
    }

    private fun openWriter(): BufferedWriter? {
        if (writerFailed) return null
        return try {
            val path = FabricLoader.getInstance().configDir.resolve("adrift").resolve(LOG_FILE)
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                .also { writer = it }
        } catch (e: IOException) {
            writerFailed = true
            modMessage("Sounds log unavailable: ${e.message}")
            null
        }
    }

    private fun close() {
        synchronized(lock) {
            val w = writer ?: return
            writer = null
            try {
                w.close()
            } catch (ignored: IOException) {
            }
        }
    }
}
