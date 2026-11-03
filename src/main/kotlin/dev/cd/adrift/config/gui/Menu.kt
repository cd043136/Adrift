package dev.cd.adrift.config.gui

import dev.cd.adrift.config.gui.clickgui.ClickGuiScreen
import dev.cd.adrift.config.gui.window.ConfigScreen
import dev.cd.adrift.features.Category
import dev.cd.adrift.features.Module

/**
 * The config menu's own settings: which screen `/adrift`, the Open Config key and the HUD editor's way back open.
 * Changing the style swaps the open menu for the other one.
 */
object Menu : Module("Menu", Category.GENERAL, "How the config menu looks.") {
    enum class Style(private val label: String) {
        CLICK_GUI("ClickGUI"), WINDOW("Window");

        override fun toString() = label
    }

    private val style = choice("Style", Style.WINDOW, "ClickGUI shows every tab as a panel you can drag around; Window shows one tab at a time.")

    init {
        // Only changed from inside the menu, so the new one replaces it; the window comes back on this tab
        style.onChange {
            when (style()) {
                Style.CLICK_GUI -> ClickGuiScreen()
                Style.WINDOW -> ConfigScreen(section.category)
            }.open()
        }
    }

    /** Opens the config in the chosen style. */
    fun open() = when (style()) {
        Style.CLICK_GUI -> ClickGuiScreen()
        Style.WINDOW -> ConfigScreen()
    }.open()
}
