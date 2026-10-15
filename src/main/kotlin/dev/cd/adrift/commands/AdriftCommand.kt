package dev.cd.adrift.commands

import com.github.stivais.commodore.Commodore
import com.github.stivais.commodore.utils.GreedyString
import com.odtheking.odin.utils.modMessage
import dev.cd.adrift.features.impl.rift.BossSlots

// Commands are handled via https://github.com/Stivais/Commodore
val adriftCommand = Commodore("adrift", "ad") {

    runs {
        modMessage("Adrift command executed")
    }

    "simulate" {
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

    runs { greedy: GreedyString ->
        modMessage("Command with parameter executed: ${greedy.string}")
    }
}
