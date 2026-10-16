package dev.cd.adrift.utils

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier

/**
 * Full Riftstalker Bloodfiend loot table (see Hypixel SkyBlock Wiki).
 *
 * Roll structure per kill:
 * - Token (Coven Seal + Motes): always.
 * - One Main roll (weighted, may roll empty): Quantum / Bubba / Fang-tastic /
 *   Guardian / McGrubber / Unfanged / The One.
 * - One Extra roll (weighted, may roll empty): Soultwist Rune only.
 * - Separate RNGesus roll: Sangria Dye.
 *
 * Consequences: two drops at once are possible (Main + Extra, or anything +
 * Sangria), but two Main drops (e.g. The One bundle + Unfanged part) cannot
 * happen on a single kill. [resolveWinningDrops] enforces that.
 *
 * Reel icons are wiki item sprites shipped under
 * `assets/adrift/textures/gui/boss_slots/`. Both bundles share one sprite;
 * they are told apart by their rarity bars (Quantum is UNCOMMON green,
 * The One is LEGENDARY gold).
 *
 * Item rarities verified against the wiki (api.php extracts, Oct 2026):
 * Quantum bundle UNCOMMON, The One bundle LEGENDARY, Burger EPIC, Unfanged
 * LEGENDARY, Guardian MYTHIC, Bubba/Fang-tastic LEGENDARY, Soultwist EPIC,
 * Sangria LEGENDARY, Coven Seal UNCOMMON.
 * Bar hexes are the standard Hypixel rarity colors
 * (https://hypixelskyblock.minecraft.wiki/w/Rarity#Normal_Rarities):
 * UNCOMMON #55FF55, EPIC #AA00AA, LEGENDARY #FFAA00, MYTHIC #FF55FF.
 * One exception: Sangria's bar is sangria-red #D40808 instead of LEGENDARY
 * gold, matching the dye's color (its text stays gold for its rarity).
 */
