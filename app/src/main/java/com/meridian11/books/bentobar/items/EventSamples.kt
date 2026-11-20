package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.Meetings.Cal
import com.meridian11.books.bentobar.items.Meetings.Guest
import com.meridian11.books.bentobar.items.Meetings.Reading
import com.meridian11.books.bentobar.items.Meetings.Row
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Made-up calendars for testing on a device (debug builds: `./bento debug event stage demo`), one set for each rule of
 * [Meetings.judge] and `demo` for pictures. A staged set goes through the real rules with the real choices, so a switch in
 * Setup changes what it shows. Made-up titles, no person's name, addresses only at example.com (and one room's address of
 * the calendar service, which is how a room whose type was not synced looks). Pure, so a unit test checks each set.
 *
 * Times are relative to the moment it is staged, and a meeting still to come is held one minute under the horizon
 * ([Meetings.horizon], 03:00): a set staged at 02:50 still has its meeting.
 */
object EventSamples {
    val names = listOf("demo", "solo", "room", "shared", "unanswered", "free", "workshop", "link", "allday", "gone", "words")

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    /** The user: the account of the calendars below. */
    const val YOU = "you@example.com"
    private const val YOU_HOME = "you.home@example.com"
    private const val GOOGLE = "com.google"
    private const val OWNER = 700
    private const val EDITOR = 600
    private const val READER = 200

    private val work = Cal(1, YOU, GOOGLE, YOU, "Work", 0xFF3F7EDB.toInt(), OWNER, visible = true, flagPrimary = true)
    private val personal = Cal(2, YOU_HOME, GOOGLE, YOU_HOME, "Personal", 0xFF2E9E6B.toInt(), OWNER, visible = true, flagPrimary = true)
    /** A team calendar the user can edit, synced with the work account: not the user's own. */
    private val team = Cal(3, YOU, GOOGLE, "team@example.com", "Team", 0xFFE08A1E.toInt(), EDITOR, visible = true, flagPrimary = false)
    private val birthdays = Cal(4, YOU, GOOGLE, "birthdays@example.com", "Birthdays", 0xFF8E63CE.toInt(), READER, visible = true, flagPrimary = false)
    private val calendars = listOf(work, personal, team, birthdays).associateBy { it.id }

    private const val LINK = "https://meet.example.com/design-review"
    private val people = listOf(Guest(YOU), Guest("ana@example.com"), Guest("ben@example.com"))

    /** The set called [name], staged at [now] in [zone]; null for a name that is none. */
    fun of(name: String, now: Long, zone: ZoneId): Reading? {
        val b = Builder(now, Meetings.horizon(now, zone) - MIN, zone)
        when (name) {
            "demo" -> {
                b.event("Design review", 12 * MIN, 45 * MIN, guests = people + Guest("cy@example.com"), link = LINK)
                b.event("Planning", 2 * HOUR, HOUR, guests = people, location = "Room 4")
                b.event("Team lunch", 3 * HOUR, HOUR, guests = people, location = "Canteen")
                b.event("Focus time", 5 * HOUR, HOUR, cal = personal, organizer = YOU_HOME)
                b.allDay("Release day")
            }
            "solo" -> {
                b.event("Focus time", 20 * MIN, HOUR)
                b.event("Dentist", 3 * HOUR, HOUR, location = "12 Main Street")
            }
            "room" -> b.event("Writing block", 20 * MIN, HOUR, guests = listOf(Guest(YOU), Guest("room-4@example.com", Meetings.TYPE_RESOURCE),
                Guest("c_4f2a1b@resource.calendar.google.com", 1)))
            "shared" -> {
                // A colleague's meeting on the team calendar, the user not invited: someone else's.
                b.event("Their 1:1", -10 * MIN, 30 * MIN, cal = team, organizer = "ana@example.com",
                    guests = listOf(Guest("ana@example.com"), Guest("ben@example.com")), link = LINK)
                // One the user is invited to: on the team calendar and on the user's own, counted once.
                val sync = listOf(Guest("ben@example.com"), Guest(YOU), Guest("cy@example.com"))
                b.event("Team sync", -5 * MIN, 30 * MIN, cal = team, organizer = "ben@example.com", guests = sync)
                b.event("Team sync", -5 * MIN, 30 * MIN, organizer = "ben@example.com", guests = sync)
            }
            "unanswered" -> b.event("Vendor intro", -5 * MIN, 30 * MIN, organizer = "vendor@example.com",
                guests = listOf(Guest("vendor@example.com"), Guest(YOU)), selfStatus = Meetings.SELF_INVITED)
            "free" -> b.event("Office hours", 20 * MIN, HOUR, guests = people, availability = Meetings.AVAILABILITY_FREE)
            "workshop" -> {
                b.event("Workshop", -HOUR, 4 * HOUR, guests = people, location = "Room 4")
                b.event("Stand-up", 10 * MIN, 15 * MIN, guests = people, link = LINK)
            }
            "link" -> b.event("Hold", -5 * MIN, HOUR, link = LINK)
            "allday" -> {
                b.allDay("Offsite", guests = people, link = LINK)
                b.allDay("Birthday", cal = birthdays, organizer = "birthdays@example.com")
            }
            "gone" -> {
                b.event("Old sync", 20 * MIN, 30 * MIN, organizer = "ana@example.com", guests = people, status = Meetings.STATUS_CANCELED)
                b.event("Review", 20 * MIN, 30 * MIN, organizer = "ana@example.com", guests = people, selfStatus = Meetings.SELF_DECLINED)
            }
            "words" -> b.event("Lunch", 20 * MIN, HOUR, guests = listOf(Guest(YOU), Guest("ana@example.com"), Guest("ben@example.com")))
            else -> return null
        }
        return Reading(calendars, b.rows, b.guests)
    }

    /** Puts a set together: [latest] is the latest a meeting still to come may start (one minute under the horizon). */
    private class Builder(val now: Long, val latest: Long, val zone: ZoneId) {
        val rows = ArrayList<Row>()
        val guests = HashMap<Long, List<Guest>>()
        private var id = 1000L

        fun event(title: String, startsIn: Long, length: Long, cal: Cal = work, organizer: String = YOU, guests: List<Guest> = emptyList(),
                  link: String? = null, location: String = "", status: Int = 1, selfStatus: Int = 0, availability: Int = 0) {
            val begin = if (startsIn > 0) minOf(now + startsIn, latest) else now + startsIn
            add(Row(++id, cal.id, title, begin, begin + length, color = cal.color, location = location, status = status,
                selfStatus = selfStatus, availability = availability, organizer = organizer, link = link), guests)
        }

        /** An all-day event today: the provider keeps those in UTC days. */
        fun allDay(title: String, cal: Cal = work, organizer: String = YOU, guests: List<Guest> = emptyList(), link: String? = null) {
            val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val begin = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            add(Row(++id, cal.id, title, begin, begin + 24 * HOUR, allDay = true, color = cal.color, organizer = organizer, link = link), guests)
        }

        private fun add(row: Row, with: List<Guest>) {
            rows += row
            if (with.isNotEmpty()) guests[row.eventId] = with
        }
    }
}
