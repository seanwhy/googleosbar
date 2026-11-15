package com.meridian11.books.bentobar.items

import kotlin.math.max
import kotlin.math.min

/** The Mirror's rules, pure: which camera, at what size, how wide the popup, and how the picture fills its box. */
object MirrorRules {
    /** A camera as Android lists it: [front] faces the user, [external] is plugged in (a USB camera). */
    data class Cam(val id: String, val front: Boolean, val external: Boolean)

    /** The camera that shows the user: the front one, else one plugged in, else the first there is; null for none. */
    fun pick(cams: List<Cam>): String? = (cams.firstOrNull { it.front } ?: cams.firstOrNull { it.external } ?: cams.firstOrNull())?.id

    /** The longest and shortest side of a picture no larger than 720p: a mirror in a popup needs no more, and costs less. */
    private const val LONG = 1280
    private const val SHORT = 720

    /**
     * The preview's size among the camera's [choices]: the largest of 16:9 or 4:3 not above 1280×720 (either way up);
     * else the smallest there is; null for none.
     */
    fun size(choices: List<Pair<Int, Int>>): Pair<Int, Int>? {
        fun area(s: Pair<Int, Int>) = s.first.toLong() * s.second
        val fits = choices.filter { (w, h) ->
            val long = max(w, h)
            val short = min(w, h)
            long <= LONG && short <= SHORT && (long * 9 == short * 16 || long * 3 == short * 4)
        }
        return fits.maxByOrNull(::area) ?: choices.minByOrNull(::area)
    }

    /** The preview's width in dp for the item's "size" option: "s", "m" (the default) or "l". */
    fun widthDp(size: String): Int = when (size) {
        "s" -> 240
        "l" -> 420
        else -> 320
    }

    /**
     * How much a picture of [width]×[height] (as it stands upright on the screen) is scaled, across and down, beyond being
     * stretched over a box of [boxWidth]×[boxHeight] about the box's middle, so that it fills the box in its own shape: what
     * sticks out is cut, nothing is squeezed. 1 and 1 for a picture of the box's shape, or one not measured yet.
     */
    fun fill(width: Int, height: Int, boxWidth: Float, boxHeight: Float): Pair<Float, Float> {
        if (width <= 0 || height <= 0 || boxWidth <= 0f || boxHeight <= 0f) return 1f to 1f
        val scale = max(boxWidth / width, boxHeight / height)
        return width * scale / boxWidth to height * scale / boxHeight
    }
}
