package com.meridian11.books.bentobar.bar

/**
 * The arithmetic of the slider in the bar, apart from its drawing and its pointer events so that it
 * is unit-tested on the JVM (`SliderMathTest`). Pure Kotlin. A level is 0 to 1; a slider with steps
 * (15 for a volume of 0 to 15) only ever stands on k / steps. The route line is the same line with a
 * plane on it and no pointer: where its parts stand is [route].
 */
object SliderMath {
    /**
     * The level for a pointer at [x] over a track that starts at [left] and is [width] wide (all in
     * the same pixels): how far along it is, kept within the track (a pointer past either end, or
     * past the bar, means that end), counted from the other end when the layout runs right to left,
     * and moved to the nearest of [steps] steps.
     */
    fun level(x: Float, left: Float, width: Float, steps: Int, rtl: Boolean = false): Float {
        if (width <= 0f) return 0f
        val along = ((x - left) / width).coerceIn(0f, 1f)
        return snap(if (rtl) 1f - along else along, steps)
    }

    /** [level] kept within 0 to 1 and, with [steps] > 0, moved to the nearest step. */
    fun snap(level: Float, steps: Int): Float {
        val l = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
        return if (steps <= 0) l else Math.round(l * steps).toFloat() / steps
    }

    /**
     * The whole number a settings slider stands on: the nearest, since a stop can come in a hair under its number
     * (6.9999995 for 7), where turning it into an Int would give one less.
     */
    fun stop(value: Float): Int = Math.round(value)

    /** How many steps a settings slider over [range] needs for every whole number in it to be a stop. */
    fun stops(range: IntRange): Int = (range.last - range.first - 1).coerceAtLeast(0)

    /** Which step [level] stands for, 0 to [steps]: what an item hands to the system (a volume index). */
    fun step(level: Float, steps: Int): Int = if (steps <= 0) 0 else Math.round(snap(level, steps) * steps)

    /**
     * [level], but never nothing: a click or a tap sets one step at the least (a slip at the track's
     * very start must not turn the sound off; a drag may). Without steps the least is a 64th, one dp
     * of the track.
     */
    fun atLeastOneStep(level: Float, steps: Int): Float = maxOf(snap(level, steps), if (steps > 0) 1f / steps else 1f / 64f)

    /**
     * How much of a track [track] wide is drawn filled at [level]: nothing at 0, and otherwise at
     * least [min] (a dot), so that the lowest step can be told from none.
     */
    fun fillWidth(level: Float, track: Float, min: Float): Float {
        val l = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
        return if (l <= 0f || track <= 0f) 0f else (l * track).coerceIn(minOf(min, track), track)
    }

    /** Where the handle's middle stands along the track: on the level, but [edge] in from either end, so it never hangs over. */
    fun handleCenter(level: Float, track: Float, edge: Float): Float {
        val l = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
        return if (track <= 2 * edge) track / 2 else (l * track).coerceIn(edge, track - edge)
    }

    /**
     * A route line as the canvas draws it, in its own pixels from its left edge. [center]: the middle
     * of the plane. [flown], [ahead]: the part of the line behind the plane and the part before it,
     * each from its left edge to its right; null: there is no such part.
     */
    class Route(val center: Float, val flown: ClosedFloatingPointRange<Float>?, val ahead: ClosedFloatingPointRange<Float>?)

    /**
     * The route line an item can have in its icon's place, which is the slider's line with a plane on
     * it: for a line [track] wide and a plane [plane] long that has [share] of its way behind it (0 to
     * 1), where the plane stands and where the two parts of the line are. The plane keeps within the
     * line: its middle is half its length in at the start, and as far from the end when it has arrived.
     * The part flown ends [gap] behind the plane and the part ahead begins [gap] past its nose, so the
     * plane stands in a clearing; a part that has no room is not there. On a line shorter than the
     * plane there is no way to go: it stands in the middle. Right to left ([rtl]) the line starts at
     * the right, and what comes back is mirrored already. Null: a line nobody can draw on.
     */
    fun route(track: Float, share: Float, plane: Float, gap: Float, rtl: Boolean = false): Route? {
        // (Written so that a width that is not a number comes out as nothing too.)
        if (!(track > 0f)) return null
        val s = if (share.isNaN()) 0f else share.coerceIn(0f, 1f)
        val center = if (track <= plane) track / 2 else plane / 2 + s * (track - plane)
        fun part(from: Float, to: Float) = if (to <= from) null else if (rtl) (track - to)..(track - from) else from..to
        return Route(if (rtl) track - center else center, part(0f, center - plane / 2 - gap), part(center + plane / 2 + gap, track))
    }
}

