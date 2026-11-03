package dev.cd.adrift.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.cd.adrift.Adrift
import dev.cd.adrift.utils.Color
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt
import kotlin.reflect.KProperty

/**
 * Options saved to config/adrift.json. Options live in a [Section] of a [Category]: the config screen shows
 * categories as tabs and sections as headed lists, all in declaration order. Options read like properties
 * (`val x by section.bool(...)`) and save themselves when changed.
 *
 * An option is shown while its [Option.dependsOn] switch (and anything that depends on in turn) is on and its
 * [Option.condition] holds, and is indented under that switch. HUD placements are [HudLayout]s saved under `"hud"`, personal bests are [records]
 * saved under `"records"`, the config screen's colours are the [Theme], saved under `"theme"`, and where the ClickGUI's
 * panels sit are [PanelLayout]s saved under `"clickGui"`.
 */
object Config {
    class Category internal constructor(val name: String) {
        val sections = mutableListOf<Section>()

        fun section(name: String, description: String = "") = Section(this, name, description).also(sections::add)
    }

    class Section internal constructor(val category: Category, val name: String, val description: String) {
        val options = mutableListOf<Option>()

        /** The switch the whole section hangs off, if it has one; options under it aren't indented. */
        var main: BoolOption? = null
            internal set

        private fun <O : Option> add(option: O): O = option.also { options += it; if (it.saved) registered += it }

        fun bool(key: String, label: String, description: String, default: Boolean, dependsOn: BoolOption? = null) =
            add(BoolOption(key, label, description, this, dependsOn, default))

        fun int(
            key: String, label: String, description: String, default: Int, min: Int, max: Int, step: Int = 1,
            suffix: String = "", dependsOn: BoolOption? = null,
        ) = add(IntOption(key, label, description, this, dependsOn, default, min, max, step, suffix))

        fun double(
            key: String, label: String, description: String, default: Double, min: Double, max: Double, step: Double,
            suffix: String = "", dependsOn: BoolOption? = null,
        ) = add(DoubleOption(key, label, description, this, dependsOn, default, min, max, step, suffix))

        fun <E : Enum<E>> choice(
            key: String, label: String, description: String, default: E, entries: List<E>,
            dependsOn: BoolOption? = null, name: (E) -> String = { it.toString() },
        ) = add(ChoiceOption(key, label, description, this, dependsOn, default, entries, name))

        fun color(key: String, label: String, description: String, default: Color, allowAlpha: Boolean, dependsOn: BoolOption? = null) =
            add(ColorOption(key, label, description, this, dependsOn, default, allowAlpha))

        /** Not saved; set [ButtonOption.onClick]. */
        fun button(key: String, label: String, description: String, text: String, dependsOn: BoolOption? = null, onClick: () -> Unit = {}) =
            add(ButtonOption(key, label, description, this, dependsOn, text, onClick))
    }

    sealed class Option(
        val key: String,
        val label: String,
        val description: String,
        val section: Section,
        val dependsOn: BoolOption?,
    ) {
        /** Extra condition for showing this, on top of [dependsOn]. */
        var condition: (() -> Boolean)? = null
        internal open val saved get() = true

        /** False while [dependsOn], or anything it depends on in turn, is off, or [condition] fails. */
        val shown: Boolean get() = (dependsOn?.let { it.shown && it() } ?: true) && (condition?.invoke() ?: true)

        /** How far the config screen indents this: a step per switch it hangs off, apart from [Section.main]. */
        val depth: Int get() = generateSequence(dependsOn) { it.dependsOn }.count { it !== section.main }

        private val listeners = mutableListOf<() -> Unit>()

        /** Called after the value changes. */
        fun onChange(listener: () -> Unit) = apply { listeners += listener }

        protected fun changed() {
            listeners.forEach { it() }
            save()
        }
    }

    /** An option holding a value of type [T]; reads like a property. */
    sealed class ValueOption<T>(
        key: String, label: String, description: String, section: Section, dependsOn: BoolOption?, val default: T,
    ) : Option(key, label, description, section, dependsOn) {
        internal var current = default

        /** Setting this saves the config. */
        var value: T
            get() = current
            set(new) {
                val normalized = normalize(new)
                if (current == normalized) return
                current = normalized
                changed()
            }

        protected open fun normalize(value: T) = value

        operator fun invoke(): T = current

        operator fun getValue(thisRef: Any?, property: KProperty<*>): T = current

        /** Only shown while [predicate] holds, like Odin's `withDependency`. */
        fun showIf(predicate: () -> Boolean) = apply { condition = predicate }

        fun reset() {
            value = default
        }
    }

    class BoolOption internal constructor(
        key: String, label: String, description: String, section: Section, dependsOn: BoolOption?, default: Boolean,
    ) : ValueOption<Boolean>(key, label, description, section, dependsOn, default)

