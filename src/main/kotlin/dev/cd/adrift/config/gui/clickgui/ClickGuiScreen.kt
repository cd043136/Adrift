package dev.cd.adrift.config.gui.clickgui

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.cd.adrift.Adrift
import dev.cd.adrift.config.Config
import dev.cd.adrift.config.gui.ColorPicker
import dev.cd.adrift.config.gui.Montserrat
import dev.cd.adrift.config.gui.Palette
import dev.cd.adrift.config.gui.Palette.fade
import dev.cd.adrift.config.gui.SliderModel
import dev.cd.adrift.config.gui.checker
import dev.cd.adrift.config.gui.doubleSlider
import dev.cd.adrift.config.gui.intSlider
import dev.cd.adrift.config.gui.label
import dev.cd.adrift.config.gui.onLeftClick
import dev.cd.adrift.config.gui.setText
import dev.cd.adrift.config.gui.textInput
import dev.cd.adrift.config.gui.textTop
import dev.cd.adrift.config.gui.width
import dev.cd.adrift.hud.HudEditorScreen
import dev.cd.adrift.utils.Color
import foo.starred.cascade.animation.extension.impl.animate
import foo.starred.cascade.animation.interpolator.easing.impl.EaseOutEasingInterpolator
import foo.starred.cascade.constraints.impl.data.PositionAlignment
import foo.starred.cascade.constraints.impl.position.AlignPositionConstraint
import foo.starred.cascade.constraints.impl.position.CenterPositionConstraint
import foo.starred.cascade.constraints.impl.position.FixedPositionConstraint
import foo.starred.cascade.constraints.impl.size.FillSizeConstraint
import foo.starred.cascade.constraints.impl.size.FixedSizeConstraint
import foo.starred.cascade.effects.impl.OutlineEffect
import foo.starred.cascade.events.impl.FocusEvent
import foo.starred.cascade.events.impl.KeyEvent
import foo.starred.cascade.events.impl.MouseEvent
import foo.starred.cascade.graphics.geometry.CascadeGeometricColor
import foo.starred.cascade.graphics.geometry.CascadeGeometricOffset
import foo.starred.cascade.graphics.geometry.CascadeGeometricRadius
import foo.starred.cascade.primitives.base.impl.IPrimitiveElement
import foo.starred.cascade.primitives.impl.ImagePrimitive
import foo.starred.cascade.primitives.impl.ImagePrimitive.Companion.image
import foo.starred.cascade.primitives.impl.RoundedRectanglePrimitive
import foo.starred.cascade.primitives.impl.RoundedRectanglePrimitive.Companion.roundedRectangle
import foo.starred.cascade.primitives.impl.ScrollablePrimitive
import foo.starred.cascade.primitives.impl.ScrollablePrimitive.Companion.scrollable
import foo.starred.cascade.primitives.impl.TextPrimitive
import foo.starred.cascade.screen.CascadeScreen
import foo.starred.cascade.wrappers.svg.impl.CascadeSVG
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The config as a ClickGUI: each [Config] category is a panel, dragged by its header (right-clicking the header
 * folds it), and each section is a module in it. Left-clicking a module flips its main switch, or opens it when it
 * has none; right-clicking opens its options underneath, which can be changed while the switch is off. Scrolling
 * over a panel moves it up and down, and anywhere else moves them all. Hovering shows the description at the
 * bottom. A bar floating at the top holds the search field, Edit HUD and the theme bucket, which sets the same
 * colours as the window screen's. Panels are saved as [Config.PanelLayout]s.
 *
 * Sizes are in the same mockup pixels as the window screen; [init] picks how big to draw them.
 */
class ClickGuiScreen : CascadeScreen("Adrift") {
    private companion object {
        const val PANEL_W = 250f
        const val PANEL_GAP = 16f
        const val MARGIN = 24f
        const val HEADER_H = 46f
        const val MODULE_H = 40f
        const val OPTION_H = 32f
        const val SLIDER_ROW_H = 46f
        const val PAD = 16f
        const val RADIUS = 10f
        /**
         * How far past the panel the body clips on every side: Cascade rounds clip rectangles down to whole GUI
         * pixels, which cut the right edge off opaque rows.
         */
        const val CLIP_SLACK = 12f
        /** Left padding of options, so they sit under their module's name. */
        const val OPTION_PAD = 24f
        /** Extra left padding per [Config.Option.depth]. */
        const val INDENT = 10f
        const val SWITCH_W = 30f
        const val SWITCH_H = 16f
        const val KNOB = 12f
        const val THUMB = 11f
        const val SWATCH_W = 34f
        const val SWATCH_H = 18f
        const val PILL_H = 22f
        const val TOOLBAR_H = 38f
        const val BAR_PAD = 8f
        const val BAR_H = TOOLBAR_H + BAR_PAD * 2
        const val SEARCH_W = 260f
        const val GAP = 12f
        const val ICON = 22f
        const val MAX_QUERY = 28
        /** Where the bucket's water is in its 24 px viewBox, like the window screen's. */
        const val WATER_Y = 12.4f
        const val WATER_H = 8.6f
        const val SCROLL_STEP = 40f
        /** Never drawn bigger than this many GUI units per mockup pixel, the window screen's biggest. */
        const val MAX_SCALE = 0.415f
        const val SCRIM = 0x40050A08
        val FAST = 150.milliseconds
        val FOLD = 200.milliseconds
        val NONE = CascadeGeometricColor.TRANSPARENT

        /** Modules left open, so they're still open when the screen comes back. */
        val expanded = mutableSetOf<Config.Section>()

        fun color(argb: Int) = CascadeGeometricColor(argb)
    }

