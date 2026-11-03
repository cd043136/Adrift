package dev.cd.adrift.config.gui

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.cd.adrift.Adrift
import dev.cd.adrift.config.gui.Palette.withAlpha
import foo.starred.cascade.constraints.impl.data.PositionAlignment
import foo.starred.cascade.constraints.impl.position.AlignPositionConstraint
import foo.starred.cascade.constraints.impl.position.FixedPositionConstraint
import foo.starred.cascade.constraints.impl.size.FixedSizeConstraint
import foo.starred.cascade.effects.impl.DropShadowEffect
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
import foo.starred.cascade.wrappers.svg.impl.CascadeSVG
import kotlin.math.ceil

/**
 * A colour popup: a saturation/value square, a hue bar, an optional bar for opacity and a hex field, editing a
 * colour live. Sizes are the mockup's CSS pixels, like the config screen. Used for colour options and for the
 * menu's own theme (where the opacity bar is the menu's opacity).
 *
 * @param rgb starting colour, `0xRRGGBB`
 * @param alpha starting opacity, or null for no opacity bar
 * @param alphaLabel caption under the opacity bar
 * @param draggable makes an element call its function with the mouse position (in scene coordinates) on press and
 * while dragged
 * @param onChange called after every change with the colour (`0xRRGGBB`) and opacity
 */
