package com.meridian11.books.bentobar.items

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The Next meeting item's rules. Pure Kotlin (no Android), so they're unit-tested on the JVM.
 * Task-app events ([isTask]) never get here: [Calendar] leaves them out when it loads.
 */
object Meetings {
    /** The horizon's time of day, tomorrow: today, plus 3 hours for a midnight or 1 a.m. meeting. */
    private val CUTOFF: LocalTime = LocalTime.of(3, 0)

    /**
     * The end of the horizon: "today, plus 3 hours". 03:00 after the current evening, so a midnight
     * or 1 a.m. meeting still counts. Between midnight and 03:00 the evening is still "today", so
     * it's 03:00 that same night, not 26 hours away. Nothing after it counts, ever.
     */
    fun horizon(now: Long, zone: ZoneId): Long {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        val day = if (t.toLocalTime() < CUTOFF) t.toLocalDate() else t.toLocalDate().plusDays(1)
        return day.atTime(CUTOFF).atZone(zone).toInstant().toEpochMilli()
    }

    /** On now, or starting before [horizon]; an event that has ended is out. */
    fun inHorizon(begin: Long, end: Long, now: Long, horizon: Long): Boolean = end > now && begin < horizon

    /**
     * A meeting is timed (not all-day), on a calendar the user can edit (contributor access or more:
     * not holidays, birthdays or subscriptions), and has a video-call link or an attendee besides the
     * user. So a flight or a solo appointment is never one.
     */
    fun isMeeting(allDay: Boolean, editable: Boolean, hasCallLink: Boolean, hasOthers: Boolean): Boolean =
        !allDay && editable && (hasCallLink || hasOthers)

    /**
     * Whether an event's attendees are read: with no call link, a timed event on an editable calendar (it may be a
     * meeting), or any event that [linksTask], so an all-day or read-only event with others invited isn't taken for a
     * task ([isTask]). Plain tasks don't need it: they stay hidden.
     */
    fun asksAttendees(hasCallLink: Boolean, linksTask: Boolean, allDay: Boolean, editable: Boolean): Boolean =
        !hasCallLink && (linksTask || (!allDay && editable))

    /**
     * Whether an event that [linksTask] (a task app's link in its notes) is a task and so shows nowhere. A link alone
     * doesn't make one: a real meeting's notes can link a task. Only with no video-call link and nobody else invited.
     */
    fun isTask(linksTask: Boolean, hasCallLink: Boolean, hasOthers: Boolean): Boolean = linksTask && !hasCallLink && !hasOthers

    /** How much of an event's title and place is kept: enough for any menu, and an invitation's sender has no say over it. */
    const val TITLE_CHARS = 200
    const val LOCATION_CHARS = 200

    /** An event's title as it is kept: written by whoever sent the invitation, so one line and nothing that turns the text around ([noTitle] if nothing is left). */
    fun title(raw: String?, noTitle: String): String = TextRules.oneLine(raw.orEmpty(), TITLE_CHARS).ifBlank { noTitle }

    /** An event's place, likewise. */
    fun location(raw: String?): String = TextRules.oneLine(raw.orEmpty(), LOCATION_CHARS)

    /** What the calendar loads: the [usual] window, then whatever part of the [viewed] month lies before or after it. */
    fun spans(usual: LongRange, viewed: LongRange?): List<LongRange> = buildList {
        add(usual)
        if (viewed == null) return@buildList
        if (viewed.first < usual.first) add(viewed.first..minOf(viewed.last, usual.first))
        if (viewed.last > usual.last) add(maxOf(viewed.first, usual.last)..viewed.last)
    }
}
