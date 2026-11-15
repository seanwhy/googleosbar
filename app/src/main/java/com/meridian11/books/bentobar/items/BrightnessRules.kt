package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.util.Sym
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Brightness without Android (`BrightnessRulesTest`). Android's setting is a value of [MIN] to [MAX]; the slider's level
 * (0 to 1) is its square root, so the slider feels even: the eye tells the dim values apart far better than the bright
 * ones, and on a straight line they would all crowd its first few millimetres.
 */
object BrightnessRules {
    const val MAX = 255
    const val MIN = 1

    /** Where the slider stands for [value]. */
    fun level(value: Int): Float = sqrt((value.coerceIn(MIN, MAX) - MIN).toFloat() / (MAX - MIN))

    /** The value a slider at [level] stands for: what is written to Android. */
    fun value(level: Float): Int {
        val l = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
        return (MIN + ((MAX - MIN) * l * l).roundToInt()).coerceIn(MIN, MAX)
    }

    /** A notch of the wheel is 5 percent of the slider, up or down. */
    fun wheel(level: Float, steps: Int): Float = (level + 0.05f * steps).coerceIn(0f, 1f)

    /** The percent the item says: the slider's place. */
    fun percent(level: Float): Int = (level.coerceIn(0f, 1f) * 100).roundToInt()

    fun icon(level: Float, auto: Boolean): String = when {
        auto -> Sym.BRIGHTNESS_AUTO
        level < 0.34f -> Sym.BRIGHTNESS_LOW
        level < 0.67f -> Sym.BRIGHTNESS_MEDIUM
        else -> Sym.BRIGHTNESS_HIGH
    }
}