    private var initialized = false
    /** Set while something is dragged; gets the mouse position in scene coordinates. Cascade has no drag event. */
    private var drag: ((Float, Float) -> Unit)? = null
    /** Called again when the theme changes. */
    private val painters = mutableListOf<() -> Unit>()

    private val canvas: ScrollablePrimitive
    private val panels: List<Panel>
    private val toolbar: RoundedRectanglePrimitive
    private val description: TextPrimitive
    private val bucket = image { interact = false }
    private val bucketWater = image { interact = false }
    private val searchIcon = image { interact = false }
    private lateinit var searchField: RoundedRectanglePrimitive
    private lateinit var themeButton: RoundedRectanglePrimitive
    /** While not blank, panels only list the modules with an option matching it. */
    private var query = ""
    private val searching get() = query.isNotBlank()
    private var searchFocused = false
    private var described: Item? = null
    /** The open colour popup, if any. */
    private var picker: ColorPicker? = null
    private var pickerLayers = emptyList<IPrimitiveElement<*>>()
    /** Checkerboards behind colour swatches, re-rasterized when the scale changes. */
    private val swatchCheckers = mutableListOf<ImagePrimitive>()

    init {
        // Holds the panels so scrolling over empty space reaches it
        canvas = scrollable {
            position = FixedPositionConstraint(0f, 0f)
            size = FillSizeConstraint()
            color = NONE
            attach(scene)
        }
        canvas.listeners[MouseEvent.Scroll::class.java]?.clear()
        canvas.on<MouseEvent.Scroll> { scroll(x.toFloat(), y.toFloat(), amount.toFloat()); cancel() }
        panels = Config.categories.mapIndexed { index, category -> Panel(category, index) }

        description = label(" ", Montserrat.MEDIUM, 13f, Palette.DESCRIPTION).apply {
            position = AlignPositionConstraint(PositionAlignment.CENTER, PositionAlignment.END)
            offset = CascadeGeometricOffset(0f, -MARGIN)
            shadow = true
            attach(scene)
        }

        toolbar = roundedRectangle {
            position = AlignPositionConstraint(PositionAlignment.CENTER, PositionAlignment.START)
            offset = CascadeGeometricOffset(0f, MARGIN)
            radius = CascadeGeometricRadius(BAR_H / 2f)
            effect(OutlineEffect { color = color(Palette.LINE) })
            // Swallows clicks between the controls
            on<MouseEvent.Press> { cancel() }
            attach(scene)
        }
        painters += { toolbar.color = color(Palette.panel) }
        val titleFont = Montserrat.EXTRABOLD.tracked(0.16f)
        toolbar.adopt(label("ADRIFT", titleFont, 15f, Palette.TEXT).apply {
            position = AlignPositionConstraint(PositionAlignment.START, PositionAlignment.CENTER)
            offset = CascadeGeometricOffset(BAR_PAD + 14f, 0f)
        })
        var barX = BAR_PAD + 14f + titleFont.width("ADRIFT", 15f) + GAP
        toolbar.adopt(searchField(barX))
        barX += SEARCH_W + GAP
        val hud = hudButton(barX)
        toolbar.adopt(hud)
        barX += (hud.size as FixedSizeConstraint).width + GAP
        toolbar.adopt(themeButton(barX))
        barX += TOOLBAR_H + BAR_PAD
        toolbar.size = FixedSizeConstraint(barX, BAR_H)

        repaint()
    }

    override fun init() {
        // As big as the window screen, or smaller when the panels wouldn't fit side by side
        val needed = MARGIN * 2 + panels.size * PANEL_W + (panels.size - 1) * PANEL_GAP
        val scale = min(MAX_SCALE, width / needed)
        scene.scale = scale
        scene.width = width / scale
        scene.height = height / scale
        // Rasterized at the size they're drawn, so they stay sharp
        val icon = ceil(ICON * scale).toInt()
        bucket.location = CascadeSVG.load(Adrift.id("textures/gui/config/bucket.svg"), icon, icon)
        bucketWater.location = CascadeSVG.load(
            Adrift.id("textures/gui/config/bucket_water.svg"), icon, ceil(ICON * WATER_H / 24f * scale).toInt(),
        )
        searchIcon.location = CascadeSVG.load(Adrift.id("textures/gui/config/search.svg"), icon, icon)
        picker?.rasterize(scale)
        swatchCheckers.forEach { it.location = checker(SWATCH_W, SWATCH_H, 4f, scale) }
        initialized = true
        // Headers back on screen, in case the window shrank
        for (panel in panels) panel.moveTo(panel.x, max(0f, panel.y), save = false)
        scene.layout()
    }

    // ---- Panels ----

    /** Moves the panel under ([x], [y]) up or down, or every panel when there's none there. */
    private fun scroll(x: Float, y: Float, amount: Float) {
        val dy = sign(amount) * SCROLL_STEP
        val under = panels.lastOrNull { it.contains(x, y) }
        for (panel in under?.let(::listOf) ?: panels) panel.moveTo(panel.x, panel.y + dy)
    }