enum class VampireDrop(
    val displayName: String,
    val chatKeys: List<String>,
    val texture: String,
    val color: ChatFormatting,
    val rarityColor: Int,
    val weight: Double,
    /**
     * Chance (in percent, T5 boss) that a kill produces this drop. Used for
     * strip filler variety so the tape looks like real outcomes: mostly
     * seals, occasional green/purple, rarely gold, almost never sangria.
     * [weight] stays as the raw loot-table weight for actual roll logic.
     * Source: https://hypixelskyblock.minecraft.wiki/w/Riftstalker_Bloodfiend#Drops
     */
    val fillerWeight: Double,
    val pool: Pool,
    val rank: Int,
) {
    SANGRIA(
        "Sangria Dye",
        listOf("sangria"),
        "sangria_dye.png",
        ChatFormatting.GOLD,
        0xFFD40808.toInt(),
        1.0,
        0.02,
        Pool.SPECIAL,
        0,
    ),
    THE_ONE(
        "The One IV Bundle",
        listOf("the one"),
        "bundle.png",
        ChatFormatting.GOLD,
        0xFFFFAA00.toInt(),
        3.0,
        1.8,
        Pool.MAIN,
        1,
    ),
    BURGER(
        "McGrubber's Burger",
        listOf("mcgrubber"),
        "burger.png",
        ChatFormatting.DARK_PURPLE,
        0xFFAA00AA.toInt(),
        2.0,
        1.2,
        Pool.MAIN,
        2,
    ),
    UNFANGED(
        "Unfanged Vampire Part",
        listOf("unfanged"),
        "unfanged.png",
        ChatFormatting.GOLD,
        0xFFFFAA00.toInt(),
        2.0,
        1.2,
        Pool.MAIN,
        3,
    ),
    GUARDIAN(
        "Guardian Lucky Block",
        listOf("guardian"),
        "guardian_lucky_block.png",
        ChatFormatting.LIGHT_PURPLE,
        0xFFFF55FF.toInt(),
        10.0,
        6.0,
        Pool.MAIN,
        4,
    ),
    BUBBA(
        "Bubba Blister",
        listOf("bubba"),
        "bubba_blister.png",
        ChatFormatting.GOLD,
        0xFFFFAA00.toInt(),
        15.0,
        9.0,
        Pool.MAIN,
        5,
    ),
    FANGTASTIC(
        "Fang-tastic Chocolate Chip",
        listOf("fang-tastic", "fangtastic", "chocolate chip"),
        "fangtastic.png",
        ChatFormatting.GOLD,
        0xFFFFAA00.toInt(),
        15.0,
        9.0,
        Pool.MAIN,
        6,
    ),
    QUANTUM(
        "Quantum III Bundle",
        listOf("quantum"),
        "bundle.png",
        ChatFormatting.GREEN,
        0xFF55FF55.toInt(),
        20.0,
        12.0,
        Pool.MAIN,
        7,
    ),
    SOULTWIST(
        "Soultwist Rune I",
        listOf("soultwist"),
        "soultwist_rune.png",
        ChatFormatting.DARK_PURPLE,
        0xFFAA00AA.toInt(),
        20.0,
        10.7,
        Pool.EXTRA,
        8,
    ),
    COVEN_SEAL(
        "Coven Seal",
        listOf("coven seal"),
        "coven_seal.png",
        ChatFormatting.GREEN,
        0xFF55FF55.toInt(),
        30.0,
        49.0,
        Pool.TOKEN,
        9,
    ),
    ;

    enum class Pool {
        MAIN,
        EXTRA,
        TOKEN,
        SPECIAL,
    }

    val textureId: Identifier
        get() = Identifier.fromNamespaceAndPath("adrift", "textures/gui/boss_slots/$texture")

    companion object {
        private val THE_ONE_COLOR: TextColor? = TextColor.fromLegacyFormat(ChatFormatting.GOLD)
        private val QUANTUM_COLOR: TextColor? = TextColor.fromLegacyFormat(ChatFormatting.GREEN)

        /**
         * Matches one chat line (e.g. "VERY RARE DROP! (McGrubber's Burger) ...")
         * to a drop, or null when the line is not a boss drop.
         *
         * Both bundle drops show as "Enchanted Book Bundle" in chat; the
         * specific enchant ("The One" vs "Quantum") disambiguates. When neither
         * is present the line is assumed to be Quantum (far more common).
         *
         * NOTE: bare bundle text cannot distinguish The One (gold) from Quantum
         * (green) — use [fromChatMessage] with the chat [Component] so color
         * disambiguates, same approach as SlayerDrops rename.
         */
        fun fromChatLine(line: String): VampireDrop? {
            val lower = line.lowercase()
            if (!lower.contains("rare drop!")) return null
            val match = entries.firstOrNull { drop ->
                drop.chatKeys.any { lower.contains(it) }
            }
            if (match != null) return match
            if (lower.contains("enchanted book bundle")) return QUANTUM
            return null
        }

        /**
         * Parses a full chat packet (plain [message] + rich [component]) into
         * drops. Same plain-text keys as [fromChatLine], except bare
         * "Enchanted Book Bundle" lines are disambiguated via component color
         * (gold -> THE_ONE, green -> QUANTUM), mirroring SlayerDrops'
         * `ValuableDrop.BUNDLE.matches`.
         */
        fun fromChatMessage(message: String, component: Component): List<VampireDrop> {
            if (!message.lowercase().contains("rare drop!")) return emptyList()
            return message.split("\n").mapNotNull { line ->
                val lower = line.lowercase()
                if (!lower.contains("rare drop!")) return@mapNotNull null
                val match = entries.firstOrNull { drop ->
                    drop.chatKeys.any { lower.contains(it) }
                }
                if (match != null) return@mapNotNull match
                if (lower.contains("enchanted book bundle")) {
                    return@mapNotNull bundleDropFromComponent(component)
                }
                null
            }
        }

        /**
         * Disambiguates the shared "Enchanted Book Bundle" sprite/name via chat
         * color: The One renders gold (LEGENDARY), Quantum renders green
         * (UNCOMMON). Falls back to QUANTUM when no colored piece is found to
         * preserve the old far-more-common assumption.
         */
        fun bundleDropFromComponent(component: Component): VampireDrop {
            val bundlePieces = component.toFlatList(Style.EMPTY)
                .filter { it.string.contains("Enchanted Book Bundle") }
            if (bundlePieces.any { it.style.color == THE_ONE_COLOR }) return THE_ONE
            if (bundlePieces.any { it.style.color == QUANTUM_COLOR }) return QUANTUM
            return QUANTUM
        }

        /**
         * True for RNG meter reselection lines, e.g.
         * "RNG METER! Reselected the Unfanged Vampire Part for Vampire Slayer!
         * CLICK HERE to select a new drop!".
         *
         * These race ahead of death detection (tick-based), so callers must
         * check before the normal collecting/spinning guard or they always
         * leak past the hider.
         */
        fun isRngMeterMessage(message: String): Boolean {
            val lower = message.lowercase()
            return lower.contains("rng meter") &&
                lower.contains("reselect") &&
                lower.contains("vampire slayer")
        }

        /**
         * Parses RNG meter reselection lines into drops. Same display-name /
         * chat-key matching as rare drops, plus bundle color disambiguation.
         * Returns empty when the packet contains no RNG meter line.
         */
        fun fromRngMeterMessage(message: String, component: Component): List<VampireDrop> {
            return message.split("\n").mapNotNull { line ->
                if (!isRngMeterMessage(line)) return@mapNotNull null
                val lower = line.lowercase()
                entries.firstOrNull { lower.contains(it.displayName.lowercase()) }
                    ?: entries.firstOrNull { drop ->
                        drop.chatKeys.any { lower.contains(it) }
                    }
                    ?: if (lower.contains("enchanted book bundle")) bundleDropFromComponent(component) else null
            }
        }

        /**
         * Reduces raw parsed chat hits to the actual winning drops, enforcing
         * the loot-table constraint that two Main drops cannot co-exist. The
         * rarest drop comes first. Empty input falls back to Coven Seal (the
         * guaranteed token when nothing rare drops).
         */
        fun resolveWinningDrops(parsed: List<VampireDrop>): List<VampireDrop> {
            if (parsed.isEmpty()) return listOf(COVEN_SEAL)
            val distinct = parsed.distinct().sortedBy { it.rank }
            val bestMain = distinct.firstOrNull { it.pool == Pool.MAIN }
            val others = distinct.filter {
                it.pool == Pool.EXTRA || it.pool == Pool.SPECIAL
            }
            val result = buildList {
                if (bestMain != null) add(bestMain)
                addAll(others)
            }
            if (result.isEmpty()) return listOf(COVEN_SEAL)
            return result.sortedBy { it.rank }.take(3)
        }
    }
}
