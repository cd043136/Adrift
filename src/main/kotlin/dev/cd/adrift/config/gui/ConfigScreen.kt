package dev.cd.adrift.config.gui

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.cd.adrift.Adrift
import dev.cd.adrift.config.Config
import dev.cd.adrift.config.gui.Palette.fade
import dev.cd.adrift.utils.Color
import foo.starred.cascade.animation.extension.impl.animate
import foo.starred.cascade.animation.interpolator.easing.impl.EaseOutEasingInterpolator
import foo.starred.cascade.animation.type.impl.PropertyAnimationType
import foo.starred.cascade.constraints.impl.data.PositionAlignment
import foo.starred.cascade.constraints.impl.position.AlignPositionConstraint
import foo.starred.cascade.constraints.impl.position.CenterPositionConstraint
import foo.starred.cascade.constraints.impl.position.FixedPositionConstraint
import foo.starred.cascade.constraints.impl.size.FillSizeConstraint
import foo.starred.cascade.constraints.impl.size.FixedSizeConstraint
import foo.starred.cascade.effects.impl.OutlineEffect
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
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Config popup opened by `/adrift` or the Open Config key (from personalmod's config screen): [Config] categories
 * are tabs, sections are headed lists of rows. Hovering a row highlights it and shows its description at the
 * bottom; options that aren't [Config.Option.shown] fold away. The bucket picks the accent colour and how opaque
 * the menu is; colour rows open the same picker for their colour. The world stays visible and blurred behind it.
 *
 * Every size is in the mockup's CSS pixels; [init] scales the whole scene to fit the window.
 */
class ConfigScreen : CascadeScreen("Adrift") {
    private companion object {
        const val PANEL_W = 820f
        const val PANEL_H = 585f
        const val PAD = 30f
        const val TABS_Y = 102f
        const val TAB_H = 37f
        const val RULE_Y = TABS_Y + TAB_H
        const val LIST_Y = 147f
        const val LIST_H = 370f
        const val FOOTER_Y = LIST_Y + LIST_H
        /** Rows reach 16 px past the panel padding; the list has 4 px more on the right for the scrollbar. */
        const val ROW_X = 14f
        const val ROW_W = 788f
        const val ROW_PAD = 16f
        const val ROW_H = 44f
        const val SECTION_H = 35f
        const val GAP = 12f
        const val SWITCH_W = 46f
        const val SWITCH_H = 24f
        const val KNOB = 18f
        const val SLIDER_W = 240f
        const val SLIDER_H = 18f
        const val THUMB = 15f
        const val VALUE_W = 56f
        const val SEGMENT_H = 30f
        const val SEGMENT_PAD = 14f
        const val SWATCH_W = 54f
        const val SWATCH_H = 26f
        const val BUTTON_H = 35f
        const val THEME_BUTTON = 38f
        const val ICON = 22f
        /**
         * Where the bucket's water is in its 24 px viewBox. It's a separate SVG cropped to these bounds, so a vertical
         * tint over it is the mockup's gradient.
         */
        const val WATER_Y = 12.4f
        const val WATER_H = 8.6f
        const val SCROLL_STEP = 60f
        const val SCROLLBAR_W = 10f
        const val SCROLLBAR_INSET = 6f
        const val SCROLL_THUMB_MIN = 40f
        /** Never drawn bigger than this many GUI units per mockup pixel, about the size of the old menu. */
        const val MAX_SCALE = 0.415f
        const val SCRIM = 0x40050A08
        val FAST = 150.milliseconds
        val HIGHLIGHT_FADE = 160.milliseconds
        val FOLD = 220.milliseconds
        val SCROLL_EASE = 120.milliseconds
        /** How long the scrollbar stays lit after scrolling. */
        val SCROLL_FLASH = 550.milliseconds
        val NONE = CascadeGeometricColor.TRANSPARENT

        /** Survives reopening the screen, so the menu comes back on the tab it was left on. */
        var lastCategory = 0

        fun color(argb: Int) = CascadeGeometricColor(argb)
    }

    private var category = Config.categories[lastCategory.coerceIn(Config.categories.indices)]
    private var initialized = false
    /** Set while something is dragged; gets the mouse position in scene coordinates. Cascade has no drag event. */
    private var drag: ((Float, Float) -> Unit)? = null
    /** Called again when the theme changes. */
    private val painters = mutableListOf<() -> Unit>()

    private val items = mutableListOf<Item>()
    /** The row the highlight sits on; it stays there while fading out. */
    private var highlighted: Row<*>? = null
    private var hoveredRow: Row<*>? = null
    private var contentHeight = 0f
    private val maxScroll get() = max(0f, contentHeight - LIST_H)
    private var scrollY = 0f
    private var scrollTarget = 0f
    /** Lit for a moment after scrolling. */
    private var scrollActive = false
    private var scrollbarDragging = false
    private var scrollFlashTimer = 0f
    private var scrollbarOpacity = 0.45f
        set(value) {
            field = value
            paintScrollbarColors()
        }
    private var highlightAlpha = 0f
        set(value) {
            field = value
            paintHighlight()
        }

    private val panel: RoundedRectanglePrimitive
    private val list: ScrollablePrimitive
    private val highlight: RoundedRectanglePrimitive
    private val highlightBar: RoundedRectanglePrimitive
    private val description: TextPrimitive
    private val scrollTrack: RoundedRectanglePrimitive
    private val scrollThumb: RoundedRectanglePrimitive
    private val bucket = image { interact = false }
    private val bucketWater = image { interact = false }
    /** The open colour popup, if any. */
    private var picker: ColorPicker? = null
    private var pickerLayers = emptyList<IPrimitiveElement<*>>()
    /** Checkerboards behind colour swatches, re-rasterized when the scale changes. */
    private val swatchCheckers = mutableListOf<ImagePrimitive>()

    init {
        panel = roundedRectangle {
            position = CenterPositionConstraint()
            size = FixedSizeConstraint(PANEL_W, PANEL_H)
            radius = CascadeGeometricRadius(14f)
            effect(OutlineEffect { color = color(Palette.LINE) })
            attach(scene)
        }
        painters += { panel.color = color(Palette.panel) }

        panel.adopt(label("SETTINGS", Montserrat.EXTRABOLD.tracked(0.16f), 30f, Palette.TEXT).apply {
            position = FixedPositionConstraint(PAD, textTop(26f, 30f))
        })
        val name = "Adrift"
        panel.adopt(label(name, Montserrat.SEMIBOLD, 12.5f, Palette.TEXT).apply {
            position = FixedPositionConstraint(PAD, textTop(67f, 12.5f))
        })
        val versionX = PAD + Montserrat.SEMIBOLD.width(name, 12.5f) + Montserrat.MEDIUM.width(" ", 12.5f)
        panel.adopt(label("v${Adrift.VERSION}", Montserrat.MEDIUM, 12.5f, Palette.MUTED).apply {
            position = FixedPositionConstraint(versionX, textTop(67f, 12.5f))
        })
        panel.adopt(themeButton())

        panel.adopt(rule(RULE_Y))
        var tabX = PAD
        for (target in Config.categories) {
            val tab = tab(target, tabX)
            panel.adopt(tab)
            tabX += (tab.size as FixedSizeConstraint).width + 6f
        }

        list = scrollable {
            position = FixedPositionConstraint(0f, LIST_Y)
            size = FixedSizeConstraint(PANEL_W, LIST_H)
            color = NONE
        }
        // Scrolling moves the rows instead (smoothly, and so the scrollbar can be dragged), so the built-in offset
        // stays 0
        list.listeners[MouseEvent.Scroll::class.java]?.clear()
        list.on<MouseEvent.Scroll> {
            scrollTo(scrollTarget - amount.toFloat() * SCROLL_STEP)
            cancel()
        }
        list.on<MouseEvent.Move.Enter> { paintScrollbar() }
        list.on<MouseEvent.Move.Exit> { paintScrollbar() }
        panel.adopt(list)

        highlight = roundedRectangle {
            size = FixedSizeConstraint(ROW_W, ROW_H - 1f)
            interact = false
            visible = false
        }
        highlightBar = roundedRectangle {
            position = FixedPositionConstraint(0f, 0f)
            size = FixedSizeConstraint(3f, ROW_H - 1f)
            interact = false
        }
        highlight.adopt(highlightBar)
        painters += ::paintHighlight

        scrollThumb = roundedRectangle {
            radius = CascadeGeometricRadius(3f)
            interact = false
        }
        scrollTrack = roundedRectangle {
            position = FixedPositionConstraint(PANEL_W - 5f - SCROLLBAR_W, LIST_Y + SCROLLBAR_INSET)
            size = FixedSizeConstraint(SCROLLBAR_W, LIST_H - SCROLLBAR_INSET * 2)
            radius = CascadeGeometricRadius(SCROLLBAR_W / 2f)
            cursor = CursorTypes.POINTING_HAND
            adopt(scrollThumb)
            on<MouseEvent.Move.Enter> { paintScrollbar() }
            on<MouseEvent.Move.Exit> { paintScrollbar() }
        }
        scrollbarDrag()
        panel.adopt(scrollTrack)

        panel.adopt(rule(FOOTER_Y))
        description = label(" ", Montserrat.MEDIUM, 13f, Palette.DESCRIPTION).apply {
            position = FixedPositionConstraint(PAD, textTop(FOOTER_Y + 2f + 14f, 13f))
        }
        panel.adopt(description)

        rebuild()
        repaint()
    }

    override fun init() {
        // Fits the panel (with a margin) in the window, at most MAX_SCALE
        val scale = min(MAX_SCALE, min(width / (PANEL_W + 40f), height / (PANEL_H + 40f)))
        scene.scale = scale
        scene.width = width / scale
        scene.height = height / scale
        // Rasterized at the size they're drawn, so they stay sharp
        val icon = ceil(ICON * scale).toInt()
        bucket.location = CascadeSVG.load(Adrift.id("textures/gui/config/bucket.svg"), icon, icon)
        bucketWater.location = CascadeSVG.load(
            Adrift.id("textures/gui/config/bucket_water.svg"), icon, ceil(ICON * WATER_H / 24f * scale).toInt(),
        )
        picker?.rasterize(scale)
        swatchCheckers.forEach { it.location = checker(SWATCH_W, SWATCH_H, 5f, scale) }
        initialized = true
        scene.layout()
    }

    // ---- Header ----

    private fun themeButton() = roundedRectangle {
        val button = this
        position = FixedPositionConstraint(PANEL_W - PAD - THEME_BUTTON, 24f)
        size = FixedSizeConstraint(THEME_BUTTON, THEME_BUTTON)
        radius = CascadeGeometricRadius(THEME_BUTTON / 2f)
        cursor = CursorTypes.POINTING_HAND
        val outline = effect(OutlineEffect { })
        val inset = (THEME_BUTTON - ICON) / 2f
        bucketWater.position = FixedPositionConstraint(inset, inset + ICON * WATER_Y / 24f)
        bucketWater.size = FixedSizeConstraint(ICON, ICON * WATER_H / 24f)
        bucket.position = FixedPositionConstraint(inset, inset)
        bucket.size = FixedSizeConstraint(ICON, ICON)
        adopt(bucketWater)
        adopt(bucket)

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
            // Under the bucket, right-aligned with it
            openPicker(picker, PANEL_W - PAD - ColorPicker.WIDTH, 24f + THEME_BUTTON + 6f)
        }
    }

    /** Shows [picker] with its top-left at ([x], [y]) of the panel; clicking anywhere else closes it. */
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
        // Placed from the panel's centre since the panel is centred
        picker.root.position = CenterPositionConstraint()
        picker.root.offset = CascadeGeometricOffset(x + ColorPicker.WIDTH / 2f - PANEL_W / 2f, y + picker.height / 2f - PANEL_H / 2f)
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

    // ---- Tabs ----

    private fun tab(target: Config.Category, x: Float) = roundedRectangle {
        val font = Montserrat.BOLD.tracked(0.14f)
        val text = target.name.uppercase()
        val w = font.width(text, 13f) + 32f
        position = FixedPositionConstraint(x, TABS_Y)
        size = FixedSizeConstraint(w, TAB_H)
        color = NONE
        cursor = CursorTypes.POINTING_HAND
        val name = label(text, font, 13f, Palette.MUTED).apply { position = FixedPositionConstraint(16f, textTop(10f, 13f)) }
        adopt(name)
        val underline = roundedRectangle {
            position = FixedPositionConstraint(12f, TAB_H)
            size = FixedSizeConstraint(w - 24f, 2f)
            radius = CascadeGeometricRadius(1f)
            interact = false
        }
        adopt(underline)

        val hover = Fade { t ->
            val selected = target === category
            name.color = color(if (selected) Palette.WHITE else Palette.lerp(Palette.MUTED, Palette.TEXT, t))
            underline.visible = selected
            underline.color = color(Palette.accent)
        }
        painters += hover::repaint
        on<MouseEvent.Move.Enter> { hover.to(1f, FAST) }
        on<MouseEvent.Move.Exit> { hover.to(0f, FAST) }
        onLeftClick { _, _ ->
            if (target === category) return@onLeftClick
            category = target
            lastCategory = Config.categories.indexOf(target)
            rebuild()
            repaint()
        }
    }

    private fun rule(y: Float) = roundedRectangle {
        val rule = this
        position = FixedPositionConstraint(ROW_X, y)
        size = FixedSizeConstraint(PANEL_W - ROW_X * 2, 2f)
        interact = false
        painters += { rule.color = color(Palette.rule) }
    }

    // ---- List ----

    /** Re-creates the rows for the current tab. */
    private fun rebuild() {
        closePicker()
        items.forEach { list.disown(it.box) }
        items.clear()
        swatchCheckers.clear()
        list.disown(highlight)
        list.adopt(highlight)
        highlighted = null
        hoveredRow = null
        highlightAlpha = 0f
        description.setText(" ")
        scrollY = 0f
        scrollTarget = 0f

        for (section in category.sections) {
            val header = SectionHeader(section)
            items += header
            for (option in section.options) {
                items += when (option) {
                    is Config.BoolOption -> ToggleRow(option)
                    is Config.IntOption -> SliderRow(option, intSlider(option))
                    is Config.DoubleOption -> SliderRow(option, doubleSlider(option))
                    is Config.ChoiceOption<*> -> ChoiceRow(option)
                    is Config.ColorOption -> ColorRow(option)
                    is Config.ButtonOption -> ButtonRow(option)
                }.also { header.rows += it }
            }
        }
        items.forEach { list.adopt(it.box) }
        // No animation when switching tabs
        for (item in items) item.reveal = if (item.wanted) 1f else 0f
        relayout()
    }

    /** Folds rows in and out after a value that others depend on changed. */
    private fun refreshShown() {
        for (item in items) {
            val target = if (item.wanted) 1f else 0f
            if (item.reveal != target) item.box.animate(item::reveal, target, FOLD, easing = EaseOutEasingInterpolator)
        }
    }

    /** Stacks the rows (folded ones as tall as they're shown) and moves them by the scroll. */
    private fun relayout() {
        contentHeight = items.sumOf { (it.fullHeight * it.reveal).toDouble() }.toFloat()
        scrollY = scrollY.coerceIn(0f, maxScroll)
        scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
        var y = 0f
        for (item in items) {
            item.top = y
            item.height = item.fullHeight * item.reveal
            item.box.position = FixedPositionConstraint(ROW_X, y - scrollY)
            item.box.size = FixedSizeConstraint(ROW_W, item.height)
            y += item.height
        }
        placeHighlight()
        paintScrollbar()
        if (initialized) {
            // Whatever is under the mouse now gets the hover
            scene.layout()
            scene.mouseMove(scene.mouseX.toDouble(), scene.mouseY.toDouble())
        }
    }

    /** Scroll position, moved by [scrollTo]. */
    private var scroll: Float
        get() = scrollY
        set(value) {
            scrollY = value
            relayout()
        }

    /** Eases there, from wherever an earlier scroll is heading. */
    private fun scrollTo(target: Float) {
        scrollTarget = target.coerceIn(0f, maxScroll)
        if (scrollTarget == scrollY) return
        list.animate(::scroll, scrollTarget, SCROLL_EASE, easing = EaseOutEasingInterpolator)
        flashScrollbar()
    }

    /** Goes straight there, stopping any eased scroll. */
    private fun jumpTo(target: Float) {
        scene.animations?.removeIf { it is PropertyAnimationType<*> && it.property == ::scroll }
        scrollTarget = target.coerceIn(0f, maxScroll)
        scroll = scrollTarget
    }

    // ---- Highlight and description ----

    private fun setHovered(row: Row<*>, on: Boolean) {
        if (on) {
            hoveredRow = row
            highlighted = row
            placeHighlight()
            description.setText(row.option.description)
            list.animate(::highlightAlpha, 1f, HIGHLIGHT_FADE)
        } else if (hoveredRow === row) {
            hoveredRow = null
            description.setText(" ")
            list.animate(::highlightAlpha, 0f, HIGHLIGHT_FADE)
        }
        row.hoverFade.to(if (on) 1f else 0f, FAST)
        paintScrollbar()
    }

    private fun placeHighlight() {
        val row = highlighted ?: return
        // A pixel short, so the row's divider stays visible
        val h = max(0f, row.height - 1f)
        highlight.position = FixedPositionConstraint(ROW_X, row.top - scrollY)
        highlight.size = FixedSizeConstraint(ROW_W, h)
        highlightBar.size = FixedSizeConstraint(3f, h)
    }

    private fun paintHighlight() {
        highlight.visible = highlightAlpha > 0f
        highlight.color = color(Palette.accentRow.fade(highlightAlpha))
        highlightBar.color = color(Palette.accent.fade(highlightAlpha))
    }

    // ---- Scrollbar ----

    private fun flashScrollbar() {
        scrollActive = true
        paintScrollbar()
        scrollFlashTimer = 0f
        scrollTrack.animate(::scrollFlashTimer, 1f, SCROLL_FLASH) {
            scrollActive = false
            paintScrollbar()
        }
    }

    private val thumbHeight get() = min(scrollInner, max(SCROLL_THUMB_MIN, scrollInner * (LIST_H / max(contentHeight, 1f))))
    private val scrollInner get() = LIST_H - SCROLLBAR_INSET * 2 - 4f

    private fun paintScrollbar() {
        scrollTrack.visible = maxScroll > 1f
        val travel = scrollInner - thumbHeight
        scrollThumb.position = FixedPositionConstraint(2f, 2f + if (maxScroll > 0f) scrollY / maxScroll * travel else 0f)
        scrollThumb.size = FixedSizeConstraint(SCROLLBAR_W - 4f, thumbHeight)
        val target = when {
            scrollbarLit -> 1f
            hoveredRow != null || list.hovered -> 0.7f
            else -> 0.45f
        }
        scrollTrack.animate(::scrollbarOpacity, target, 200.milliseconds)
        paintScrollbarColors()
    }

    private val scrollbarLit get() = scrollActive || scrollbarDragging || scrollTrack.hovered

    private fun paintScrollbarColors() {
        scrollTrack.color = color(Palette.TEXT.fade((if (scrollbarLit) 0.7f else 0.55f) * scrollbarOpacity))
        scrollThumb.color = color(Palette.SCROLL_THUMB.fade(scrollbarOpacity))
    }

    /** Clicking the rail jumps there; the thumb (or the rail, after the jump) is then dragged. */
    private fun scrollbarDrag() {
        scrollTrack.on<MouseEvent.Press> {
            if (button != InputConstants.MOUSE_BUTTON_LEFT) return@on
            val mouseY = y.toFloat()
            val top = scrollTrack.y + 2f
            val travel = max(1f, scrollInner - thumbHeight)
            var thumbTop = scrollThumb.y - top
            if (mouseY < scrollThumb.y - 2f || mouseY > scrollThumb.y + thumbHeight + 2f) {
                thumbTop = (mouseY - top - thumbHeight / 2f).coerceIn(0f, travel)
                jumpTo(thumbTop / travel * maxScroll)
            }
            val startY = mouseY
            val startTop = thumbTop
            drag = { _, my -> jumpTo((startTop + my - startY).coerceIn(0f, travel) / travel * maxScroll) }
            scrollbarDragging = true
            paintScrollbar()
            cancel()
        }
        // Pressing focused the rail, so the release reaches it even off the rail
        scrollTrack.on<MouseEvent.Release> {
            drag = null
            scrollbarDragging = false
            paintScrollbar()
        }
    }

    // ---- Rows ----

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

    /** One entry in the list, folded to [reveal] of its [fullHeight] (and faded with it) when hidden. */
    private abstract inner class Item(val fullHeight: Float) {
        val box = roundedRectangle {
            size = FixedSizeConstraint(ROW_W, fullHeight)
            color = NONE
        }
        /** Within the list's content, so before scrolling. */
        var top = 0f
        var height = fullHeight
        var reveal = 1f
            set(value) {
                field = value
                box.visible = value > 0f
                relayout()
                paint()
            }
        abstract val wanted: Boolean

        abstract fun paint()
    }

    private inner class SectionHeader(section: Config.Section) : Item(SECTION_H) {
        val rows = mutableListOf<Row<*>>()
        private val name = label(section.name.uppercase(), Montserrat.BOLD.tracked(0.2f), 11f, 0).apply {
            // 16 px of padding above the line; anchored to the bottom so it stays put while the header folds
            position = AlignPositionConstraint(PositionAlignment.START, PositionAlignment.END)
            offset = CascadeGeometricOffset(ROW_PAD, -(SECTION_H - textTop(16f, 11f) - Montserrat.BOLD.height(11f)))
        }

        init {
            box.interact = false
            box.adopt(name)
        }

        override val wanted get() = rows.any { it.wanted }

        override fun paint() {
            name.color = color(Palette.accent.fade(reveal))
        }
    }

    private abstract inner class Row<O : Config.Option>(val option: O) : Item(ROW_H) {
        protected val name = label(option.label.uppercase(), Montserrat.BOLD.tracked(0.1f), 14f, 0).apply {
            position = AlignPositionConstraint(PositionAlignment.START, PositionAlignment.CENTER)
            offset = CascadeGeometricOffset(ROW_PAD, 0f)
            shadow = true
        }
        private val divider = roundedRectangle {
            position = AlignPositionConstraint(PositionAlignment.START, PositionAlignment.END)
            size = FixedSizeConstraint(ROW_W, 1f)
            interact = false
        }
        val hoverFade = Fade { paint() }
        protected val hover get() = hoverFade.value
        /** Right edge of the controls, relative to the row. */
        protected val right = ROW_W - ROW_PAD

        init {
            box.adopt(divider)
            box.adopt(name)
            box.on<MouseEvent.Move.Enter> { setHovered(this@Row, true) }
            box.on<MouseEvent.Move.Exit> { setHovered(this@Row, false) }
        }

        override val wanted get() = option.shown

        override fun paint() {
            name.color = color(Palette.TEXT.fade(reveal))
            divider.color = color(Palette.LINE.fade(reveal))
            paintControl(reveal)
        }

        abstract fun paintControl(alpha: Float)

        /** A control child: centered vertically, its right edge [fromRight] in from the row's padding. */
        protected fun <T : IPrimitiveElement<*>> T.control(fromRight: Float): T {
            position = AlignPositionConstraint(PositionAlignment.END, PositionAlignment.CENTER)
            offset = CascadeGeometricOffset(-ROW_PAD - fromRight, 0f)
            interact = false
            box.adopt(this)
            return this
        }
    }

    /** The whole row toggles it, not just the switch. */
    private inner class ToggleRow(option: Config.BoolOption) : Row<Config.BoolOption>(option) {
        private val state = label("", Montserrat.BOLD.tracked(0.1f), 12f, 0).control(SWITCH_W + GAP)
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
            state.setText(if (option()) "ON" else "OFF")
            state.color = color(Palette.lerp(Palette.MUTED, Palette.WHITE, hover).fade(alpha))
            val onColor = Palette.lerp(Palette.accent, Palette.accentHi, hover)
            switch.color = color(Palette.lerp(Palette.SWITCH_OFF, onColor, on.value).fade(alpha))
            knob.position = FixedPositionConstraint(3f + (SWITCH_W - KNOB - 6f) * on.value, 3f)
            knob.color = color(Palette.WHITE.fade(alpha))
        }
    }

    /** A slider's value as a fraction of its range, and how it reads. */
    private interface SliderModel {
        var fraction: Float
        val text: String
    }

    private fun intSlider(option: Config.IntOption) = object : SliderModel {
        override var fraction: Float
            get() = (option() - option.min).toFloat() / (option.max - option.min)
            set(value) {
                option.value = option.min + (value * (option.max - option.min)).roundToInt()
            }
        override val text get() = "${option()}${option.suffix}"
    }

    private fun doubleSlider(option: Config.DoubleOption) = object : SliderModel {
        override var fraction: Float
            get() = ((option() - option.min) / (option.max - option.min)).toFloat()
            set(value) {
                option.value = option.min + value * (option.max - option.min)
            }
        override val text get() = "${option.format()}${option.suffix}"
    }

    /** Pressing anywhere along the track (or a little either side) grabs the thumb. */
    private inner class SliderRow(option: Config.Option, private val model: SliderModel) : Row<Config.Option>(option) {
        private val value = label("", Montserrat.BOLD, 14f, 0).control(0f)
        private val fill = roundedRectangle {
            position = FixedPositionConstraint(0f, (SLIDER_H - 4f) / 2f)
            radius = CascadeGeometricRadius(2f)
            interact = false
        }
        private val ring = roundedRectangle {
            size = FixedSizeConstraint(THUMB + 8f, THUMB + 8f)
            radius = CascadeGeometricRadius(THUMB / 2f + 4f)
            interact = false
        }
        private val thumb = roundedRectangle {
            size = FixedSizeConstraint(THUMB, THUMB)
            radius = CascadeGeometricRadius(THUMB / 2f)
            interact = false
        }
        private val track = roundedRectangle {
            position = FixedPositionConstraint(0f, (SLIDER_H - 4f) / 2f)
            size = FixedSizeConstraint(SLIDER_W, 4f)
            radius = CascadeGeometricRadius(2f)
            interact = false
        }
        private val slider = roundedRectangle {
            size = FixedSizeConstraint(SLIDER_W, SLIDER_H)
            color = NONE
            adopt(track)
            adopt(fill)
            adopt(ring)
            adopt(thumb)
        }.control(VALUE_W + GAP)

        init {
            box.on<MouseEvent.Move.Any> {
                box.cursor = if (overSlider(x.toFloat())) CursorTypes.POINTING_HAND else null
            }
            box.on<MouseEvent.Press> {
                if (button != InputConstants.MOUSE_BUTTON_LEFT || !overSlider(x.toFloat())) return@on
                set(x.toFloat())
                drag = { mx, _ -> set(mx) }
                cancel()
            }
            // Pressing focused the row, so the release reaches it even off the row
            box.on<MouseEvent.Release> { drag = null }
        }

        private fun overSlider(x: Float) = x >= slider.x - 8f && x <= slider.x + SLIDER_W + 8f

        private fun set(mouseX: Float) {
            // The thumb's center travels between its half-widths at each end, like a range input's
            model.fraction = ((mouseX - slider.x - THUMB / 2f) / (SLIDER_W - THUMB)).coerceIn(0f, 1f)
            paint()
            refreshShown()
        }

        override fun paintControl(alpha: Float) {
            val frac = model.fraction
            val center = THUMB / 2f + frac * (SLIDER_W - THUMB)
            track.color = color(Palette.TRACK.fade(alpha))
            fill.size = FixedSizeConstraint(center, 4f)
            fill.color = color(Palette.WHITE.fade(alpha))
            thumb.position = FixedPositionConstraint(center - THUMB / 2f, (SLIDER_H - THUMB) / 2f)
            thumb.color = color(Palette.WHITE.fade(alpha))
            ring.position = FixedPositionConstraint(center - THUMB / 2f - 4f, (SLIDER_H - THUMB) / 2f - 4f)
            ring.color = color(Palette.WHITE.fade(0.25f * hover * alpha))
            value.setText(model.text)
            value.color = color(Palette.TEXT.fade(alpha))
        }
    }

    private inner class ButtonRow(option: Config.ButtonOption) : Row<Config.ButtonOption>(option) {
        private val font = Montserrat.BOLD.tracked(0.14f)
        private val text = option.text.uppercase()
        private val buttonW = font.width(text, 12f) + 32f + 2f
        private val caption = label(text, font, 12f, 0).apply { position = CenterPositionConstraint() }
        private val outline: OutlineEffect
        private val button = roundedRectangle {
            size = FixedSizeConstraint(buttonW, BUTTON_H)
            radius = CascadeGeometricRadius(6f)
            adopt(caption)
        }.control(0f)
        private val buttonHover = Fade { paint() }

        init {
            outline = button.effect(OutlineEffect { })
            box.on<MouseEvent.Move.Any> {
                val over = overButton(x.toFloat(), y.toFloat())
                box.cursor = if (over) CursorTypes.POINTING_HAND else null
                buttonHover.to(if (over) 1f else 0f, FAST)
            }
            box.on<MouseEvent.Move.Exit> { buttonHover.to(0f, FAST) }
            box.on<MouseEvent.Press> {
                if (button != InputConstants.MOUSE_BUTTON_LEFT || !overButton(x.toFloat(), y.toFloat())) return@on
                minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
                option.onClick()
                cancel()
            }
        }

        private fun overButton(x: Float, y: Float) =
            x >= button.x && x <= button.x + button.width && y >= button.y && y <= button.y + button.height

        override fun paintControl(alpha: Float) {
            val t = buttonHover.value
            button.color = color(Palette.lerp(0x12FFFFFF, 0x29FFFFFF, t).fade(alpha))
            outline.color = color(Palette.lerp(0x47FFFFFF, Palette.WHITE, t).fade(alpha))
            caption.color = color(Palette.TEXT.fade(alpha))
        }
    }

    /** One segment per choice; clicking one picks it. */
    private inner class ChoiceRow<E : Enum<E>>(option: Config.ChoiceOption<E>) : Row<Config.ChoiceOption<E>>(option) {
        private val font = Montserrat.BOLD.tracked(0.08f)

        private inner class Segment(val entry: E, val left: Float, val w: Float) {
            val caption = label(option.name(entry).uppercase(), font, 11f, 0).apply { position = CenterPositionConstraint() }
            val fill = roundedRectangle {
                position = FixedPositionConstraint(left, 0f)
                size = FixedSizeConstraint(this@Segment.w, SEGMENT_H)
                radius = CascadeGeometricRadius(5f)
                interact = false
                adopt(caption)
            }
            val hover = Fade { paint() }
        }

        private val segments: List<Segment>
        private val bar: RoundedRectanglePrimitive
        private var hovered: Segment? = null

        init {
            var x = 0f
            segments = option.entries.map { entry ->
                val w = font.width(option.name(entry).uppercase(), 11f) + SEGMENT_PAD * 2
                Segment(entry, x, w).also { x += w + 2f }
            }
            val total = x - 2f
            bar = roundedRectangle {
                size = FixedSizeConstraint(total, SEGMENT_H)
                radius = CascadeGeometricRadius(6f)
                segments.forEach { adopt(it.fill) }
            }.control(0f)
            bar.effect(OutlineEffect { color = color(0x29FFFFFF) })

            box.on<MouseEvent.Move.Any> {
                val over = segmentAt(x.toFloat(), y.toFloat())
                box.cursor = if (over != null) CursorTypes.POINTING_HAND else null
                if (over !== hovered) {
                    hovered?.hover?.to(0f, FAST)
                    over?.hover?.to(1f, FAST)
                    hovered = over
                }
            }
            box.on<MouseEvent.Move.Exit> {
                hovered?.hover?.to(0f, FAST)
                hovered = null
            }
            box.on<MouseEvent.Press> {
                if (button != InputConstants.MOUSE_BUTTON_LEFT) return@on
                val segment = segmentAt(x.toFloat(), y.toFloat()) ?: return@on
                minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
                option.value = segment.entry
                paint()
                refreshShown()
                cancel()
            }
        }

        private fun segmentAt(x: Float, y: Float): Segment? {
            if (y < bar.y || y > bar.y + SEGMENT_H) return null
            return segments.firstOrNull { x >= bar.x + it.left && x <= bar.x + it.left + it.w }
        }

        override fun paintControl(alpha: Float) {
            bar.color = color(0x14FFFFFF.fade(alpha))
            for (segment in segments) {
                val selected = segment.entry == option()
                val t = segment.hover.value
                val fill = if (selected) Palette.lerp(Palette.accent, Palette.accentHi, t * 0.4f) else Palette.lerp(0x00FFFFFF, 0x1FFFFFFF, t)
                segment.fill.color = color(fill.fade(alpha))
                segment.caption.color = color((if (selected) Palette.WHITE else Palette.lerp(Palette.MUTED, Palette.TEXT, t)).fade(alpha))
            }
        }
    }

    /** A swatch and the hex value; clicking opens a colour picker under it. */
    private inner class ColorRow(option: Config.ColorOption) : Row<Config.ColorOption>(option) {
        private val hex = label("", Montserrat.SEMIBOLD, 12.5f, 0).control(SWATCH_W + GAP)
        private val checkerImage = image {
            position = FixedPositionConstraint(0f, 0f)
            size = FixedSizeConstraint(SWATCH_W, SWATCH_H)
            interact = false
        }
        private val fill = roundedRectangle {
            position = FixedPositionConstraint(0f, 0f)
            size = FixedSizeConstraint(SWATCH_W, SWATCH_H)
            radius = CascadeGeometricRadius(5f)
            interact = false
        }
        private val swatch = roundedRectangle {
            size = FixedSizeConstraint(SWATCH_W, SWATCH_H)
            radius = CascadeGeometricRadius(5f)
            color = NONE
            adopt(checkerImage)
            adopt(fill)
        }.control(0f)
        private val swatchOutline = swatch.effect(OutlineEffect { })

        init {
            swatchCheckers += checkerImage
            if (initialized) checkerImage.location = checker(SWATCH_W, SWATCH_H, 5f, scene.scale)
            box.cursor = CursorTypes.POINTING_HAND
            box.onLeftClick { _, _ -> open() }
        }

        private fun open() {
            val start = option()
            val picker = ColorPicker(start.rgb, if (option.allowAlpha) start.alphaFloat else null, "OPACITY", ::draggable) { rgb, alpha ->
                option.value = Color(rgb or 0xFF000000.toInt()).withAlpha(alpha)
                paint()
            }
            // Under the row, right-aligned with the swatch; above it if it would run off the bottom
            val rowTop = LIST_Y + top - scrollY
            val below = rowTop + ROW_H + 4f
            val y = if (below + picker.height > PANEL_H + 10f) rowTop - picker.height - 4f else below
            openPicker(picker, ROW_X + ROW_W - ROW_PAD - ColorPicker.WIDTH, y)
        }

        override fun paintControl(alpha: Float) {
            val c = option()
            hex.setText("#" + c.hex(option.allowAlpha))
            hex.color = color(Palette.lerp(Palette.MUTED, Palette.TEXT, hover).fade(alpha))
            fill.color = color(c.rgba.fade(alpha))
            checkerImage.color = color(Palette.WHITE.fade(alpha))
            swatchOutline.color = color(Palette.lerp(0x4DFFFFFF, Palette.WHITE, hover).fade(alpha))
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
        items.forEach { it.paint() }
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
