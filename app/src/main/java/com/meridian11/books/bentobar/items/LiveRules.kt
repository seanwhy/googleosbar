package com.meridian11.books.bentobar.items

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * The Live activity's rules: which of the things happening now stands first, which sources an item has switched on, and
 * when each source this item brought along speaks (a timer, a meeting, what plays, a flight). Pure Kotlin, unit-tested
 * (`LiveRulesTest`); the words are each type's own, in its `liveNow`.
 */
object LiveRules {
    /** The most characters the bar shows: a meeting's title with its countdown, a flight's line. */
    const val LIMIT = 24

    /** The first of [all]: the highest rank, and among equals the one that began latest. Null: nothing is happening. */
    fun first(all: List<LiveNow>): LiveNow? = rest(all).firstOrNull()

    /** Every one of [all], in that order: the popup's rows. */
    fun rest(all: List<LiveNow>): List<LiveNow> = all.sortedWith(compareByDescending<LiveNow> { it.rank }.thenByDescending { it.since })

    /** The option that switches the source [type] on or off in a Live item. */
    fun key(type: String) = "src_$type"

    /** The type of Notification icons, whose progress bars come from other apps. */
    private const val OTHER_APPS = "notices"

    /**
     * Whether a source is on in an item that says nothing of it. Every one is, but other apps' progress: that needs
     * notification access and binds a listener, so the user chooses it.
     */
    fun onAtFirst(type: String): Boolean = type != OTHER_APPS

    /**
     * Whether a source is asked again at [now], having last been asked at [last] (null: never), when its own items are
     * recomputed every [everyMs]: at the pace its own `state()` would have, so it asks nothing of a service that its item
     * would not. A clock set back counts as due.
     */
    fun due(now: Long, last: Long?, everyMs: Long): Boolean = last == null || now < last || TickRules.due(now, last, everyMs)

    /**
     * [words] around a [title], the title cut so that the whole has at most [LIMIT] characters and never more than [most]
     * of its own (the item's "Longest title"): a meeting's countdown stays whole and the title gives way.
     */
    fun around(title: String, most: Int, words: (String) -> String): String {
        val room = LIMIT - TextRules.count(words(""))
        return words(TextRules.short(title, minOf(most, room).coerceAtLeast(1)))
    }

    // ---- the timer

    enum class Timer { RUNNING, DONE }

    /** How long a finished timer is news: as long as the Timer item says "Done". */
    const val DONE_MS = 60_000L

    /**
     * The one timer at [now] (its own clock, wall time): running (a countdown, a Pomodoro round, the stopwatch), done and
     * not yet dismissed (Stop sets [finishedAt] back to 0), or nothing. A paused timer is nothing happening.
     */
    fun timer(state: Timers.State?, finishedAt: Long, now: Long): Timer? = when {
        state != null -> if (state.running) Timer.RUNNING else null
        finishedAt != 0L && now - finishedAt in 0 until DONE_MS -> Timer.DONE
        else -> null
    }

    // ---- the next meeting

    /** A meeting is news from this long before it starts until this long after. */
    const val MEETING_MS = 10 * 60_000L

    /**
     * The meeting of [meetings] (the Next meeting item's: on now or later today) that is news at [now]: one that starts
     * within [MEETING_MS] or started less than that ago and has not ended. Of two, the one whose start is nearest.
     */
    fun meeting(meetings: List<Calendar.Event>, now: Long): Calendar.Event? =
        meetings.filter { now >= it.begin - MEETING_MS && now < it.begin + MEETING_MS && it.end > now }.minByOrNull { abs(it.begin - now) }

    // ---- what plays

    /** The player that is playing, and only with Now playing's own access, which says what it plays. Null: nothing to name. */
    fun media(playing: NowPlaying.Playing): NowPlaying.Session? = playing.main?.takeIf { playing.access && it.playing }

    // ---- a flight

    /** When boarding is taken to start: the service names no boarding time, and most flights board from about then. */
    val BOARDING: Duration = Duration.ofMinutes(45)

    /**
     * Since when [f] is news at [now], or null: from boarding until it lands. Counted down to and not boarding yet, landed,
     * canceled, diverted or a timetable's flight past its time (nobody knows what became of it) is no news here.
     */
    fun flight(f: Flight, now: Instant): Instant? {
        val boards = f.from.time?.let(f.from::moment)?.minus(BOARDING)
        return when (FlightRules.phase(f, now)) {
            FlightRules.Phase.IN_AIR -> boards ?: now
            FlightRules.Phase.SOON -> boards?.takeUnless { now.isBefore(it) }
            else -> null
        }
    }
}
