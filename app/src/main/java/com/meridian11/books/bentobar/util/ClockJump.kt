package com.meridian11.books.bentobar.util

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * A wall-clock time remembered with the real time that had passed since boot when it was taken, so
 * a deadline can tell the clock being changed (by the user, or a time zone fix) from time passing.
 * Timers and keep awake store wall times, which survive a restart; this keeps them from finishing
 * early or running long when someone sets the clock.
 */
data class ClockAnchor(val wall: Long, val elapsed: Long, val boot: Int) {
    /**
     * How far the wall clock moved beyond the real time that passed since this anchor: shift a
     * wall-clock deadline by it to keep the same duration. 0 for under 2 s (ordinary network time
     * corrections) and across a reboot, where the wall clock is the only reference left.
     */
    fun jump(now: ClockAnchor): Long {
        if (boot != now.boot || boot < 0) return 0
        val moved = (now.wall - wall) - (now.elapsed - elapsed)
        return if (kotlin.math.abs(moved) < 2_000) 0 else moved
    }

    /** Real time from [now] to [deadline], a wall-clock time set at this anchor, whatever the clock did in between. */
    fun left(deadline: Long, now: ClockAnchor): Long = deadline + jump(now) - now.wall

    companion object {
        private var bootCount: Int? = null

        /** The boot count can't change while this process lives, so it's read once. */
        private fun boot(context: Context): Int = bootCount
            ?: runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1).also { bootCount = it }

        fun now(context: Context) = ClockAnchor(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot(context))
    }
}
