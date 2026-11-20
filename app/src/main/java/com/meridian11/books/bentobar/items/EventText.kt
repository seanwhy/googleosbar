package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.util.Fmt
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * What Next meeting says in the bar about the meeting it stands on: the text, the name the ‹ menu and a tooltip give it, and
 * what is spoken. Pure Kotlin, unit-tested (`EventTextTest`): the words come from outside ([Words]), and so does the way a
 * moment is written ([Clock]: the system's 12- or 24-hour time, the locale's weekday and date). Nothing here keeps or prints
 * a title.
 */
object EventText {
    /** The words the rules use, each a text resource: the older ones `event_…` in strings.xml, the newer `events_…`. */
    enum class Word(val resource: String) {
        NOW_TEXT("event_now_text"), LEFT("events_left"), IN("event_in"), NEXT_TEXT("event_next_text"),
        NOW_DESC("event_now_desc"), NEXT_DESC("event_next_desc"),
    }

    /** Where the words come from: the app's strings in the item, the files themselves in the test. */
    class Words(private val text: (Word) -> String) {
        /** [word] with its arguments filled in; what a translation gone wrong can't fill is said plainly, never thrown. */
        fun fill(word: Word, vararg args: String): String =
            try { String.format(Locale.ROOT, text(word), *args) } catch (e: IllegalArgumentException) { args.joinToString(" ") }
    }

    /**
     * How a moment is written: [time] as the system shows a time ("14:30"), [dayTime] a weekday and a time ("Tue 10:00"),
     * [date] a day of a month ("Nov 24"); [dayTimeSaid] and [dateSaid] the same in words a screen reader reads well.
     */
    class Clock(
        val time: (Long) -> String,
        val dayTime: (Long) -> String,
        val date: (Long) -> String,
        val dayTimeSaid: (Long) -> String = dayTime,
        val dateSaid: (Long) -> String = date,
    )

    /** What the bar shows: [text], [label] (the title), [desc] (spoken, and the tooltip: it always names the title), [active]. */
    class Bar(val text: String, val label: String, val desc: String, val active: Boolean)

    /** A meeting that starts within this long is counted down ("in 12m"); later ones get their time. */
    const val COUNTDOWN_MS = 60 * 60_000L
    /** Up to this many days ahead a weekday names the day unmistakably; further out, the date. */
    const val WEEKDAY_DAYS = 6L

    /**
     * The bar's words for [e] at [now]. [horizon]: the end of today ([Meetings.horizon]). [timeOnly]: "In the bar" is Time,
     * so the title is left out of the text (never out of [desc]). [chars]: "Longest title". [soonMs]: "Show when", from when
     * a meeting is [Bar.active]; one past the horizon, on a later day, never is.
     */
    fun bar(e: Calendar.Event, now: Long, horizon: Long, zone: ZoneId, timeOnly: Boolean, chars: Int, soonMs: Long, words: Words, clock: Clock): Bar {
        val title = TextRules.short(e.title, chars)
        if (e.begin <= now) {
            val left = Fmt.duration(e.end - now)
            return Bar(if (timeOnly) words.fill(Word.LEFT, left) else words.fill(Word.NOW_TEXT, title, left), e.title,
                words.fill(Word.NOW_DESC, e.title, Fmt.spokenMinutes(e.end - now)), active = true)
        }
        val until = e.begin - now
        val (shown, said) = when {
            until < COUNTDOWN_MS -> (until.coerceAtLeast(60_000)).let { words.fill(Word.IN, Fmt.duration(it)) to words.fill(Word.IN, Fmt.spokenMinutes(it)) }
            e.begin < horizon -> clock.time(e.begin).let { it to it }
            daysAhead(e.begin, now, zone) <= WEEKDAY_DAYS -> clock.dayTime(e.begin) to clock.dayTimeSaid(e.begin)
            else -> clock.date(e.begin) to clock.dateSaid(e.begin)
        }
        return Bar(if (timeOnly) shown else words.fill(Word.NEXT_TEXT, title, shown), e.title, words.fill(Word.NEXT_DESC, e.title, said),
            active = until <= soonMs && e.begin < horizon)
    }

    /** What Live activity says: [text] and [desc] (spoken: it always names the title). */
    class Live(val text: String, val desc: String)

    /**
     * Live activity's words for [e] at [now], a meeting from ten minutes before it starts ([LiveRules.meeting]): the bar's
     * countdown, with the title cut so that the countdown stays whole ([LiveRules.around], [chars] at most). [timeOnly]: the
     * first Next meeting item shows the time alone ("In the bar": Time), and so does Live activity: a title kept off the
     * bar is not shown beside it either. Never out of [Live.desc].
     */
    fun live(e: Calendar.Event, now: Long, timeOnly: Boolean, chars: Int, words: Words): Live {
        if (e.begin <= now) {
            val left = Fmt.duration(e.end - now)
            return Live(if (timeOnly) words.fill(Word.LEFT, left) else LiveRules.around(e.title, chars) { words.fill(Word.NOW_TEXT, it, left) },
                words.fill(Word.NOW_DESC, e.title, Fmt.spokenMinutes(e.end - now)))
        }
        val until = (e.begin - now).coerceAtLeast(60_000)
        val shown = words.fill(Word.IN, Fmt.duration(until))
        return Live(if (timeOnly) shown else LiveRules.around(e.title, chars) { words.fill(Word.NEXT_TEXT, it, shown) },
            words.fill(Word.NEXT_DESC, e.title, words.fill(Word.IN, Fmt.spokenMinutes(until))))
    }

    /** How many local days [begin] is after [now]'s. */
    private fun daysAhead(begin: Long, now: Long, zone: ZoneId): Long =
        ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), Instant.ofEpochMilli(begin).atZone(zone).toLocalDate())
}
