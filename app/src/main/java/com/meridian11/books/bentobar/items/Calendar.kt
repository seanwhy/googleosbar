package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.MeetingChoices
import com.meridian11.books.bentobar.data.Uses
import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.util.Now
import java.time.ZoneId

/**
 * Calendar events from the Calendar provider (needs READ_CALENDAR), each with its verdict ([Meetings.judge]): what is shown at
 * all, and what is a meeting. Tasks synced in by task apps, canceled and declined events are left out. The Android part is
 * [read]; what an event is, is [Meetings]'. Nothing read here is logged: a failure is logged by its kind.
 */
object Calendar {
    data class Event(
        val eventId: Long,
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val color: Int,
        val location: String,
        /** On a calendar the user can edit (contributor access or more): not holidays, birthdays or subscriptions. */
        val editable: Boolean,
        /** A meeting by its [verdict] ([Meetings.judge]): what Next meeting, the chip and Live activity count. */
        val meeting: Boolean,
        /** A video-call link found in the location or description. */
        val link: String?,
        val calendarId: Long = 0,
        /** What [Meetings.judge] made of it; null for an event a test made up. */
        val verdict: Meetings.Verdict? = null,
        /** On one of the user's own calendars ([Meetings.Cal.own]): of one meeting on two calendars, this copy is kept ([Meetings.once]). */
        val ownCalendar: Boolean = false,
    ) {
        /** A place to get directions to: a timed event's location on an editable calendar, unless that is itself a link. */
        val place: String? get() = location.trim().takeIf { editable && !allDay && it.isNotEmpty() && !CallLinks.hasUrl(it) }
    }

    private const val TAG = "BentoBar"
    /** How long what was read stays fresh. The provider changing reloads it at once, so this is only for a change it didn't tell of. */
    const val RELOAD_MS = 15 * 60_000L
    /**
     * How long the calendar stays observed and loaded after its last user left. The strip hides for every panel and every
     * full-screen flip, and it comes back in seconds: without this each of those would read 41 days again and the next
     * meeting would be gone from the bar until that was done.
     */
    const val KEPT_AFTER_LAST_USER_MS = 60_000L
    /** How many event ids one attendee query names at most. */
    private const val IDS_PER_QUERY = 400
    private lateinit var app: Context
    private var loaded: () -> Unit = {}
    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** Who needs the calendar: the two item types while they are sampled, and the Live Update chip, which needs meetings while the service is off. Nobody: no observer, nothing loaded, nothing kept. */
    private val users = Holders(KEPT_AFTER_LAST_USER_MS, { ms, r -> main.postDelayed(r, ms) }, { main.removeCallbacks(it) }, start = { refresh() }, stop = ::letGo)
    /** How long [whenLoaded] waits at most: time for a read of 41 days, and well inside what Android gives a broadcast receiver. */
    private const val LOAD_WAIT_MS = 5_000L
    private val waiting = Waiters(LOAD_WAIT_MS, { ms, r -> main.postDelayed(r, ms) }, { main.removeCallbacks(it) })
    /**
     * Calendar's loading is [Refresher]'s, one load at a time and one snapshot, but on a wiring of its own: `Background`'s
     * stops loading while nothing shows items, and the Live Update chip needs meetings while the service is off.
     */
    private val loader = calendarLoader(
        Refresher.Wiring(Background.executor("calendar"), { Background.onMain(it::run) }, Now::elapsed,
            changed = { Chips.update(app); loaded(); waiting.arrived() }, minGapMs = 0, mayLoad = { users.active && allowed() }),
    ) { load(System.currentTimeMillis()).takeIf { allowed() } }

    /**
     * A reading and its events, judged by the choices they were last asked with: when the choices change it is judged again,
     * and the provider is not read again (which events are meetings changes nothing of what is read). [readOff]: the
     * calendars unticked when it was read, whose attendees were not read: they stay unticked in its judging until a read
     * with the new choices comes ([choicesChanged] asks for one). Null for a staged set, which has every attendee.
     */
    private class Judged(val reading: Meetings.Reading, private val readOff: Set<String>?) {
        private var at: MeetingChoices.State? = null
        private var events: List<Event> = emptyList()

        /** Whether it knows every attendee a read with [off] unticked would: no calendar ticked now was unticked when it was read. */
        fun hasAttendeesFor(off: Set<String>) = readOff == null || readOff.all { it in off }

        /** Its events as [choices] judge them: judged again only when they are other choices than last time. */
        @Synchronized
        fun events(choices: MeetingChoices.State, noTitle: () -> String): List<Event> {
            if (at !== choices) {
                val judging = if (hasAttendeesFor(choices.calendarsOff)) choices
                    else choices.copy(calendarsOff = choices.calendarsOff + readOff.orEmpty())
                events = Meetings.events(reading, judging, noTitle())
                at = choices
            }
            return events
        }
    }

