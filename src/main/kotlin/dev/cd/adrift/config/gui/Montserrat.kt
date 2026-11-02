package dev.cd.adrift.config.gui

import foo.starred.cascade.graphics.font.data.font.impl.TtfFontData
import foo.starred.cascade.graphics.font.rendering.impl.FontRenderer
import foo.starred.cascade.wrappers.text.base.ITextWrapper
import foo.starred.cascade.wrappers.text.data.CascadeTextWrapperData
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.util.FormattedCharSequence

/**
 * Montserrat (bundled, OFL) in one weight, for Cascade text. Cascade can't space letters, so with [tracking]
 * (extra space after each letter, in em, like CSS `letter-spacing`) glyphs are drawn one at a time.
 * Like [foo.starred.cascade.wrappers.text.impl.CascadeTextWrapper], the height is the ascender only.
 */
class Montserrat private constructor(private val font: Lazy<FontRenderer>, private val tracking: Float) : ITextWrapper {
    fun tracked(em: Float) = Montserrat(font, em)

    override fun width(text: FormattedCharSequence, size: Float): Float {
        if (tracking == 0f) return font.value.width(text, size)
        var width = 0f
        text.accept { _, _, codepoint ->
            width += font.value.width(Character.toString(codepoint), size) + tracking * size
            true
        }
        return width
    }

    override fun height(size: Float) = font.value.regular.height * size

    override fun render(graphics: GuiGraphicsExtractor, data: CascadeTextWrapperData) {
        val text = data.text
        val size = data.size
        var x = if (data.center) data.x + (data.width - width(text, size)) / 2f else data.x
        if (tracking == 0f) {
            font.value.extract(graphics, text, x, data.y, data.color, data.shadow, size)
            return
        }
        text.accept { _, _, codepoint ->
            val glyph = Character.toString(codepoint)
            font.value.extract(graphics, glyph, x, data.y, data.color, data.shadow, size)
            x += font.value.width(glyph, size) + tracking * size
            true
        }
    }

    companion object {
        // Loaded on first use: the glyph atlas is a texture, so it has to be made on the render thread
        private fun load(weight: String) = Montserrat(lazy {
            val data = TtfFontData(Montserrat::class.java.getResourceAsStream("/assets/adrift/fonts/Montserrat-$weight.ttf")!!)
            FontRenderer(data, data)
        }, 0f)

        val MEDIUM = load("Medium")
        val SEMIBOLD = load("SemiBold")
        val BOLD = load("Bold")
        val EXTRABOLD = load("ExtraBold")
    }
}
