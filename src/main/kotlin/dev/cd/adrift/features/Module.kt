package dev.cd.adrift.features

import dev.cd.adrift.config.Config
import dev.cd.adrift.events.Event
import dev.cd.adrift.events.EventBus
import dev.cd.adrift.events.PacketEvent
import dev.cd.adrift.hud.HudCanvas
import dev.cd.adrift.hud.HudManager
import dev.cd.adrift.hud.TextHud
import dev.cd.adrift.utils.Color
import net.minecraft.client.Minecraft

/** Config tabs, in the order they're shown. */
enum class Category(val title: String) {
    BOSS("Boss"),
    PARTY("Party"),
    TRACKING("Tracking"),
    GENERAL("General");

    val config: Config.Category = Config.category(title)
}

/**
 * A feature with its own config section called [name]. With a [toggle], the section opens with a switch of that
 * name and its options fold away while it's off. Every feature has one, so it can be switched off as a whole; only
 * settings that are always in effect (like [dev.cd.adrift.config.gui.Menu]) go without. Option keys are
 * `<category>.<section>.<option>` in camel case, so renaming a label resets it.
 */
abstract class Module(
    val name: String, category: Category, val description: String, toggle: String? = null, enabledByDefault: Boolean = false,
) {
    protected val mc: Minecraft get() = Minecraft.getInstance()

    val key = "${camel(category.title)}.${camel(name)}"
    val section = category.config.section(name, description)

    /** The section's main switch, from [toggle]; options hang off it unless given another parent. */
    protected val main: Config.BoolOption? = toggle?.let { label ->
        section.bool(key(label), label, description, enabledByDefault)
            .also { option -> option.onChange { if (option()) onEnable() else onDisable() } }
            .also { section.main = it }
    }

    /** Whether the module runs at all; without a [toggle], override this. */
    open val enabled: Boolean get() = main?.invoke() ?: true

    /** Called when the [toggle] switch changes. */
    open fun onEnable() {}

    open fun onDisable() {}

    private fun key(label: String) = "$key.${camel(label)}"

    protected fun bool(label: String, default: Boolean, desc: String, parent: Config.BoolOption? = main) =
        section.bool(key(label), label, desc, default, parent)

    protected fun int(label: String, default: Int, range: IntRange, step: Int = 1, desc: String, unit: String = "", parent: Config.BoolOption? = main) =
        section.int(key(label), label, desc, default, range.first, range.last, step, unit, parent)

    protected fun double(
        label: String, default: Double, range: ClosedFloatingPointRange<Double>, step: Double, desc: String, unit: String = "",
        parent: Config.BoolOption? = main,
    ) = section.double(key(label), label, desc, default, range.start, range.endInclusive, step, unit, parent)

    protected inline fun <reified E : Enum<E>> choice(label: String, default: E, desc: String, noinline name: (E) -> String = { it.toString() }) =
        choice(label, default, enumValues<E>().toList(), desc, name)

    protected fun <E : Enum<E>> choice(label: String, default: E, entries: List<E>, desc: String, name: (E) -> String) =
        section.choice(key(label), label, desc, default, entries, main, name)

    protected fun color(label: String, default: Color, desc: String, allowAlpha: Boolean = true, parent: Config.BoolOption? = main) =
        section.color(key(label), label, desc, default, allowAlpha, parent)

    protected fun button(label: String, text: String, desc: String, onClick: () -> Unit) =
        section.button(key(label), label, desc, text, main, onClick)

    /**
     * A movable HUD element with a switch called [label] to show it, or with a null [label] shown whenever the module
     * is on, called [title] in the HUD editor. Its placement is saved under [layout]. [content] draws into the canvas
     * and returns its size, (0, 0) to hide; with `preview` it draws example content for the HUD editor.
     */
    protected fun hud(
        label: String?, desc: String, x: Float, y: Float, default: Boolean = true, title: String = name, scale: Float = 1f,
        parent: Config.BoolOption? = main, layout: String = label ?: name,
        content: HudCanvas.(preview: Boolean) -> Pair<Int, Int>,
    ): Config.BoolOption {
        val shown = label?.let { bool(it, default, desc, parent) } ?: checkNotNull(main) { "$name: a HUD without a switch needs a toggle" }
        HudManager.add(TextHud(title, Config.hudLayout(key(layout), x, y, scale), { enabled && shown() }, content))
        return shown
    }

    /**
     * For a [toggle] added to a module that used to go by its parts alone: an existing config without it loads it
     * on when [anyOn] (read after loading) says one of them was.
     */
    protected fun upgradedFrom(anyOn: () -> Boolean) {
        checkNotNull(main) { "$name: upgradedFrom needs a toggle" }.fallback = anyOn
    }

    /** Only runs while the module is [enabled]. */
    protected inline fun <reified T : Event> on(noinline handler: T.() -> Unit) =
        EventBus.register(T::class.java) { if (enabled) it.handler() }

    /** Packets of type [P], only while the module is enabled. */
    protected inline fun <reified P> onReceive(noinline handler: P.(PacketEvent.Receive) -> Unit) =
        EventBus.register(PacketEvent.Receive::class.java) { event -> if (enabled) (event.packet as? P)?.handler(event) }

    private companion object {
        /** "Low Health Warning" -> "lowHealthWarning", "Mania #1" -> "mania1" */
        fun camel(label: String): String {
            val words = label.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            return words.mapIndexed { i, w -> if (i == 0) w.lowercase() else w.lowercase().replaceFirstChar(Char::uppercase) }.joinToString("")
        }
    }
}