    /** A made-up reading a test staged (`./bento debug event stage NAME`). */
    private class Staged(val name: String, reading: Meetings.Reading) {
        val judged = Judged(reading, readOff = null)
    }
    @Volatile private var staged: Staged? = null

    /** What was read, or while a set is staged, the set: judged by the real rules with the real choices. */
    val events: List<Event> get() = (staged?.judged ?: loader.peek(Unit))?.events(MeetingChoices.state.value, ::noTitle).orEmpty()

    private fun noTitle() = if (::app.isInitialized) app.getString(R.string.calendar_no_title) else ""
    private var observer: ContentObserver? = null

    /** [loaded]: told on the main thread when what was read has changed (the items that show it are computed again). */
    fun init(context: Context, loaded: () -> Unit = {}) { app = context.applicationContext; this.loaded = loaded }

    /** [who] needs the calendar from now on: a type going live, the chip. Main thread. */
    fun hold(who: String) = users.hold(who)

    /** [who] doesn't any more. Main thread. */
    fun release(who: String) = users.release(who)

    /** Nothing has been read yet and the first load is on its way. Main thread. */
    val loadingFirst: Boolean get() = staged == null && loader.loadingFirst()

    /** [done] runs when what is being loaded has come back, or after a few seconds if it doesn't say so. Main thread. */
    fun whenLoaded(done: () -> Unit) = waiting.add(done)

    /** Calendar access is given and in use, or a set is staged (which stands for a calendar that can be read). */
    fun allowed() = staged != null || readable()

    private fun readable() = ::app.isInitialized && app.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        Uses.on(Uses.CALENDAR)

    /**
     * Reloads at most every [RELOAD_MS], or right away after the provider changes, and only while someone [hold]s the
     * calendar. One load at a time: a sync that changes hundreds of events queues one more load, not hundreds. Main thread.
     * Nothing while a set is staged: the real reading stays as it is until the set is taken off.
     */
    fun refresh(force: Boolean = false) {
        if (staged != null) return
        if (!allowed()) { forget(); return }
        observe()
        if (force) loader.refresh(Unit, afterRunning = true) else loader.want(Unit)
    }

    /**
     * The choices of which events count changed ([MeetingChoices]): what was read is judged again by them where it is read
     * ([events]), and the items and the chip are told at once. Only a calendar ticked now that was unticked when the
     * provider was read has it read again, for its attendees; a word, a switch, "Events with only you" or a calendar
     * unticked read nothing. Main thread.
     */
    fun choicesChanged() {
        if (::app.isInitialized) Chips.update(app)
        loaded()
        if (staged == null && loader.peek(Unit)?.hasAttendeesFor(MeetingChoices.state.value.calendarsOff) != true) refresh(force = true)
    }

    /** Calendar switched off in Setup: drop what was read, and whatever a running load returns. */
    fun forget() {
        val had = loader.peek(Unit)?.reading?.rows.orEmpty().isNotEmpty()
        loader.forget()
        if (had && ::app.isInitialized) Chips.update(app)
    }

