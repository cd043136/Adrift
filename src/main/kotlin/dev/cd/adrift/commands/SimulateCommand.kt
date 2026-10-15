package dev.cd.adrift.commands

import com.github.stivais.commodore.Commodore
import com.github.stivais.commodore.utils.GreedyString
import dev.cd.adrift.features.impl.rift.BossSlots

val simulateSuggestions = listOf(
    "random",
    "sangria",
    "the_one",
    "burger",
    "unfanged",
    "guardian",
    "bubba",
    "fangtastic",
    "quantum",
    "soultwist",
    "seal",
)

// Test hook for the boss-drop case opening:
// /simulate (random roll) or /simulate <drops...> (forced result, e.g. /simulate the_one)
val simulateCommand = Commodore("simulate") {
    runs {
        BossSlots.simulateRandom()
    }

    executable {
        param("drops") { suggests { simulateSuggestions } }
        runs { drops: GreedyString ->
            BossSlots.simulate(drops.string)
        }
    }
}
