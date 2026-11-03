package dev.cd.adrift.features.tracking

import dev.cd.adrift.events.ChatEvent
import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module
import dev.cd.adrift.utils.Location
import dev.cd.adrift.utils.modMessage
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

object SlayerDrops : Module(
    "Drops",
    Category.TRACKING,
    "Copies valuable slayer drops to the clipboard and renames the bundle drop.",
    toggle = "Drops",
) {
    private val copyDrops by bool("Copy Drops", false, "Copies valuable drop messages to the clipboard.")
    private val bundleRename by bool("Rename Bundle", false, "Renames the bundle drop to 'The One IV Bundle'.")

    private val anyOn get() = copyDrops || bundleRename

    override val enabled get() = super.enabled && anyOn

    init {
        upgradedFrom { anyOn }
    }

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
        val literal: String get() = "$formatting$plain"

        fun matches(piece: Component): Boolean {
            val text = piece.string
            if (!text.contains(plain)) return false
            if (text.contains(literal)) return true
            return piece.style.color == color
        }

        fun rename(text: String): String {
            val replacement = renameTo ?: return text
            return text.replace(plain, replacement).replace(literal, "$formatting$replacement")
        }
    }

    init {
        on<ChatEvent> {
            if (!Location.inRift) return@on

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
                //? if >=26.2 {
                mc.schedule { mc.gui.hud.chat.addClientSystemMessage(rebuilt) }
                //?} else
                //mc.schedule { mc.gui.chat.addClientSystemMessage(rebuilt) }
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