    private fun observe() {
        if (observer != null || !users.active) return
        observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) { refresh(force = true) }
        }.also { app.contentResolver.registerContentObserver(CalendarContract.Instances.CONTENT_URI, true, it) }
    }

    /** The last user has gone: nothing listens to the provider and nothing it said is kept. */
    private fun letGo() {
        observer?.let { app.contentResolver.unregisterContentObserver(it) }
        observer = null
        forget()
    }

    /**
     * The days the month menu shows beyond the ones always kept loaded (36 hours back to 40 days
     * ahead), loaded as well while it shows them. Without it, a month further out looked empty.
     */
    @Volatile private var view: LongRange? = null

    /** The month menu shows [from] to [to]; nulls when it has closed. */
    fun view(from: Long?, to: Long?) {
        if (staged != null) return
        val next = if (from == null || to == null) null else from..to
        if (next == view) return
        view = next
        if (next != null && allowed()) refresh(force = true)
    }

    /** One load: read, and judged at once (off the main thread) by the choices it was read with. */
    private fun load(now: Long): Judged {
        val choices = MeetingChoices.state.value
        val reading = try {
            read(Meetings.spans(now - 36 * 3_600_000L..now + 40 * 86_400_000L, view), choices)
        } catch (e: Exception) {
            Log.w(TAG, "calendar query failed: ${e.javaClass.simpleName}")
            Meetings.Reading(emptyMap(), emptyList())
        }
        return Judged(reading, choices.calendarsOff).also { it.events(choices, ::noTitle) }
    }

    /**
     * The provider's calendars, every one (as [Meetings.Cal], by id). For Setup's list too ([calendars]). Off the main thread.
     */
    private fun readCalendars(): Map<Long, Meetings.Cal> {
        val out = HashMap<Long, Meetings.Cal>()
        val c = CalendarContract.Calendars.CONTENT_URI
        app.contentResolver.query(c, arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.CALENDAR_COLOR,
        ), null, null, null)?.use { r ->
            while (r.moveToNext()) {
                val id = r.getLong(0)
                out[id] = Meetings.Cal(id, r.getString(1).orEmpty(), r.getString(2).orEmpty(), r.getString(3).orEmpty(),
                    TextRules.oneLine(r.getString(7).orEmpty(), Meetings.TITLE_CHARS), r.getInt(8), r.getInt(5), r.getInt(6) != 0, r.getInt(4) == 1)
            }
        }
        return out
    }

    /**
     * One reading: the calendars, the occurrences in [spans], and the attendees of the events [Meetings.asksAttendees] names
     * by [choices] (read once per load, not per tick, in chunks of [IDS_PER_QUERY]), each with its answer. A row keeps what
     * was found in the description, never the description. Throws when the provider does (a column it doesn't have): the
     * caller says so by its kind.
     */
    private fun read(spans: List<LongRange>, choices: MeetingChoices.State): Meetings.Reading {
        val calendars = readCalendars()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS, CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.STATUS, CalendarContract.Instances.AVAILABILITY, CalendarContract.Instances.ORGANIZER,
        )
        val rows = ArrayList<Meetings.Row>()
        // An occurrence that crosses from one span into the next comes back from both.
        val seen = HashSet<Pair<Long, Long>>()
        for (span in spans) CalendarContract.Instances.query(app.contentResolver, projection, span.first, span.last)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (!seen.add(id to c.getLong(2))) continue
                val rawLocation = c.getString(6).orEmpty()
                // A call link is looked for in the text as it came: a place cut short could lose it.
                val text = rawLocation + "\n" + c.getString(7).orEmpty()
                rows += Meetings.Row(
                    eventId = id, calendarId = c.getLong(9), title = TextRules.oneLine(c.getString(1).orEmpty(), Meetings.TITLE_CHARS),
                    begin = c.getLong(2), end = c.getLong(3), allDay = c.getInt(4) != 0, color = c.getInt(5),
                    location = Meetings.location(rawLocation), status = if (c.isNull(10)) 1 else c.getInt(10), selfStatus = c.getInt(8),
                    availability = c.getInt(11), organizer = c.getString(12).orEmpty(), link = CallLinks.find(text), linksTask = CallLinks.isTask(text),
                )
            }
        }
        val asked = rows.filter { Meetings.asksAttendees(it, calendars[it.calendarId], choices) }.mapTo(LinkedHashSet()) { it.eventId }
        val guests = HashMap<Long, MutableList<Meetings.Guest>>()
        // Without them nobody is known to be invited: every event is then one with only the user, as before there were rules.
        val failed = try {
            for (chunk in asked.chunked(IDS_PER_QUERY)) app.contentResolver.query(CalendarContract.Attendees.CONTENT_URI,
                arrayOf(CalendarContract.Attendees.EVENT_ID, CalendarContract.Attendees.ATTENDEE_EMAIL, CalendarContract.Attendees.ATTENDEE_TYPE,
                    CalendarContract.Attendees.ATTENDEE_STATUS),
                "${CalendarContract.Attendees.EVENT_ID} IN (${chunk.joinToString(",")})", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val email = c.getString(1).orEmpty()
                    if (email.isBlank()) continue // no one to tell apart (and no one to meet)
                    guests.getOrPut(c.getLong(0)) { ArrayList() } += Meetings.Guest(email, c.getInt(2), c.getInt(3))
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "attendee query failed: ${e.javaClass.simpleName}")
            guests.clear()
            e.javaClass.simpleName
        }
        return Meetings.Reading(calendars, rows, guests, failed)
    }

    /**
     * The calendars, for Setup's "Calendar events" list: the staged set's while one is staged, else the provider's. Empty
     * without calendar access, or when the provider can't be read. Off the main thread.
     */
    fun calendars(): List<Meetings.Cal> {
        staged?.let { return it.judged.reading.calendars.values.toList() }
        if (!readable()) return emptyList()
        return try { readCalendars().values.toList() } catch (e: Exception) { emptyList() }
    }

    /**
     * Tells [changed], on the main thread, each time the provider's calendars may have changed (an account added, a calendar
     * shared, left or renamed): for Setup's list, only while it is shown. The provider tells of every change it makes in
     * one go, events too, so a sync tells it many times. Returns what stops it; null without calendar access, since
     * Android refuses an observer then. Main thread.
     */
    fun watchCalendars(changed: () -> Unit): (() -> Unit)? {
        if (!readable()) return null
        val watch = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = changed()
        }
        try {
            app.contentResolver.registerContentObserver(CalendarContract.Calendars.CONTENT_URI, true, watch)
        } catch (e: SecurityException) {
            return null
        }
        return { runCatching { app.contentResolver.unregisterContentObserver(watch) } }
    }

    /**
     * Meetings (see [Event.meeting]) on now or starting before the horizon ([Meetings.horizon]: 03:00
     * tomorrow), one event on two calendars once ([Meetings.once]). Next meeting's "Today" uses only these.
     */
    fun meetings(now: Long): List<Event> {
        if (!allowed()) return emptyList()
        val horizon = Meetings.horizon(now, ZoneId.systemDefault())
        return Meetings.once(events.filter { it.meeting && Meetings.inHorizon(it.begin, it.end, now, horizon) })
    }

    /**
     * Every loaded meeting that hasn't ended, today's and later ones, each once: the chip and Live activity pick from these
     * (a 03:05 meeting gets its chip at 02:50), Next meeting with "Any day", and the chip plans its next change from them.
     */
    fun upcomingMeetings(now: Long): List<Event> = if (!allowed()) emptyList() else Meetings.once(events.filter { it.meeting && it.end > now })

    /** Events overlapping the local day that starts at [dayStart] (all-day events use UTC dates). */
    fun on(dayStart: Long, dayEnd: Long, utcDayStart: Long): List<Event> = if (!allowed()) emptyList() else events.filter {
        if (it.allDay) it.begin <= utcDayStart && it.end > utcDayStart else it.begin < dayEnd && it.end > dayStart
    }

    /**
     * Debug builds (`./bento debug event …` or `calendar …`): `stage NAME` puts a made-up set ([EventSamples]) in the
     * calendar's place, judged by the real rules with the real choices; `off` takes it away; `check` reads the real calendar
     * for today and tomorrow and says what the rules make of it, in counts ([Meetings.report]). Bare: what is there now.
     * Never a title, an address, a calendar's name or an event's id. Null for what it doesn't know.
     */
    fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        null -> {
            val all = events
            val counts = all.groupingBy { it.verdict?.word ?: "?" }.eachCount().entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }
            "staged=${staged?.name ?: "no"} events=${all.size} meetings=${all.count { it.meeting }}" + if (counts.isEmpty()) "" else " [$counts]"
        }
        "stage" -> args.getOrNull(1)?.let { name ->
            EventSamples.of(name, System.currentTimeMillis(), ZoneId.systemDefault())?.let { reading ->
                staged = Staged(name, reading)
                choicesChanged()
                debug(emptyList())
            }
        }
        "off" -> {
            val had = staged != null
            staged = null
            if (had && ::app.isInitialized) { Chips.update(app); loaded(); refresh(force = true) }
            "staged=no"
        }
        "check" -> check()
        else -> null
    }

    private fun check(): String {
        if (!readable()) return "no calendar access"
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val day = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val choices = MeetingChoices.state.value
        val reading = try { read(listOf(from..to), choices) } catch (e: Exception) { return "query=failed:${e.javaClass.simpleName}" }
        return "query=ok\n" + Meetings.report(reading, choices, now, zone)
    }

    fun open(e: Event) = Env.launch(openIntent(e))

    /** The calendar app showing this occurrence of [e]. */
    fun openIntent(e: Event): Intent = Intent(Intent.ACTION_VIEW,
        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.eventId))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end)

    fun openDay(millis: Long) = Env.launch(Intent(Intent.ACTION_VIEW,
        CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(millis.toString()).build()))

    fun join(e: Event) = joinIntent(e)?.let { Env.launch(it) } ?: false

    fun joinIntent(e: Event): Intent? = e.link?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }

    /** Google Maps (or any map app) searching for the event's place; the Maps website if none handles geo:. */
    fun directions(e: Event): Boolean {
        val q = Uri.encode(e.place ?: return false)
        return Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")), quiet = true) ||
            Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$q")))
    }
}

/**
 * How the calendar loads: [Refresher]'s rules, on the calendar's numbers. Pure, so the rules run in a test; [Calendar] gives
 * it the app's wiring and the query.
 */
internal fun <T : Any> calendarLoader(wiring: Refresher.Wiring, load: () -> T?): Refresher<Unit, T> =
    Refresher(wiring, every = { _, _ -> Calendar.RELOAD_MS }, load = { _, _ -> load() })

/** The load under way is the first: nothing has been read yet, so finding no meeting would only mean not knowing. */
internal fun Refresher<Unit, *>.loadingFirst(): Boolean = loading(Unit) && peek(Unit) == null
