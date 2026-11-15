package com.meridian11.books.bentobar.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import com.meridian11.books.bentobar.data.Store

/**
 * BentoBar's own motion, read in one place: the popups, the strip's highlight, items appearing, a drag. "No
 * animations" (Look) stops all of it, as the system's "Remove animations" does: popups are whole at once and gone at
 * once, the highlight jumps. Tolerances (a grace after the pointer leaves, a guard against a double click) stay.
 */
object Motion {
    /**
     * The settings window's times, in milliseconds, as its design gives them. Things travel there and don't fade: a
     * fade only goes with a move. Every one of them is run through [spec], and Compose stretches it by the system's
     * animator scale, so "No animations" and the system's "Remove animations" both make it happen at once.
     */
    /** A row picked up rises and takes its shadow; over the preview it becomes the item's chip, and a chip a row. A switch's knob. */
    const val LIFT = 120
    /** The rows around a move make room; a segment's raised part and the tabs' underline travel. */
    const val NEIGHBOURS = 160
    /** A dropped row settles; the panel's content slides in from the side it turned to. */
    const val SETTLE = 180
    /** A row that was not dropped anywhere travels back where it came from. */
    const val BACK = 200
    /** An area starts or closes: the rows travel to their lanes, or back into the one list. */
    const val AREA = 200
    /**
     * A note with Undo (a removed row's slip, a closed or removed area's, the library's "Added", Look's line) stands
     * this long, then only the note goes: the change can still be undone. [NoteClock] says what holds it meanwhile.
     */
    const val NOTE = 3000

    /** "No animations" is set. */
    val off: Boolean get() = Store.config.value.noAnimations

    /** The scale every duration is stretched by: 0 (nothing moves) with "No animations" or the system's Remove animations. */
    fun scale(context: Context): Float = if (off) 0f else runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

    /** [spec], or straight to the end with "No animations". (The system's own scale Compose applies by itself.) */
    fun <T> spec(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = if (off) snap() else spec
}
