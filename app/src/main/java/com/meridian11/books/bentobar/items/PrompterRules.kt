package com.meridian11.books.bentobar.items

/**
 * The prompter's rules, pure: how fast its script moves up, how large it is written, and where it stands while it
 * plays, pauses and ends. Places are dp from the script's top, times milliseconds on one clock (the popup's).
 */
object PrompterRules {
    /** The longest script an item keeps, in characters. */
    const val MOST_CHARS = 20_000

    /** How high the window the script moves in is, in dp. */
    const val WINDOW_DP = 220

    /** How fast it moves, 1 (slowest) to 10. */
    val speed = IntOpt("speed", 4, 1..10)

    /** How large it is written: "s", "m" or "l". */
    val size = Opt.oneOf("size", "m", "s", "m", "l")

    /** dp a second at [speed]: a slow reader's pace at 1, a fast speaker's at 10. */
    fun dpPerSecond(speed: Int): Float = 6f + 6f * speed.coerceIn(1, 10)

    fun textSp(size: String): Int = when (size) { "s" -> 18; "l" -> 32; else -> 24 }

    /**
     * Where the script stands: [offsetDp] up from its start, and while [playing] moving on from there since [startedAt].
     * What is kept is where it was when it last started or stopped, so a pause never loses the place.
     */
    data class Run(val playing: Boolean = false, val startedAt: Long = 0L, val offsetDp: Float = 0f)

    /** How far up the script is at [now]. */
    fun offset(run: Run, speed: Int, now: Long): Float =
        if (!run.playing) run.offsetDp else run.offsetDp + dpPerSecond(speed) * (now - run.startedAt).coerceAtLeast(0L) / 1000f

    /**
     * Plays from where it stands. Standing at its end ([endDp], when known), it plays from the start again: a press of
     * play there means "once more", and there is nowhere further to go.
     */
    fun play(run: Run, now: Long, endDp: Float? = null): Run = when {
        run.playing -> run
        endDp != null && ended(run.offsetDp, endDp) -> Run(playing = true, startedAt = now, offsetDp = 0f)
        else -> run.copy(playing = true, startedAt = now)
    }

    fun pause(run: Run, speed: Int, now: Long): Run = if (!run.playing) run else Run(playing = false, offsetDp = offset(run, speed, now))

    /** The speed is about to change: the place so far is taken at the old [speed], and the new one counts from [now]. */
    fun respeed(run: Run, speed: Int, now: Long): Run = if (!run.playing) run else Run(playing = true, startedAt = now, offsetDp = offset(run, speed, now))

    fun restart(): Run = Run()

    /** Played to its end: it stands there, paused. */
    fun atEnd(endDp: Float): Run = Run(playing = false, offsetDp = endDp)

    /**
     * Whether the script has ended: its last line, which starts [endDp] into it, has reached the top of the window,
     * nearest the camera. A script of one line ([endDp] 0) has nowhere to go and never says so.
     */
    fun ended(offsetDp: Float, endDp: Float): Boolean = endDp > 0f && offsetDp >= endDp

    /** Where the script is drawn at [now]: never above its start, never past its end. */
    fun shown(run: Run, speed: Int, now: Long, endDp: Float): Float = offset(run, speed, now).coerceIn(0f, endDp.coerceAtLeast(0f))
}