    private inner class Panel(val category: Config.Category, index: Int) {
        val layout = Config.panel(category.name)
        var x = layout.x ?: (MARGIN + index * (PANEL_W + PANEL_GAP))
        var y = layout.y ?: (MARGIN + BAR_H + PANEL_GAP)
        val items = mutableListOf<Item>()
        private var contentHeight = 0f
        val height get() = HEADER_H + contentHeight * fold
        /** 1 while open, 0 once folded up. */
        var fold = if (layout.collapsed) 0f else 1f
            set(value) {
                field = value
                relayout()
            }

        val root: RoundedRectanglePrimitive = roundedRectangle {
            // Not x and y: inside the builder those are the element's own
            position = FixedPositionConstraint(this@Panel.x, this@Panel.y)
            size = FixedSizeConstraint(PANEL_W, HEADER_H)
            radius = CascadeGeometricRadius(10f)
            effect(OutlineEffect { color = color(Palette.LINE) })
        }
        private val header = roundedRectangle {
            position = FixedPositionConstraint(0f, 0f)
            size = FixedSizeConstraint(PANEL_W, HEADER_H)
            radius = CascadeGeometricRadius(10f, 10f, 0f, 0f)
            cursor = CursorTypes.RESIZE_ALL
        }
        private val title = label(category.name.uppercase(), Montserrat.EXTRABOLD.tracked(0.14f), 14f, Palette.WHITE).apply {
            position = AlignPositionConstraint(PositionAlignment.START, PositionAlignment.CENTER)
            offset = CascadeGeometricOffset(PAD, 0f)
        }
        private val sign = label("", Montserrat.BOLD, 16f, 0).apply {
            position = AlignPositionConstraint(PositionAlignment.END, PositionAlignment.CENTER)
            offset = CascadeGeometricOffset(-PAD, 0f)
        }
        private val rule = roundedRectangle {
            position = FixedPositionConstraint(0f, HEADER_H - 2f)
            size = FixedSizeConstraint(PANEL_W, 2f)
            interact = false
        }
        /** Only clips the items while folding; the last one rounds its own corners. */
        private val body = scrollable {
            position = FixedPositionConstraint(-CLIP_SLACK, HEADER_H)
            size = FixedSizeConstraint(PANEL_W + CLIP_SLACK * 2, 0f)
            color = NONE
        }
        private val headerHover = Fade { paint() }

        init {
            header.adopt(title)
            header.adopt(sign)
            root.adopt(header)
            root.adopt(body)
            root.adopt(rule)
            canvas.adopt(root)

            body.listeners[MouseEvent.Scroll::class.java]?.clear()
            body.on<MouseEvent.Scroll> { scroll(x.toFloat(), y.toFloat(), amount.toFloat()); cancel() }
            header.on<MouseEvent.Move.Enter> { headerHover.to(1f, FAST) }
            header.on<MouseEvent.Move.Exit> { headerHover.to(0f, FAST) }
            header.on<MouseEvent.Press> {
                toFront()
                when (button) {
                    InputConstants.MOUSE_BUTTON_LEFT -> {
                        val grabX = x.toFloat() - this@Panel.x
                        val grabY = y.toFloat() - this@Panel.y
                        drag = { mx, my -> moveTo(mx - grabX, my - grabY) }
                    }
                    InputConstants.MOUSE_BUTTON_RIGHT -> {
                        layout.collapsed = !layout.collapsed
                        Config.save()
                        root.animate(this@Panel::fold, if (layout.collapsed) 0f else 1f, FOLD, easing = EaseOutEasingInterpolator)
                        clickSound()
                        paint()
                    }
                    else -> return@on
                }
                cancel()
            }
            // Pressing focused the header, so the release reaches it even off the header
            header.on<MouseEvent.Release> { drag = null }

            for (section in category.sections) {
                val module = ModuleItem(this, section)
                items += module
                for (option in section.options) {
                    if (option === section.main) continue
                    items += when (option) {
                        is Config.BoolOption -> ToggleItem(module, option)
                        is Config.IntOption -> SliderItem(module, option, intSlider(option))
                        is Config.DoubleOption -> SliderItem(module, option, doubleSlider(option))
                        is Config.ChoiceOption<*> -> ChoiceItem(module, option)
                        is Config.ColorOption -> ColorItem(module, option)
                        is Config.ButtonOption -> ButtonItem(module, option)
                    }.also { module.options += it }
                }
            }
            items.forEach { body.adopt(it.box) }
            for (item in items) {
                item.revealTarget = if (item.wanted) 1f else 0f
                item.reveal = item.revealTarget
            }
            painters += ::paint
        }

        fun contains(px: Float, py: Float) = px >= x && px < x + PANEL_W && py >= y && py < y + height

        private fun toFront() = canvas.adopt(root)

        /** Keeps the header on screen; [save] remembers it for next time. */
        fun moveTo(nx: Float, ny: Float, save: Boolean = true) {
            x = nx.coerceIn(0f, max(0f, scene.width - PANEL_W))
            y = ny.coerceIn(min(0f, HEADER_H - height), max(0f, scene.height - HEADER_H))
            root.position = FixedPositionConstraint(x, y)
            if (save) {
                layout.x = x
                layout.y = y
                Config.save()
            }
        }

        /** Stacks the items (folded ones as tall as they're shown) and sizes the panel around them. */
        fun relayout() {
            var top = 0f
            for (item in items) {
                item.height = item.fullHeight * item.reveal
                item.box.position = FixedPositionConstraint(CLIP_SLACK, top)
                item.box.size = FixedSizeConstraint(PANEL_W, item.height)
                top += item.height
            }
            contentHeight = top
            val last = items.lastOrNull { it.height > 0f }
            for (item in items) item.fit(item === last)
            // Slack under the items too once open; while folding it would show the next one
            body.size = FixedSizeConstraint(PANEL_W + CLIP_SLACK * 2, contentHeight * fold + if (fold == 1f) CLIP_SLACK else 0f)
            root.size = FixedSizeConstraint(PANEL_W, height)
            rule.visible = contentHeight * fold > 0f
            // Opaque, so its corners show; all four are rounded once nothing is under it
            header.radius = if (rule.visible) CascadeGeometricRadius(10f, 10f, 0f, 0f) else CascadeGeometricRadius(10f)
            if (initialized) {
                // Whatever is under the mouse now gets the hover
                scene.layout()
                scene.mouseMove(scene.mouseX.toDouble(), scene.mouseY.toDouble())
            }
        }

        fun paint() {
            root.color = color(Palette.panel)
            header.color = color(Palette.lerp(Palette.header, Palette.headerHover, headerHover.value))
            rule.color = color(Palette.rule)
            sign.setText(if (layout.collapsed) "+" else "–")
            sign.color = color(Palette.lerp(Palette.MUTED, Palette.TEXT, headerHover.value))
        }
    }