    /** A whole number from [min] to [max] in [step]s, shown as a slider with [suffix] after the value. */
    class IntOption internal constructor(
        key: String, label: String, description: String, section: Section, dependsOn: BoolOption?, default: Int,
        val min: Int, val max: Int, val step: Int, val suffix: String,
    ) : ValueOption<Int>(key, label, description, section, dependsOn, default) {
        override fun normalize(value: Int) = (min + ((value - min).toDouble() / step).roundToInt() * step).coerceIn(min, max)
    }

    /** A number from [min] to [max] in [step]s, shown as a slider. */
    class DoubleOption internal constructor(
        key: String, label: String, description: String, section: Section, dependsOn: BoolOption?, default: Double,
        val min: Double, val max: Double, val step: Double, val suffix: String,
    ) : ValueOption<Double>(key, label, description, section, dependsOn, default) {
        /** Enough decimals to show one step. */
        val decimals = step.toBigDecimal().stripTrailingZeros().scale().coerceAtLeast(0)

        override fun normalize(value: Double): Double {
            val stepped = min + ((value - min) / step).roundToInt() * step
            return "%.${decimals}f".format(java.util.Locale.ROOT, stepped.coerceIn(min, max)).toDouble()
        }

        fun format(value: Double = current) = "%.${decimals}f".format(java.util.Locale.ROOT, value)
    }

    /** One of [entries], shown as a segmented control labelled by [name]. Saved by enum name. */
    class ChoiceOption<E : Enum<E>> internal constructor(
        key: String, label: String, description: String, section: Section, dependsOn: BoolOption?, default: E,
        val entries: List<E>, val name: (E) -> String,
    ) : ValueOption<E>(key, label, description, section, dependsOn, default) {
        internal fun load(saved: String) {
            entries.firstOrNull { it.name == saved }?.let { current = it }
        }
    }

    /** A colour, opaque unless [allowAlpha]. Saved as `RRGGBBAA`. */
    class ColorOption internal constructor(
        key: String, label: String, description: String, section: Section, dependsOn: BoolOption?, default: Color,
        val allowAlpha: Boolean,
    ) : ValueOption<Color>(key, label, description, section, dependsOn, default) {
        override fun normalize(value: Color) = if (allowAlpha) value else value.withAlpha(1f)
    }

    /** A row with a button labelled [text]. */
    class ButtonOption internal constructor(
        key: String, label: String, description: String, section: Section, dependsOn: BoolOption?, val text: String,
        var onClick: () -> Unit,
    ) : Option(key, label, description, section, dependsOn) {
        override val saved get() = false
    }

    /**
     * Colours of the config screen: an [accent] (`0xRRGGBB`) everything else is derived from, and how opaque the
     * menu panel is. Call [save] after changing them.
     */
    object Theme {
        const val DEFAULT_ACCENT = 0xB03FD9
        const val DEFAULT_OPACITY = 0.93f

        var accent = DEFAULT_ACCENT
            set(value) {
                field = value and 0xFFFFFF
            }
        var opacity = DEFAULT_OPACITY
            set(value) {
                field = value.coerceIn(0f, 1f)
            }
    }

    /**
     * Where a HUD element sits and how big it is. [x] and [y] are fractions of the space left over once the
     * element is placed (0 = left/top edge, 1 = right/bottom edge), so it stays on screen at any window size.
     */
    class HudLayout internal constructor(val key: String, val defaultX: Float, val defaultY: Float, val defaultScale: Float) {
        var x = defaultX
        var y = defaultY
        var scale = defaultScale
            set(value) {
                field = value.coerceIn(MIN_SCALE, MAX_SCALE)
            }

        fun reset() {
            x = defaultX
            y = defaultY
            scale = defaultScale
        }

        companion object {
            const val MIN_SCALE = 0.5f
            const val MAX_SCALE = 3f
        }
    }

    /**
     * Where a ClickGUI panel sits ([x] and [y] of its top-left corner, in the screen's own units; null until it's
     * moved) and whether it's folded up. Call [save] after changing it.
     */
    class PanelLayout internal constructor() {
        var x: Float? = null
        var y: Float? = null
        var collapsed = false
    }

