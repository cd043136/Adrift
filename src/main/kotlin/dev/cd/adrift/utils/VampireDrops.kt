package dev.cd.adrift.utils

import net.minecraft.ChatFormatting
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
 * The One gets a gold border at render time to tell them apart.
 */
enum class VampireDrop(
    val displayName: String,
    val chatKeys: List<String>,
    val texture: String,
    val color: ChatFormatting,
    val weight: Double,
    val pool: Pool,
    val rank: Int,
) {
    SANGRIA(
        "Sangria Dye",
        listOf("sangria"),
        "sangria_dye.png",
        ChatFormatting.GOLD,
        1.0,
        Pool.SPECIAL,
        0,
    ),
    THE_ONE(
        "The One IV Bundle",
        listOf("the one"),
        "bundle.png",
        ChatFormatting.GOLD,
        3.0,
        Pool.MAIN,
        1,
    ),
    BURGER(
        "McGrubber's Burger",
        listOf("mcgrubber"),
        "burger.png",
        ChatFormatting.DARK_PURPLE,
        2.0,
        Pool.MAIN,
        2,
    ),
    UNFANGED(
        "Unfanged Vampire Part",
        listOf("unfanged"),
        "unfanged.png",
        ChatFormatting.GOLD,
        2.0,
        Pool.MAIN,
        3,
    ),
    GUARDIAN(
        "Guardian Lucky Block",
        listOf("guardian"),
        "guardian_lucky_block.png",
        ChatFormatting.LIGHT_PURPLE,
        10.0,
        Pool.MAIN,
        4,
    ),
    BUBBA(
        "Bubba Blister",
        listOf("bubba"),
        "bubba_blister.png",
        ChatFormatting.GOLD,
        15.0,
        Pool.MAIN,
        5,
    ),
    FANGTASTIC(
        "Fang-tastic Chocolate Chip",
        listOf("fang-tastic", "fangtastic", "chocolate chip"),
        "fangtastic.png",
        ChatFormatting.GOLD,
        15.0,
        Pool.MAIN,
        6,
    ),
    QUANTUM(
        "Quantum III Bundle",
        listOf("quantum"),
        "bundle.png",
        ChatFormatting.LIGHT_PURPLE,
        20.0,
        Pool.MAIN,
        7,
    ),
    SOULTWIST(
        "Soultwist Rune I",
        listOf("soultwist"),
        "soultwist_rune.png",
        ChatFormatting.AQUA,
        20.0,
        Pool.EXTRA,
        8,
    ),
    COVEN_SEAL(
        "Coven Seal",
        listOf("coven seal"),
        "coven_seal.png",
        ChatFormatting.GREEN,
        30.0,
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
        /**
         * Matches one chat line (e.g. "VERY RARE DROP! (McGrubber's Burger) ...")
         * to a drop, or null when the line is not a boss drop.
         *
         * Both bundle drops show as "Enchanted Book Bundle" in chat; the
         * specific enchant ("The One" vs "Quantum") disambiguates. When neither
         * is present the line is assumed to be Quantum (far more common).
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