    /** Folds items in and out after something they depend on changed. */
    private fun refreshShown() {
        for (panel in panels) for (item in panel.items) {
            val target = if (item.wanted) 1f else 0f
            // Against where it's headed, not where it is: one still unfolding (and at 0) must turn back
            if (item.revealTarget == target) continue
            item.revealTarget = target
            item.box.animate(item::reveal, target, FOLD, easing = EaseOutEasingInterpolator)
        }
    }

    /** Whether "<section> <option>" has every word of the search in it. */
    private fun matches(section: Config.Section, label: String): Boolean {
        val text = "${section.name} $label".lowercase()
        return query.lowercase().split(' ').filter { it.isNotEmpty() }.all(text::contains)
    }

    /**
     * Like [Config.Option.shown], but the section's main switch doesn't hide anything, so a module's options can be
     * set up before it's switched on.
     */
    private fun Config.Option.listed(): Boolean {
        val parent = dependsOn
        val parentOk = parent == null || parent === section.main || (parent.listed() && parent())
        return parentOk && (condition?.invoke() ?: true)
    }

    // ---- Toolbar ----

    /** Typing filters the panels; Escape clears it, then lets go of it. */
    private fun searchField(x: Float) = roundedRectangle {
        position = FixedPositionConstraint(x, BAR_PAD)
        size = FixedSizeConstraint(SEARCH_W, TOOLBAR_H)
        radius = CascadeGeometricRadius(TOOLBAR_H / 2f)
        cursor = CursorTypes.IBEAM
        val outline = effect(OutlineEffect { })
        val inset = (TOOLBAR_H - ICON) / 2f
        searchIcon.position = FixedPositionConstraint(inset + 4f, inset)
        searchIcon.size = FixedSizeConstraint(ICON, ICON)
        adopt(searchIcon)
        val text = label("", Montserrat.SEMIBOLD, 12.5f, 0).apply {
            position = AlignPositionConstraint(PositionAlignment.START, PositionAlignment.CENTER)
            offset = CascadeGeometricOffset(inset + 4f + ICON + 8f, 0f)
        }
        adopt(text)
        searchField = this
        val field = this
        painters += {
            field.color = color(if (searchFocused) 0x80000000.toInt() else 0x59000000)
            outline.color = color(if (searchFocused) Palette.accent else 0x38FFFFFF)
            searchIcon.color = color(Palette.TEXT)
            text.setText(if (query.isEmpty()) "Search options" else if (searchFocused) "$query|" else query)
            text.color = color(if (query.isEmpty()) Palette.MUTED else Palette.TEXT)
        }

        // Pressing focuses it; keys then go to the focused element
        on<MouseEvent.Press> { cancel() }
        on<FocusEvent.Gain> { searchFocused = true; textInput(field, true); repaint() }
        on<FocusEvent.Lose> { searchFocused = false; textInput(field, false); repaint() }
        on<KeyEvent.Type> {
            if (char < ' ' || query.length >= MAX_QUERY) return@on
            search(query + char)
            cancel()
        }
        on<KeyEvent.Press> {
            when (key) {
                InputConstants.KEY_BACKSPACE -> if (query.isNotEmpty()) search(query.dropLast(1))
                // Swallowed, so the first Escape doesn't close the menu
                InputConstants.KEY_ESCAPE -> if (query.isNotEmpty()) search("") else scene.focused = null
                else -> return@on
            }
            cancel()
        }
    }

    private fun search(text: String) {
        query = text
        refreshShown()
        repaint()
    }