/**
 * One press on the slider, from the pointer going down to its letting go: which levels the slider
 * reports on the way and how the press ends. Apart from the pointer events, so that the rules are
 * unit-tested (`SliderGestureTest`). Levels come in as they are under the pointer, 0 to 1.
 *
 * A mouse, a touchpad or a stylus follows from the press on. A finger first has to say what it is
 * about: a tap sets the level when it lifts, a move past the touch slop makes the level follow, a
 * long hold asks for the item's menu when it is that long ([held]). A click or a tap never sets nothing at all (its lowest level
 * is one step): only a pointer that has really moved to another level can, so a click that
 * trembles at the track's start is still a click.
 */
class SliderGesture(private val steps: Int) {
    /** How a press ended. */
    sealed interface End {
        /** The slider stays at [level]: report it once more, as the last word. */
        data class Level(val level: Float) : End
        /** A long hold that never followed: the item's menu, and nothing is set. */
        data object Menu : End
        /** Nothing was set and nothing is: a finger that lifted somewhere else, a touch that was taken away. */
        data object None : End
    }

    /** The pointer is down. */
    var down = false; private set
    /** The level follows the pointer. */
    var following = false; private set
    /** A finger is down that has not said yet what it is about: a hold is being waited for. */
    val holding: Boolean get() = down && !following && !strayed
    private var moved = false
    /** The finger went off the slider or across it, past the slop or out of its zone: it is no hold any more, and it may still lift as a tap. */
    private var strayed = false
    /** The level under the pointer when it was last looked at, on its step; NaN: not looked at yet. */
    private var under = Float.NaN
    /** The last level reported; NaN: none yet. */
    private var said = Float.NaN

    /**
     * The pointer went down over [level]. [follows]: it is not a finger, so the level is set at once.
     * Returns the level to report, or null (a finger: nothing yet).
     */
    fun press(level: Float, follows: Boolean): Float? {
        down = true; moved = false; following = follows; strayed = false; said = Float.NaN
        under = if (follows) SliderMath.snap(level, steps) else Float.NaN
        return if (follows) say(SliderMath.atLeastOneStep(level, steps)) else null
    }

    /**
     * The pointer moved and is over [level] now. [pastSlop]: it is far enough along the track from where
     * it went down for a finger to mean it. [strayed]: it has gone off the track past the slop, or out of
     * the slider's zone, so a finger is no longer held still. Returns a level to report, or null: only a
     * new level is news.
     */
    fun move(level: Float, pastSlop: Boolean, strayed: Boolean = false): Float? {
        if (!down) return null
        if (strayed) this.strayed = true
        if (!following && pastSlop) following = true
        if (!following) return null
        val l = SliderMath.snap(level, steps)
        if (l == under) return null
        under = l
        moved = true
        return if (l == said) null else say(l)
    }

    /**
     * The pointer let go over [level]. [inside]: it is still on the slider; it only matters for a finger that never followed.
     */
    fun release(level: Float, inside: Boolean): End {
        if (!down) return End.None
        val followed = following
        down = false; following = false
        return when {
            // A drag ends where it is let go; a click ends on the level it set when the button went down.
            followed -> if (moved || said.isNaN()) End.Level(SliderMath.snap(level, steps)) else End.Level(said)
            !inside -> End.None
            else -> End.Level(SliderMath.atLeastOneStep(level, steps))
        }
    }

    /** A finger stayed down for the system's long-press time without saying more: the item's menu, now, and the press is over. Nothing is set. */
    fun held(): End {
        if (!holding) return End.None
        down = false
        return End.Menu
    }

    /**
     * The press was taken away: the strip went, the item left it, the system took the touch. What was
     * set stays and is the last word; a finger that had set nothing sets nothing.
     */
    fun cancel(): End {
        if (!down) return End.None
        val end = if (following && !said.isNaN()) End.Level(said) else End.None
        down = false; following = false
        return end
    }

    private fun say(level: Float): Float { said = level; return level }
}