    private val path = FabricLoader.getInstance().configDir.resolve("${Adrift.MOD_ID}.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val registered = mutableListOf<Option>()
    private val hudLayouts = mutableListOf<HudLayout>()
    val categories = mutableListOf<Category>()

    /** Named numbers kept between sessions, like personal bests. Call [save] after changing them. */
    val records = linkedMapOf<String, Float>()

    private val panels = linkedMapOf<String, PanelLayout>()

    /** The ClickGUI panel called [name]. */
    fun panel(name: String) = panels.getOrPut(name, ::PanelLayout)

    fun category(name: String) = categories.firstOrNull { it.name == name } ?: Category(name).also(categories::add)

    fun hudLayout(key: String, x: Float, y: Float, scale: Float = 1f) = HudLayout(key, x, y, scale).also(hudLayouts::add)

    @Volatile private var loaded = false

    fun load() {
        loaded = true
        if (!Files.isRegularFile(path)) return
        try {
            val json = JsonParser.parseString(Files.readString(path)).asJsonObject
            for (option in registered) {
                val element = json.get(option.key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: continue
                when (option) {
                    is BoolOption -> if (element.isBoolean) option.current = element.asBoolean
                    is IntOption -> if (element.isNumber) option.current = option.clampInt(element.asInt)
                    is DoubleOption -> if (element.isNumber) option.current = element.asDouble.coerceIn(option.min, option.max)
                    is ChoiceOption<*> -> if (element.isString) option.load(element.asString)
                    is ColorOption -> if (element.isString) Color.fromHexOrNull(element.asString)?.let { option.current = it }
                    is ButtonOption -> {}
                }
            }
            json.get("theme")?.takeIf { it.isJsonObject }?.asJsonObject?.let { theme ->
                theme.get("accent")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.removePrefix("#")?.toIntOrNull(16)?.let { Theme.accent = it }
                theme.get("opacity")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let { Theme.opacity = it.asFloat }
            }
            val hud = json.get("hud")?.takeIf { it.isJsonObject }?.asJsonObject
            for (layout in hudLayouts) {
                val saved = hud?.get(layout.key)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                fun number(key: String) = saved.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asFloat
                number("x")?.let { layout.x = it.coerceIn(0f, 1f) }
                number("y")?.let { layout.y = it.coerceIn(0f, 1f) }
                number("scale")?.let { layout.scale = it }
            }
            json.get("clickGui")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (name, value) ->
                val saved = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                fun number(key: String) = saved.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asFloat
                val panel = panel(name)
                panel.x = number("x")
                panel.y = number("y")
                panel.collapsed = saved.get("collapsed")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
            }
            json.get("records")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (key, value) ->
                if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) records[key] = value.asFloat
            }
        } catch (e: Exception) {
            Adrift.LOGGER.warn("Could not read {} config, using defaults", Adrift.MOD_ID, e)
        }
    }

    private fun IntOption.clampInt(value: Int) = value.coerceIn(min, max)

    @Volatile private var dirtySince = 0L

    /**
     * Options save themselves; call this after changing a [HudLayout], a [PanelLayout], [records] or the [Theme]. Writes are
     * batched: dragging a slider changes it every frame, so the file is written once things settle (see [tick]).
     */
    fun save() {
        // Options set while modules initialise mustn't overwrite the file before it's read
        if (!loaded) return
        if (dirtySince == 0L) dirtySince = System.currentTimeMillis()
    }

    /** Writes pending changes half a second after the first one, or right away with [force]. */
    fun tick(force: Boolean = false) {
        val since = dirtySince
        if (since == 0L || (!force && System.currentTimeMillis() - since < SAVE_DELAY_MS)) return
        dirtySince = 0L
        write()
    }

    private const val SAVE_DELAY_MS = 500L

    private fun write() {
        val json = JsonObject()
        for (option in registered) {
            when (option) {
                is BoolOption -> json.addProperty(option.key, option.current)
                is IntOption -> json.addProperty(option.key, option.current)
                is DoubleOption -> json.addProperty(option.key, option.current)
                is ChoiceOption<*> -> json.addProperty(option.key, option.current.name)
                is ColorOption -> json.addProperty(option.key, option.current.hex())
                is ButtonOption -> {}
            }
        }
        json.add("theme", JsonObject().apply {
            addProperty("accent", "#%06X".format(Theme.accent))
            addProperty("opacity", Theme.opacity)
        })
        json.add("hud", JsonObject().apply {
            for (layout in hudLayouts) {
                add(layout.key, JsonObject().apply {
                    addProperty("x", layout.x)
                    addProperty("y", layout.y)
                    addProperty("scale", layout.scale)
                })
            }
        })
        json.add("clickGui", JsonObject().apply {
            for ((name, panel) in panels) {
                add(name, JsonObject().apply {
                    panel.x?.let { addProperty("x", it) }
                    panel.y?.let { addProperty("y", it) }
                    addProperty("collapsed", panel.collapsed)
                })
            }
        })
        json.add("records", JsonObject().apply { records.forEach { (key, value) -> addProperty(key, value) } })
        val tmp = path.resolveSibling("${Adrift.MOD_ID}.json.tmp")
        try {
            Files.createDirectories(path.parent)
            Files.writeString(tmp, gson.toJson(json))
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            Adrift.LOGGER.warn("Could not save {} config", Adrift.MOD_ID, e)
        }
    }
}