    /** Opens the HUD editor, which comes back here when closed. */
    private fun hudButton(x: Float) = roundedRectangle {
        val button = this
        val font = Montserrat.BOLD.tracked(0.14f)
        val text = "EDIT HUD"
        val w = font.width(text, 12f) + 32f + 2f
        position = FixedPositionConstraint(x, BAR_PAD)
        size = FixedSizeConstraint(w, TOOLBAR_H)
        radius = CascadeGeometricRadius(TOOLBAR_H / 2f)
        cursor = CursorTypes.POINTING_HAND
        val outline = effect(OutlineEffect { })
        val caption = label(text, font, 12f, Palette.TEXT).apply { position = CenterPositionConstraint() }
        adopt(caption)

        val hover = Fade { t ->
            button.color = color(Palette.lerp(0x0FFFFFFF, 0x24FFFFFF, t))
            outline.color = color(Palette.lerp(0x38FFFFFF, Palette.WHITE, t))
            caption.color = color(Palette.TEXT)
        }
        painters += hover::repaint
        on<MouseEvent.Move.Enter> { hover.to(1f, FAST) }
        on<MouseEvent.Move.Exit> { hover.to(0f, FAST) }
        onLeftClick { _, _ ->
            closePicker()
            HudEditorScreen(returnToConfig = true).open()
        }
    }

    /** Picks the accent colour and how opaque the panels are. */
    private fun themeButton(x: Float) = roundedRectangle {
        val button = this
        position = FixedPositionConstraint(x, BAR_PAD)
        size = FixedSizeConstraint(TOOLBAR_H, TOOLBAR_H)
        radius = CascadeGeometricRadius(TOOLBAR_H / 2f)
        cursor = CursorTypes.POINTING_HAND
        val outline = effect(OutlineEffect { })
        val inset = (TOOLBAR_H - ICON) / 2f
        bucketWater.position = FixedPositionConstraint(inset, inset + ICON * WATER_Y / 24f)
        bucketWater.size = FixedSizeConstraint(ICON, ICON * WATER_H / 24f)
        bucket.position = FixedPositionConstraint(inset, inset)
        bucket.size = FixedSizeConstraint(ICON, ICON)
        adopt(bucketWater)
        adopt(bucket)
        themeButton = this

        val hover = Fade { t ->
            button.color = color(Palette.lerp(0x0FFFFFFF, 0x24FFFFFF, t))
            outline.color = color(Palette.lerp(0x38FFFFFF, Palette.WHITE, t))
            bucket.color = color(Palette.TEXT)
            bucketWater.color = CascadeGeometricColor.vertical(Palette.lerp(Palette.accentHi, Palette.accent, t), Palette.accentDeep)
        }
        painters += hover::repaint
        on<MouseEvent.Move.Enter> { hover.to(1f, FAST) }
        on<MouseEvent.Move.Exit> { hover.to(0f, FAST) }
        onLeftClick { _, _ ->
            if (picker != null) return@onLeftClick closePicker()
            val picker = ColorPicker(Config.Theme.accent, Config.Theme.opacity, "MENU OPACITY", ::draggable) { rgb, alpha ->
                Config.Theme.accent = rgb
                Config.Theme.opacity = alpha
                Palette.update()
                repaint()
            }
            // Under the bar, right-aligned with the bucket
            openPicker(picker, themeButton.x + TOOLBAR_H - ColorPicker.WIDTH, toolbar.y + BAR_H + 8f)
        }
    }

    /** Shows [picker] with its top-left near ([x], [y]), kept on screen; clicking anywhere else closes it. */
    private fun openPicker(picker: ColorPicker, x: Float, y: Float) {
        closePicker()
        this.picker = picker
        picker.rasterize(scene.scale)
        val catcher = roundedRectangle {
            position = FixedPositionConstraint(0f, 0f)
            size = FillSizeConstraint()
            color = NONE
            onLeftClick(sound = false) { _, _ -> closePicker() }
        }
        picker.root.position = FixedPositionConstraint(
            x.coerceIn(8f, max(8f, scene.width - ColorPicker.WIDTH - 8f)),
            y.coerceIn(8f, max(8f, scene.height - picker.popupHeight - 8f)),
        )
        pickerLayers = listOf(catcher, picker.root)
        pickerLayers.forEach(scene::adopt)
    }

    private fun closePicker() {
        if (picker == null) return
        pickerLayers.forEach(scene::disown)
        pickerLayers = emptyList()
        picker = null
        Config.save()
    }

    // ---- Items ----

    /** A float driven by Cascade's animations that repaints whatever [paint] draws when it changes. */
    private inner class Fade(initial: Float = 0f, private val paint: (Float) -> Unit) {
        var value = initial
            set(v) {
                field = v
                paint(v)
            }

        fun to(target: Float, duration: Duration) {
            scene.animate(::value, target, duration)
        }

        fun repaint() = paint(value)
    }

    /** One entry in a panel, folded to [reveal] of its [fullHeight] (and faded with it) when hidden. */
    private abstract inner class Item(val panel: Panel, val fullHeight: Float) {
        val box = roundedRectangle {
            size = FixedSizeConstraint(PANEL_W, fullHeight)
            color = NONE
        }
        var height = fullHeight
        var reveal = 1f
            set(value) {
                field = value
                box.visible = value > 0f
                panel.relayout()
                paint()
            }
        /** What [reveal] is animating to. */
        var revealTarget = 1f
        val hoverFade = Fade { paint() }
        protected val hover get() = hoverFade.value
        abstract val wanted: Boolean
        abstract val description: String

        init {
            box.on<MouseEvent.Move.Enter> { setHovered(this@Item, true) }
            box.on<MouseEvent.Move.Exit> { setHovered(this@Item, false) }
        }

        abstract fun paint()

        /** Rounds the bottom corners when it's [last] in the panel, to sit inside the panel's own. */
        open fun fit(last: Boolean) {
            box.radius = if (last) CascadeGeometricRadius(0f, 0f, RADIUS, RADIUS) else CascadeGeometricRadius.ZERO
        }
    }

