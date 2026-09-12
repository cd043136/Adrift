package dev.cd.adrift.commands

import com.github.stivais.commodore.Commodore
import com.github.stivais.commodore.utils.GreedyString
import com.odtheking.odin.utils.modMessage

// Commands are handled via https://github.com/Stivais/Commodore
val adriftCommand = Commodore("adrift", "ad") {

    runs {
        modMessage("Adrift command executed")
    }

    runs { greedy: GreedyString ->
        modMessage("Command with parameter executed: ${greedy.string}")
    }
}