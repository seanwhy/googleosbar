package com.meridian11.books.bentobar.bar

/**
 * When the status bar's colours are read, after something that can change them. No Android types, so
 * the cases the devices showed are unit-tested.
 *
 * The bar says nothing when its look changes, and it changes late: it fades, a window's bounds say
 * "maximized" before the bar turns black, and after an unlock it can keep the lock screen's light
 * icons for seconds. One reading soon after the cause can show the look from before, and a reading
 * that matches the last one is no proof that the change is over. Reading once, and once more only if
 * that differed, left the strip white after an unlock until the next change of windows.
 *
 * So every cause is followed by readings spread over the time the bar may take, each of them taken
 * whatever the one before showed, and a look that still differed at the last one is read again.
 *
 * A reading is a screenshot, and screenshots must stay few (see AGENTS.md, Play Protect). Readings are
 * tied to a cause and end; none is taken on a timer. Causes that keep coming are held to a budget:
 * after [BUSY] readings within a minute a cause's first reading waits two seconds behind the last
 * one, and what follows it comes later ([LATER_MS]), where the next cause of a stream takes its
 * place. A stream that goes on is held further: after a minute of it two readings keep ten seconds
 * apart, after five minutes a minute apart, until it stops. A slow cause's first reading is not held
 * by that: the strip back on screen is not the stream's doing, and a bar still in the lock screen's
 * look is what that reading is for, so it keeps only the two seconds. Nothing is dropped: the last
 * cause of a stream still gets its readings, and the distance Android asks for ([MERGE_MS]) stays.
 */
class ColorWatch {
    enum class Cause(internal val after: LongArray, internal val busy: LongArray) {
        /** A window settled against the bar or under it, or left; the wallpaper changed; or a fresh look is wanted: the bar follows within the second. */
        QUICK(longArrayOf(0, 1_000), longArrayOf(0, LATER_MS)),
        /**
         * The strip is back on screen (an unlock, a wake, a full-screen app or a panel gone), or the
         * theme or the display changed: the bar can take seconds, more after a sleep.
         */
        SLOW(longArrayOf(0, 750, 1_750, 3_250, 5_750, 9_750), longArrayOf(0, 3_250, 9_750)),
    }

    /** What a reading showed, against the colours the strip had. */
    enum class Result { SAME, CHANGED, NOTHING }

    private val slow = ArrayList<Long>()
    private val quick = ArrayList<Long>()
    private var again = 0
    private var nothing = 0
    /** When screenshots were asked for within the last minute, oldest first. */
    private val asked = ArrayDeque<Long>()
    /** The first reading a change of windows still waits for. */
    private var windowsDue: Long? = null
    /** When a change of windows last got its first reading. */
    private var windowsRead = -BarNeighbours.APART_MS
    /** The first reading a slow cause still waits for, which a long stream's gap doesn't hold back. */
    private var slowFirst: Long? = null
    /** The series still owed while the strip has nothing to draw ([pause]): SLOW, QUICK or none. */
    private var owed: Cause? = null
    /** When causes that kept coming began to be held back; null while no stream is. */
    private var streamSince: Long? = null
    /** When the last cause came. Causes that stop for [STREAM_OVER_MS] have stopped: the stream is forgotten. */
    private var lastCause: Long? = null

    /**
     * Something that can change the bar happened at [now]; the first reading is wanted [first] ms later.
     * A quick cause takes the place of the readings an earlier one still had to come (in a stream of
     * window changes only the last gets its second reading) and leaves a slow one's late readings
     * alone. A slow cause starts over.
     */
    fun cause(cause: Cause, now: Long, first: Long) {
        stream(now)
        val busy = busy(now)
        var start = now + first
        // Not on the heels of a screenshot just asked for: Android would refuse this one.
        val behind = when { !busy -> MERGE_MS; cause == Cause.SLOW -> BarNeighbours.APART_MS; else -> gap(now) }
        asked.lastOrNull()?.let { start = maxOf(start, it + behind) }
        val times = (if (busy) cause.busy else cause.after).map { start + it }
        quick.clear(); windowsDue = null
        if (cause == Cause.SLOW) { slow.clear(); slow += times; slowFirst = start } else quick += times
        again = 0
    }

    /**
     * A window settled against the bar or under it, or left, at [now]. A quick cause whose first
     * readings keep two seconds apart ([BarNeighbours.wait]): under a full-screen app every screen it
     * opens is another window under the bar. Only these first readings count for that: a window
     * maximized right after an unlock is not held back by the readings the unlock asked for.
     */
    fun windowsMoved(now: Long) {
        cause(Cause.QUICK, now, BarNeighbours.wait(now - windowsRead))
        windowsDue = quick.first()
    }

    /** Milliseconds from [now] to the next reading (0 when it is overdue), or null when none is to come. */
    fun due(now: Long): Long? {
        val next = (slow + quick).minOrNull() ?: return null
        // A long stream's gap holds for every reading of it, a cause's later ones and the follow-ups too; not for a slow cause's first.
        // The gap as it stood at the last cause: a stream that has stopped doesn't go on lengthening what its last cause waits for.
        val apart = gap(lastCause ?: now)
        val earliest = asked.lastOrNull()?.takeIf { streamSince != null && apart > BarNeighbours.APART_MS }?.plus(apart) ?: next
        val held = maxOf(next, earliest)
        return (minOf(held, slowFirst ?: held) - now).coerceAtLeast(0)
    }