    private fun setHovered(item: Item, on: Boolean) {
        if (on) {
            described = item
            description.setText(item.description.ifEmpty { " " })
        } else if (described === item) {
            described = null
            description.setText(" ")
        }
        item.hoverFade.to(if (on) 1f else 0f, FAST)
    }

    private fun clickSound() = minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))

    /** Left-click flips the main switch (or opens it, with no switch); right-click opens its options. */
    private inner class ModuleItem(panel: Panel, val section: Config.Section) : Item(panel, MODULE_H) {
        val options = mutableListOf<OptionItem<*>>()
        private val main = section.main
        private val name = label(section.name.uppercase(), Montserrat.BOLD.tracked(0.1f), 13f, 0).apply {
            position = AlignPositionConstraint(PositionAlignment.START, PositionAlignment.CENTER)
            offset = CascadeGeometricOffset(PAD, 0f)
            shadow = true
        }
        private val sign = label("", Montserrat.BOLD, 15f, 0).apply {
            position = AlignPositionConstraint(PositionAlignment.END, PositionAlignment.CENTER)
            offset = CascadeGeometricOffset(-PAD, 0f)
        }
        private val bar = roundedRectangle {
            position = FixedPositionConstraint(0f, 0f)
            size = FixedSizeConstraint(3f, MODULE_H)
            interact = false
        }
        private val divider = roundedRectangle {
            position = AlignPositionConstraint(PositionAlignment.START, PositionAlignment.END)
            size = FixedSizeConstraint(PANEL_W, 1f)
            interact = false
        }
        private val on = Fade(if (main?.invoke() == true) 1f else 0f) { paint() }

        init {
            box.adopt(bar)
            box.adopt(divider)
            box.adopt(name)
            box.adopt(sign)
            box.cursor = CursorTypes.POINTING_HAND
            box.on<MouseEvent.Press> {
                when (button) {
                    InputConstants.MOUSE_BUTTON_LEFT -> if (main != null) {
                        main.value = !main.value
                        on.to(if (main()) 1f else 0f, FAST)
                        refreshShown()
                    } else toggleOpen()
                    InputConstants.MOUSE_BUTTON_RIGHT -> toggleOpen()
                    else -> return@on
                }
                clickSound()
                cancel()
            }
        }

        val open get() = section in expanded

        private fun toggleOpen() {
            if (options.isEmpty()) return
            if (!expanded.add(section)) expanded.remove(section)
            refreshShown()
            paint()
        }

        override val wanted get() = !searching || matches(section, "") || section.options.any { matches(section, it.label) }
        override val description get() = section.description

        override fun fit(last: Boolean) {
            super.fit(last)
            // Square, so kept clear of the rounded corner, and no line under the panel's edge
            bar.size = FixedSizeConstraint(3f, if (last) MODULE_H - RADIUS / 2f else MODULE_H)
            divider.visible = !last
        }

        override fun paint() {
            val t = if (main == null) 0f else on.value
            val idle = Palette.lerp(0x00FFFFFF, 0x12FFFFFF, hover)
            val active = Palette.lerp(Palette.accentRow, Palette.accent, hover * 0.25f)
            // As see-through as the menu opacity, like the rows switched off; the bar still marks it
            box.color = color(Palette.lerp(idle, active, t * Config.Theme.opacity).fade(reveal))
            bar.color = color(Palette.accent.fade(t * reveal))
            divider.color = color(Palette.LINE.fade(reveal))
            val base = if (main == null) Palette.lerp(Palette.TEXT, Palette.WHITE, hover) else Palette.lerp(Palette.MUTED, Palette.TEXT, hover)
            name.color = color(Palette.lerp(base, Palette.WHITE, t).fade(reveal))
            sign.visible = options.isNotEmpty()
            sign.setText(if (open) "–" else "+")
            sign.color = color(Palette.lerp(Palette.MUTED, Palette.TEXT, hover).fade(reveal))
        }
    }

    /** One of a module's options, shown under it while it's open (or while it matches the search). */
    private abstract inner class OptionItem<O : Config.Option>(val module: ModuleItem, val option: O, rowHeight: Float) :
        Item(module.panel, rowHeight) {
        protected val left = OPTION_PAD + option.depth * INDENT
        protected val name = label(option.label, Montserrat.SEMIBOLD, 12f, 0).apply {
            position = AlignPositionConstraint(PositionAlignment.START, PositionAlignment.CENTER)
            offset = CascadeGeometricOffset(left, 0f)
        }
        private val guide = roundedRectangle {
            position = FixedPositionConstraint(OPTION_PAD - 10f, 0f)
            size = FixedSizeConstraint(1f, rowHeight)
            interact = false
        }
        /** Right edge of the controls, relative to the item. */
        protected val right = PANEL_W - PAD

        init {
            box.adopt(guide)
            box.adopt(name)
        }

        override val wanted get() =
            option.listed() && module.wanted && (module.open || searching && matches(module.section, option.label))
        override val description get() = option.description

        override fun paint() {
            box.color = color(Palette.lerp(0x33000000, 0x40000000, hover).fade(reveal))
            guide.color = color(Palette.accent.fade(0.5f * reveal))
            name.color = color(Palette.lerp(Palette.DESCRIPTION, Palette.WHITE, hover).fade(reveal))
            paintControl(reveal)
        }

        abstract fun paintControl(alpha: Float)

        /** A control child: centred vertically, its right edge [fromRight] in from the item's padding. */
        protected fun <T : IPrimitiveElement<*>> T.control(fromRight: Float): T {
            position = AlignPositionConstraint(PositionAlignment.END, PositionAlignment.CENTER)
            offset = CascadeGeometricOffset(-PAD - fromRight, 0f)
            interact = false
            box.adopt(this)
            return this
        }
    }

    /** The whole item toggles it. */
    private inner class ToggleItem(module: ModuleItem, option: Config.BoolOption) : OptionItem<Config.BoolOption>(module, option, OPTION_H) {
        private val knob = roundedRectangle {
            size = FixedSizeConstraint(KNOB, KNOB)
            radius = CascadeGeometricRadius(KNOB / 2f)
            interact = false
        }
        private val switch = roundedRectangle {
            size = FixedSizeConstraint(SWITCH_W, SWITCH_H)
            radius = CascadeGeometricRadius(SWITCH_H / 2f)
            adopt(knob)
        }.control(0f)
        private val on = Fade(if (option()) 1f else 0f) { paint() }

        init {
            box.cursor = CursorTypes.POINTING_HAND
            box.onLeftClick { _, _ ->
                option.value = !option.value
                on.to(if (option()) 1f else 0f, FAST)
                refreshShown()
            }
        }

        override fun paintControl(alpha: Float) {
            val onColor = Palette.lerp(Palette.accent, Palette.accentHi, hover * 0.5f)
            switch.color = color(Palette.lerp(Palette.SWITCH_OFF, onColor, on.value).fade(alpha))
            knob.position = FixedPositionConstraint(2f + (SWITCH_W - KNOB - 4f) * on.value, 2f)
            knob.color = color(Palette.WHITE.fade(alpha))
        }
    }

    /** Name and value on top, the track under them; pressing anywhere on the item grabs the thumb. */
    private inner class SliderItem(module: ModuleItem, option: Config.Option, private val model: SliderModel) :
        OptionItem<Config.Option>(module, option, SLIDER_ROW_H) {
        private val value = label("", Montserrat.BOLD, 12f, 0).apply {
            position = AlignPositionConstraint(PositionAlignment.END, PositionAlignment.START)
            offset = CascadeGeometricOffset(-PAD, textTop(9f, 12f))
            interact = false
        }
        private val trackW = right - left
        private val track = roundedRectangle {
            position = FixedPositionConstraint(left, SLIDER_ROW_H - 14f)
            size = FixedSizeConstraint(trackW, 4f)
            radius = CascadeGeometricRadius(2f)
            interact = false
        }
        private val fill = roundedRectangle {
            position = FixedPositionConstraint(left, SLIDER_ROW_H - 14f)
            radius = CascadeGeometricRadius(2f)
            interact = false
        }
        private val thumb = roundedRectangle {
            size = FixedSizeConstraint(THUMB, THUMB)
            radius = CascadeGeometricRadius(THUMB / 2f)
            interact = false
        }

        init {
            name.position = AlignPositionConstraint(PositionAlignment.START, PositionAlignment.START)
            name.offset = CascadeGeometricOffset(left, textTop(9f, 12f))
            box.adopt(value)
            box.adopt(track)
            box.adopt(fill)
            box.adopt(thumb)
            box.cursor = CursorTypes.POINTING_HAND
            box.on<MouseEvent.Press> {
                if (button != InputConstants.MOUSE_BUTTON_LEFT) return@on
                set(x.toFloat())
                drag = { mx, _ -> set(mx) }
                cancel()
            }
            // Pressing focused the item, so the release reaches it even off the item
            box.on<MouseEvent.Release> { drag = null }
        }

        private fun set(mouseX: Float) {
            // The thumb's center travels between its half-widths at each end, like a range input's
            model.fraction = ((mouseX - box.x - left - THUMB / 2f) / (trackW - THUMB)).coerceIn(0f, 1f)
            paint()
            refreshShown()
        }

        override fun paintControl(alpha: Float) {
            val center = THUMB / 2f + model.fraction * (trackW - THUMB)
            track.color = color(Palette.TRACK.fade(alpha))
            fill.size = FixedSizeConstraint(center, 4f)
            fill.color = color(Palette.accent.fade(alpha))
            thumb.position = FixedPositionConstraint(left + center - THUMB / 2f, SLIDER_ROW_H - 12f - THUMB / 2f)
            thumb.color = color(Palette.lerp(Palette.TEXT, Palette.WHITE, hover).fade(alpha))
            value.setText(model.text)
            value.color = color(Palette.TEXT.fade(alpha))
        }
    }

    /** Left-click picks the next choice, right-click the one before. */
    private inner class ChoiceItem<E : Enum<E>>(module: ModuleItem, option: Config.ChoiceOption<E>) :
        OptionItem<Config.ChoiceOption<E>>(module, option, OPTION_H) {
        private val font = Montserrat.BOLD.tracked(0.08f)
        private val caption = label("", font, 11f, 0).apply { position = CenterPositionConstraint() }
        private val pill = roundedRectangle {
            radius = CascadeGeometricRadius(5f)
            adopt(caption)
        }.control(0f)

        init {
            box.cursor = CursorTypes.POINTING_HAND
            box.on<MouseEvent.Press> {
                val step = when (button) {
                    InputConstants.MOUSE_BUTTON_LEFT -> 1
                    InputConstants.MOUSE_BUTTON_RIGHT -> option.entries.size - 1
                    else -> return@on
                }
                option.value = option.entries[(option.entries.indexOf(option()) + step) % option.entries.size]
                clickSound()
                paint()
                refreshShown()
                cancel()
            }
        }

        override fun paintControl(alpha: Float) {
            val text = option.name(option()).uppercase()
            caption.setText(text)
            pill.size = FixedSizeConstraint(font.width(text, 11f) + 20f, PILL_H)
            pill.color = color(Palette.lerp(Palette.accent, Palette.accentHi, hover * 0.4f).fade(alpha))
            caption.color = color(Palette.onAccent.fade(alpha))
        }
    }

    /** A swatch; clicking opens a colour picker beside the panel. */
    private inner class ColorItem(module: ModuleItem, option: Config.ColorOption) : OptionItem<Config.ColorOption>(module, option, OPTION_H) {
        private val checkerImage = image {
            position = FixedPositionConstraint(0f, 0f)
            size = FixedSizeConstraint(SWATCH_W, SWATCH_H)
            interact = false
        }
        private val fill = roundedRectangle {
            position = FixedPositionConstraint(0f, 0f)
            size = FixedSizeConstraint(SWATCH_W, SWATCH_H)
            radius = CascadeGeometricRadius(4f)
            interact = false
        }
        private val swatch = roundedRectangle {
            size = FixedSizeConstraint(SWATCH_W, SWATCH_H)
            radius = CascadeGeometricRadius(4f)
            color = NONE
            adopt(checkerImage)
            adopt(fill)
        }.control(0f)
        private val swatchOutline = swatch.effect(OutlineEffect { })

        init {
            swatchCheckers += checkerImage
            box.cursor = CursorTypes.POINTING_HAND
            box.onLeftClick { _, _ -> open() }
        }

        private fun open() {
            val start = option()
            val picker = ColorPicker(start.rgb, if (option.allowAlpha) start.alphaFloat else null, "OPACITY", ::draggable) { rgb, alpha ->
                option.value = Color(rgb or 0xFF000000.toInt()).withAlpha(alpha)
                paint()
            }
            // Beside the panel, level with the item; on the left when there's no room on the right
            val besideRight = panel.x + PANEL_W + 8f
            val x = if (besideRight + ColorPicker.WIDTH <= scene.width - 8f) besideRight else panel.x - ColorPicker.WIDTH - 8f
            openPicker(picker, x, box.y)
        }

        override fun paintControl(alpha: Float) {
            fill.color = color(option().rgba.fade(alpha))
            checkerImage.color = color(Palette.WHITE.fade(alpha))
            swatchOutline.color = color(Palette.lerp(0x4DFFFFFF, Palette.WHITE, hover).fade(alpha))
        }
    }

    /** Clicking anywhere on the item presses it. */
    private inner class ButtonItem(module: ModuleItem, option: Config.ButtonOption) : OptionItem<Config.ButtonOption>(module, option, OPTION_H + 4f) {
        private val font = Montserrat.BOLD.tracked(0.14f)
        private val text = option.text.uppercase()
        private val caption = label(text, font, 11f, 0).apply { position = CenterPositionConstraint() }
        private val button = roundedRectangle {
            size = FixedSizeConstraint(font.width(text, 11f) + 24f, PILL_H + 2f)
            radius = CascadeGeometricRadius(5f)
            adopt(caption)
        }.control(0f)
        private val outline = button.effect(OutlineEffect { })

        init {
            box.cursor = CursorTypes.POINTING_HAND
            box.onLeftClick { _, _ -> option.onClick() }
        }

        override fun paintControl(alpha: Float) {
            button.color = color(Palette.lerp(0x12FFFFFF, 0x29FFFFFF, hover).fade(alpha))
            outline.color = color(Palette.lerp(0x47FFFFFF, Palette.WHITE, hover).fade(alpha))
            caption.color = color(Palette.TEXT.fade(alpha))
        }
    }

    // ---- Plumbing ----

    /** Makes [element] call [onDrag] with the mouse position on a left press and while the mouse is dragged. */
    private fun draggable(element: IPrimitiveElement<*>, onDrag: (Float, Float) -> Unit) {
        element.on<MouseEvent.Press> {
            if (button != InputConstants.MOUSE_BUTTON_LEFT) return@on
            onDrag(x.toFloat(), y.toFloat())
            drag = onDrag
            cancel()
        }
        element.on<MouseEvent.Release> { drag = null }
    }

    /** Re-applies every color, after the theme changed. */
    private fun repaint() {
        painters.forEach { it() }
        panels.forEach { panel -> panel.items.forEach { it.paint() } }
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val drag = drag ?: return super.mouseDragged(event, dx, dy)
        drag((event.x() / scene.scale).toFloat(), (event.y() / scene.scale).toFloat())
        return true
    }

    override fun removed() {
        Config.save()
        Config.tick(force = true)
        super.removed()
    }

    override fun isPauseScreen() = false

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        extractBlurredBackground(graphics)
        graphics.fill(0, 0, width, height, SCRIM)
    }
}
