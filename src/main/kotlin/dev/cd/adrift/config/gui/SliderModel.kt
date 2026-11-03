package dev.cd.adrift.config.gui

import dev.cd.adrift.config.Config
import kotlin.math.roundToInt

/** A slider's value as a fraction of its range, and how it reads. */
interface SliderModel {
    var fraction: Float
    val text: String
}

fun intSlider(option: Config.IntOption) = object : SliderModel {
    override var fraction: Float
        get() = (option() - option.min).toFloat() / (option.max - option.min)
        set(value) {
            option.value = option.min + (value * (option.max - option.min)).roundToInt()
        }
    override val text get() = "${option()}${option.suffix}"
}

fun doubleSlider(option: Config.DoubleOption) = object : SliderModel {
    override var fraction: Float
        get() = ((option() - option.min) / (option.max - option.min)).toFloat()
        set(value) {
            option.value = option.min + value * (option.max - option.min)
        }
    override val text get() = "${option.format()}${option.suffix}"
}
