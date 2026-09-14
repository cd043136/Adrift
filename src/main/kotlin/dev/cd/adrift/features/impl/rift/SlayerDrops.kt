package dev.cd.adrift.features.impl.rift

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import dev.cd.adrift.utils.Category
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

object SlayerDrops : Module(
    "Slayer Drops",
    category = Category.RIFT,
    description = "Copy and rename valuable slayer drops"
) {
    private val copyDrops by BooleanSetting(
        "Copy Drops",
        default = false,
        desc = "Copy valuable drops"
    )
    private val bundleRename by BooleanSetting(
        "Rename Bundle",
        default = false,
        desc = "Rename bundle drop to 'The One IV Bundle'"
    )

    private const val DROP = "RARE DROP!"

    private enum class ValuableDrop(
        val plain: String,
        val formatting: ChatFormatting,
        val renameTo: String? = null,
    ) {
        BUNDLE("Enchanted Book Bundle", ChatFormatting.GOLD, "The One IV Bundle"),
        UNFANGED("Unfanged", ChatFormatting.GOLD),
        BURGER("McGrubber", ChatFormatting.DARK_PURPLE);

        val color: TextColor? get() = TextColor.fromLegacyFormat(formatting)
        val literal: String get() = "§${formatting.char}$plain"

        fun matches(piece: Component): Boolean {
            val text = piece.string
            if (!text.contains(plain)) return false
            if (text.contains(literal)) return true
            return piece.style.color == color
        }

        fun rename(text: String): String {
            val replacement = renameTo ?: return text
            return text.replace(plain, replacement).replace(literal, "§${formatting.char}$replacement")
        }
    }

    init {
        on<MessageEvent.Chat> {
            if (LocationUtils.currentArea != Island.Rift) return@on
            if (!bundleRename && !copyDrops) return@on

            val pieces = component.toFlatList(Style.EMPTY)
            val matched = ValuableDrop.entries.filter { drop -> pieces.any(drop::matches) }
            if (matched.isEmpty()) return@on

            var displayRenamed = false
            if (bundleRename && ValuableDrop.BUNDLE in matched &&
                isSystemLine(message, ValuableDrop.BUNDLE.plain)
            ) {
                val rebuilt = Component.empty()
                pieces.forEach { piece ->
                    val out = if (ValuableDrop.BUNDLE.matches(piece)) ValuableDrop.BUNDLE.rename(piece.string) else piece.string
                    rebuilt.append(Component.literal(out).withStyle(piece.style))
                }
                cancel()
                mc.schedule { mc.gui.chat.addClientSystemMessage(rebuilt) }
                displayRenamed = true
            }

            if (copyDrops && isSystemLine(message, DROP)) {
                var text = message
                // Mirror chat: only use the renamed form if we actually renamed the display.
                if (ValuableDrop.BUNDLE in matched && displayRenamed)
                    text = text.replace(ValuableDrop.BUNDLE.plain, ValuableDrop.BUNDLE.renameTo!!)
                mc.keyboardHandler.clipboard = text
                modMessage("Copied to clipboard")
            }
        }
    }

    private fun isSystemLine(value: String, keyword: String): Boolean =
        value.lines().any { line ->
            line.contains(keyword) && !line.substringBefore(keyword).contains(":")
        }
}
