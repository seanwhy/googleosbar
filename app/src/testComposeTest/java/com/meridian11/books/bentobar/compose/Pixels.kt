package com.meridian11.books.bentobar.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs

/**
 * Whether a drawn [pixel] is [color], to one step of each channel: a colour laid over another with an alpha (a veil
 * over a card) is rounded once by the palette's arithmetic and once by the canvas, and the two can differ by one.
 */
internal fun near(pixel: Int, color: Color): Boolean {
    val want = color.toArgb()
    return (0..24 step 8).all { abs(((pixel ushr it) and 0xFF) - ((want ushr it) and 0xFF)) <= 1 }
}