class ColorPicker(
    rgb: Int,
    alpha: Float?,
    private val alphaLabel: String,
    private val draggable: (IPrimitiveElement<*>, (Float, Float) -> Unit) -> Unit,
    private val onChange: (rgb: Int, alpha: Float) -> Unit,
) {
    companion object {
        const val WIDTH = 244f
        private const val PAD = 12f
        private const val INNER = WIDTH - PAD * 2
        private const val SV_H = 140f
        private const val BAR_H = 12f
        private const val KNOB = 16f
        private const val HUE_Y = PAD + SV_H + 12f
        private const val ALPHA_Y = HUE_Y + BAR_H + 12f
        private const val CAPTION_Y = ALPHA_Y + BAR_H + 6f
        private const val FIELD_H = 32f
        private const val SWATCH_W = 36f
        private const val MAX_HEX = 9
        private val NONE = CascadeGeometricColor.TRANSPARENT

        /** Height with or without the opacity bar. */
        fun height(withAlpha: Boolean) = if (withAlpha) 274f else HUE_Y + BAR_H + 12f + FIELD_H + PAD

        private fun color(argb: Int) = CascadeGeometricColor(argb)
    }

    private val hasAlpha = alpha != null
    /** Not called height: inside the element builders that would be the element's own height. */
    val popupHeight = height(hasAlpha)
    private val rowY = popupHeight - PAD - FIELD_H

    private var hue: Float
    private var saturation: Float
    private var value: Float
    private var alpha = alpha ?: 1f
    private var hex = ""
    private var editing = false

    /** GUI units per scene unit, for rasterizing the checkerboards sharply. */
    private var sceneScale = 1f

    init {
        val (h, s, v) = Palette.hsv(rgb)
        hue = h
        saturation = s
        value = v
    }

    private lateinit var svBase: RoundedRectanglePrimitive
    private lateinit var svKnob: Knob
    private lateinit var hueKnob: Knob
    private var alphaFill: RoundedRectanglePrimitive? = null
    private var alphaKnob: Knob? = null
    private var alphaChecker: ImagePrimitive? = null
    private lateinit var swatchFill: RoundedRectanglePrimitive
    private lateinit var swatchChecker: ImagePrimitive
    private val hexText = label("", Montserrat.SEMIBOLD, 12.5f, Palette.TEXT)
    private lateinit var fieldOutline: OutlineEffect
    private lateinit var field: RoundedRectanglePrimitive

    /** Knob: a white ring with a thin dark edge, filled with the colour under it. */
    private class Knob(parent: RoundedRectanglePrimitive) {
        val edge = roundedRectangle {
            size = FixedSizeConstraint(KNOB + 2f, KNOB + 2f)
            radius = CascadeGeometricRadius(KNOB / 2f + 1f)
            color = color(0x8C000000.toInt())
            interact = false
            effect(DropShadowEffect { offset = CascadeGeometricOffset(0f, 2f); blur = 4f; color = color(0x66000000) })
        }
        val fill = roundedRectangle {
            position = FixedPositionConstraint(3f, 3f)
            size = FixedSizeConstraint(KNOB - 4f, KNOB - 4f)
            radius = CascadeGeometricRadius(KNOB / 2f - 2f)
            interact = false
        }

        init {
            edge.adopt(roundedRectangle {
                position = FixedPositionConstraint(1f, 1f)
                size = FixedSizeConstraint(KNOB, KNOB)
                radius = CascadeGeometricRadius(KNOB / 2f)
                color = CascadeGeometricColor.WHITE
                interact = false
            })
            edge.adopt(fill)
            parent.adopt(edge)
        }

        /** Centres it on ([x], [y]) of its parent. */
        fun place(x: Float, y: Float, rgb: Int) {
            edge.position = FixedPositionConstraint(x - KNOB / 2f - 1f, y - KNOB / 2f - 1f)
            fill.color = color(rgb or 0xFF000000.toInt())
        }
    }

    val root: RoundedRectanglePrimitive = roundedRectangle {
        size = FixedSizeConstraint(WIDTH, popupHeight)
        radius = CascadeGeometricRadius(10f)
        color = color(Palette.POPUP)
        effect(DropShadowEffect { offset = CascadeGeometricOffset(0f, 14f); blur = 40f; color = color(0x99000000.toInt()) })
        effect(OutlineEffect { color = color(0x33FFFFFF) })
        // Swallows clicks between the controls, which would otherwise close it
        on<MouseEvent.Press> { cancel() }

        val sv = roundedRectangle {
            position = FixedPositionConstraint(PAD, PAD)
            size = FixedSizeConstraint(INNER, SV_H)
            radius = CascadeGeometricRadius(6f)
            color = NONE
            cursor = CursorTypes.CROSSHAIR
            svBase = layer(CascadeGeometricColor.WHITE)
            layer(CascadeGeometricColor.horizontal(Palette.WHITE, 0x00FFFFFF))
            layer(CascadeGeometricColor.vertical(0x00000000, 0xFF000000.toInt()))
        }
        adopt(sv)
        svKnob = Knob(sv)
        draggable(sv) { x, y ->
            saturation = ((x - sv.x) / INNER).coerceIn(0f, 1f)
            value = 1f - ((y - sv.y) / SV_H).coerceIn(0f, 1f)
            changed()
        }

        val hueBar = roundedRectangle {
            position = FixedPositionConstraint(PAD, HUE_Y)
            size = FixedSizeConstraint(INNER, BAR_H)
            radius = CascadeGeometricRadius(BAR_H / 2f)
            color = NONE
            cursor = CursorTypes.POINTING_HAND
            val segment = INNER / 6f
            for (i in 0 until 6) {
                val r = BAR_H / 2f
                adopt(roundedRectangle {
                    position = FixedPositionConstraint(i * segment, 0f)
                    // A hair wider so the seams don't show
                    size = FixedSizeConstraint(segment + if (i < 5) 0.5f else 0f, BAR_H)
                    radius = CascadeGeometricRadius(if (i == 0) r else 0f, if (i == 5) r else 0f, if (i == 0) r else 0f, if (i == 5) r else 0f)
                    color = CascadeGeometricColor.horizontal(
                        Palette.rgb(i * 60f, 1f, 1f) or 0xFF000000.toInt(),
                        Palette.rgb((i + 1) * 60f % 360f, 1f, 1f) or 0xFF000000.toInt(),
                    )
                    interact = false
                })
            }
        }
        adopt(hueBar)
        hueKnob = Knob(hueBar)
        draggable(hueBar) { x, _ ->
            hue = ((x - hueBar.x) / INNER).coerceIn(0f, 1f) * 360f
            changed()
        }

        if (hasAlpha) {
            val alphaBar = roundedRectangle {
                position = FixedPositionConstraint(PAD, ALPHA_Y)
                size = FixedSizeConstraint(INNER, BAR_H)
                radius = CascadeGeometricRadius(BAR_H / 2f)
                color = NONE
                cursor = CursorTypes.POINTING_HAND
                alphaChecker = image {
                    position = FixedPositionConstraint(0f, 0f)
                    size = FixedSizeConstraint(INNER, BAR_H)
                    interact = false
                }.also(::adopt)
                alphaFill = layer(NONE)
            }
            adopt(alphaBar)
            alphaKnob = Knob(alphaBar)
            // The knob stays inside the bar at both ends
            draggable(alphaBar) { x, _ ->
                this@ColorPicker.alpha = ((x - alphaBar.x - KNOB / 2f) / (INNER - KNOB)).coerceIn(0f, 1f)
                changed()
            }

            adopt(label(alphaLabel, Montserrat.SEMIBOLD.tracked(0.14f), 10f, Palette.MUTED).apply {
                position = FixedPositionConstraint(PAD, textTop(CAPTION_Y, 10f))
            })
        }

        val swatch = roundedRectangle {
            position = FixedPositionConstraint(PAD, rowY)
            size = FixedSizeConstraint(SWATCH_W, FIELD_H)
            radius = CascadeGeometricRadius(6f)
            color = NONE
            swatchChecker = image {
                position = FixedPositionConstraint(0f, 0f)
                size = FixedSizeConstraint(SWATCH_W, FIELD_H)
                interact = false
            }
            adopt(swatchChecker)
            interact = false
        }
        swatchFill = swatch.layer(NONE)
        swatch.effect(OutlineEffect { color = color(0x4DFFFFFF) })
        adopt(swatch)

        field = roundedRectangle {
            position = FixedPositionConstraint(PAD + SWATCH_W + 8f, rowY)
            size = FixedSizeConstraint(INNER - SWATCH_W - 8f, FIELD_H)
            radius = CascadeGeometricRadius(6f)
            cursor = CursorTypes.IBEAM
            fieldOutline = effect(OutlineEffect { color = color(0x2EFFFFFF) })
            hexText.position = AlignPositionConstraint(PositionAlignment.START, PositionAlignment.CENTER)
            hexText.offset = CascadeGeometricOffset(11f, 0f)
            adopt(hexText)
            val box = this

            // Pressing focuses it; keys then go to the focused element
            on<MouseEvent.Press> { cancel() }
            on<FocusEvent.Gain> { editing = true; textInput(box, true); paintField() }
            on<FocusEvent.Lose> { editing = false; textInput(box, false); hex = format(); paintField() }
            on<KeyEvent.Type> {
                val c = char.uppercaseChar()
                if ((c !in '0'..'9' && c !in 'A'..'F' && c != '#') || hex.length >= MAX_HEX) return@on
                hex += c
                typed()
                cancel()
            }
            on<KeyEvent.Press> {
                if (key != InputConstants.KEY_BACKSPACE || hex.isEmpty()) return@on
                hex = hex.dropLast(1)
                typed()
                cancel()
            }
        }
        adopt(field)
    }

    init {
        hex = format()
        sync()
    }

    /** A layer covering this (which must have a fixed size), for stacking gradients. */
    private fun RoundedRectanglePrimitive.layer(color: CascadeGeometricColor): RoundedRectanglePrimitive {
        val parentSize = size as FixedSizeConstraint
        return roundedRectangle {
            position = FixedPositionConstraint(0f, 0f)
            size = FixedSizeConstraint(parentSize.width, parentSize.height)
            radius = this@layer.radius
            this.color = color
            interact = false
        }.also(::adopt)
    }

    private fun format() = "#" + Palette.hex(Palette.rgb(hue, saturation, value)) + if (hasAlpha) "%02X".format(Math.round(alpha * 255)) else ""

    /** Applies [hex] once it's a whole colour: 3, 4, 6 or 8 digits, the last of 4 or 8 being the opacity. */
    private fun typed() {
        var digits = hex.removePrefix("#")
        if (digits.length == 3 || digits.length == 4) digits = digits.map { "$it$it" }.joinToString("")
        if (digits.length == 6 || digits.length == 8) {
            val (h, s, v) = Palette.hsv(digits.take(6).toInt(16))
            // Greys have no hue, so keep the one the square is showing
            if (s > 0f) hue = h
            saturation = s
            value = v
            if (digits.length == 8 && hasAlpha) alpha = digits.substring(6).toInt(16) / 255f
            changed()
        }
        paintField()
    }

    private fun changed() {
        if (!editing) hex = format()
        sync()
        onChange(Palette.rgb(hue, saturation, value), alpha)
    }

    private fun sync() {
        val rgb = Palette.rgb(hue, saturation, value)
        val pure = Palette.rgb(hue, 1f, 1f)
        svBase.color = color(pure or 0xFF000000.toInt())
        svKnob.place(saturation * INNER, (1f - value) * SV_H, rgb)
        hueKnob.place(hue / 360f * INNER, BAR_H / 2f, pure)
        alphaFill?.color = CascadeGeometricColor.horizontal(rgb, rgb or 0xFF000000.toInt())
        alphaKnob?.place(KNOB / 2f + alpha * (INNER - KNOB), BAR_H / 2f, rgb)
        swatchFill.color = color(rgb.withAlpha(alpha))
        paintField()
    }

    private fun paintField() {
        fieldOutline.color = color(if (editing) Palette.accent else 0x2EFFFFFF)
        field.color = color(if (editing) 0x80000000.toInt() else 0x59000000)
        hexText.setText(if (editing) "$hex|" else hex)
    }

    /** The checkerboards are SVGs rasterized at the size they're drawn, so call this when the scene scale changes. */
    fun rasterize(scale: Float) {
        sceneScale = scale
        alphaChecker?.location = checker(INNER, BAR_H, BAR_H / 2f, sceneScale)
        swatchChecker.location = checker(SWATCH_W, FIELD_H, 6f, sceneScale)
    }
}

// Light squares drawn over one solid base rather than a <pattern>: pattern tiles leave
// anti-aliased seams at fractional scales that let the background show through as dark lines
fun checker(w: Float, h: Float, r: Float, scale: Float) = CascadeSVG.load(
    buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h"><defs><clipPath id="c"><rect width="$w" height="$h" rx="$r"/></clipPath></defs>""")
        append("""<g clip-path="url(#c)"><rect width="$w" height="$h" fill="#8a8a8a"/>""")
        for (y in 0 until ceil(h / 4f).toInt()) for (x in 0 until ceil(w / 4f).toInt()) {
            if ((x + y) % 2 == 0) append("""<rect x="${x * 4}" y="${y * 4}" width="4" height="4" fill="#c4c4c4"/>""")
        }
        append("</g></svg>")
    },
    Adrift.id("config/checker_${w.toInt()}x${h.toInt()}"),
    ceil(w * scale).toInt(),
    ceil(h * scale).toInt(),
)