    /**
     * A screenshot is asked for at [now]. It serves every reading that was due by now, or would be
     * within the moment, and from here on a cause knows that it is on its way.
     */
    fun taken(now: Long) {
        asked += now
        while (asked.first() <= now - MINUTE_MS) asked.removeFirst()
        slow.removeAll { it <= now + MERGE_MS }
        quick.removeAll { it <= now + MERGE_MS }
        windowsDue?.let { if (it <= now + MERGE_MS) { windowsRead = now; windowsDue = null } }
        slowFirst?.let { if (it <= now + MERGE_MS) slowFirst = null }
    }

    /**
     * The screenshot's answer came at [now]. True when it was the third in a row that gave nothing: the
     * strip then uses the theme and the wallpaper's hints rather than how the bar looked before, until
     * a reading shows something.
     */
    fun read(now: Long, result: Result): Boolean {
        val last = slow.isEmpty() && quick.isEmpty()
        when (result) {
            Result.SAME -> nothing = 0
            Result.CHANGED -> {
                nothing = 0
                // Caught halfway through a fade, perhaps: read again when it has settled.
                if (last && again < MAX_AGAIN) { again++; quick += now + if (busy(now)) LATER_MS else SETTLE_MS }
            }
            Result.NOTHING -> {
                nothing++
                if (last && nothing < GIVE_UP) quick += now + RETRY_MS
                return nothing == GIVE_UP
            }
        }
        return false
    }

    /** The strip left the screen: what was to be read no longer matters, and its return asks again. */
    fun clear() {
        slow.clear(); quick.clear(); windowsDue = null; slowFirst = null
        again = 0
        owed = null
    }

    /**
     * The strip is on screen with nothing to draw: no readings (an invisible window of ours reading the
     * screen is what Play Protect flags). The series still to come is owed until [resume].
     */
    fun pause() {
        owe(if (slow.isNotEmpty()) Cause.SLOW else if (quick.isNotEmpty()) Cause.QUICK else null)
        slow.clear(); quick.clear(); windowsDue = null; slowFirst = null
        again = 0
    }

    /** A [cause] while the strip has nothing to draw: it waits for [resume]; a slow one isn't replaced by a quick one. */
    fun owe(cause: Cause?) {
        if (cause != null && owed != Cause.SLOW) owed = cause
    }

    /** The strip has something to draw again at [now]: the owed series starts [first] ms later, two quick readings if none. */
    fun resume(now: Long, first: Long) {
        val cause = owed ?: Cause.QUICK
        owed = null
        cause(cause, now, first)
    }

    private fun busy(now: Long) = streamSince != null || asked.count { it > now - MINUTE_MS } >= BUSY

    /** A cause came at [now]: a stream that had stopped is forgotten, and causes past the budget begin one. */
    private fun stream(now: Long) {
        if (lastCause?.let { now - it >= STREAM_OVER_MS } == true) streamSince = null
        lastCause = now
        if (streamSince == null && asked.count { it > now - MINUTE_MS } >= BUSY) streamSince = now
    }

    /** How far apart readings keep while a stream lasts: two seconds, ten after a minute of it, a minute after five. */
    private fun gap(now: Long): Long {
        val lasted = now - (streamSince ?: return BarNeighbours.APART_MS)
        return when {
            lasted < TEN_S_AFTER_MS -> BarNeighbours.APART_MS
            lasted < MINUTE_AFTER_MS -> TEN_S_MS
            else -> MINUTE_MS
        }
    }

    companion object {
        /** What a reading that [seen] the bar (null: it gave nothing) is, against the colours the strip [had]. */
        fun result(had: Any?, seen: Any?): Result = when (seen) { null -> Result.NOTHING; had -> Result.SAME; else -> Result.CHANGED }

        /** Readings closer together than this are one reading; Android refuses a screenshot within a third of a second of the last. */
        private const val MERGE_MS = 400L
        /** A fade is over this long after a reading that caught it. */
        private const val SETTLE_MS = 800L
        private const val MAX_AGAIN = 3
        /** A reading that gave nothing (the bar caught fading, a screenshot refused) is tried again after this. */
        private const val RETRY_MS = 1_500L
        private const val GIVE_UP = 3
        /** This many readings within a minute, and causes are held back. An unlock and a few window changes stay under it. */
        private const val BUSY = 12
        private const val MINUTE_MS = 60_000L
        /** What follows a first reading while busy: long enough for the next cause of a stream to come first. */
        private const val LATER_MS = 4_000L
        /** How long a stream lasts before its readings keep [TEN_S_MS] and then a minute apart. */
        private const val TEN_S_AFTER_MS = 60_000L
        private const val MINUTE_AFTER_MS = 300_000L
        private const val TEN_S_MS = 10_000L
        /** A minute with no cause and the stream has stopped. */
        private const val STREAM_OVER_MS = 60_000L
    }
}
