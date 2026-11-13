package com.meridian11.books.bentobar.items

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
import java.util.Locale

/** Upcoming calendar events from the Calendar provider (needs READ_CALENDAR). Tasks synced in by task apps are left out. */
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
        /**
         * A real meeting (see [Meetings.isMeeting]): timed, on an [editable] calendar, and with a video-call
         * [link] or at least one attendee besides the user. A flight Gmail added on its own is not one.
         */
        val meeting: Boolean,
        /** A video-call link found in the location or description. */
        val link: String?,
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
    val events: List<Event> get() = loader.peek(Unit).orEmpty()
    private var observer: ContentObserver? = null

    /** [loaded]: told on the main thread when what was read has changed (the items that show it are computed again). */
    fun init(context: Context, loaded: () -> Unit = {}) { app = context.applicationContext; this.loaded = loaded }

    /** [who] needs the calendar from now on: a type going live, the chip. Main thread. */
    fun hold(who: String) = users.hold(who)

    /** [who] doesn't any more. Main thread. */
    fun release(who: String) = users.release(who)

    /** Nothing has been read yet and the first load is on its way. Main thread. */
    val loadingFirst: Boolean get() = loader.loadingFirst()

    /** [done] runs when what is being loaded has come back, or after a few seconds if it doesn't say so. Main thread. */
    fun whenLoaded(done: () -> Unit) = waiting.add(done)

    fun allowed() = ::app.isInitialized && app.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        Uses.on(Uses.CALENDAR)

    /**
     * Reloads at most every [RELOAD_MS], or right away after the provider changes, and only while someone [hold]s the
     * calendar. One load at a time: a sync that changes hundreds of events queues one more load, not hundreds. Main thread.
     */
    fun refresh(force: Boolean = false) {
        if (!allowed()) { forget(); return }
        observe()
        if (force) loader.refresh(Unit, afterRunning = true) else loader.want(Unit)
    }

    /** Calendar switched off in Setup: drop what was read, and whatever a running load returns. */
    fun forget() {
        val had = events.isNotEmpty()
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
        val next = if (from == null || to == null) null else from..to
        if (next == view) return
        view = next
        if (next != null && allowed()) refresh(force = true)
    }

    private fun load(now: Long): List<Event> = try {
        val begin = now - 36 * 3_600_000L
        val end = now + 40 * 86_400_000L
        val spans = Meetings.spans(begin..end, view)
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS, CalendarContract.Instances.VISIBLE,
            CalendarContract.Instances.CALENDAR_ACCESS_LEVEL, CalendarContract.Instances.OWNER_ACCOUNT,
        )
        val out = ArrayList<Event>()
        // Events whose notes link a task in a task app: a task if nothing says it is a meeting ([Meetings.isTask]).
        val taskLinked = HashSet<Long>()
        // The calendar owner of each event, for telling the user apart from other attendees.
        val owners = HashMap<Long, String>()
        // An occurrence that crosses from one span into the next comes back from both.
        val seen = HashSet<Pair<Long, Long>>()
        for (span in spans) CalendarContract.Instances.query(app.contentResolver, projection, span.first, span.last)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(9) == 0) continue // calendar hidden by the user
                if (c.getInt(8) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                val rawLocation = c.getString(6).orEmpty()
                // A call link is looked for in the text as it came: a place cut short could lose it.
                val text = rawLocation + "\n" + c.getString(7).orEmpty()
                val allDay = c.getInt(4) != 0
                val id = c.getLong(0)
                if (!seen.add(id to c.getLong(2))) continue
                if (CallLinks.isTask(text)) taskLinked += id
                c.getString(11)?.let { owners[id] = it }
                out += Event(
                    eventId = id, title = Meetings.title(c.getString(1), app.getString(R.string.calendar_no_title)),
                    begin = c.getLong(2), end = c.getLong(3), allDay = allDay,
                    color = c.getInt(5), location = Meetings.location(rawLocation),
                    editable = c.getInt(10) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                    meeting = false, link = CallLinks.find(text),
                )
            }
        }
        // Without a call link, a meeting needs someone else invited. Attendees are read for every
        // loaded candidate, once per load (not per tick): Next meeting needs today's, and the calendar
        // leaves meetings out of its agenda on any day the month view can show.
        val candidates = out.filter { Meetings.asksAttendees(it.link != null, it.eventId in taskLinked, it.allDay, it.editable) }.mapTo(HashSet()) { it.eventId }
        val withOthers = runCatching { withOthers(candidates, owners) }
            .onFailure { Log.w(TAG, "attendee query failed", it) }.getOrDefault(emptySet())
        // Tasks synced in from a task app (Todoist links each one, open or done) aren't events:
        // they show nowhere, not in the agenda, the month view, Next meeting or the chip.
        out.filterNot { Meetings.isTask(it.eventId in taskLinked, it.link != null, it.eventId in withOthers) }.map { e ->
            val meeting = Meetings.isMeeting(e.allDay, e.editable, e.link != null, e.eventId in withOthers)
            if (meeting) e.copy(meeting = true) else e
        }.sortedBy { it.begin }
    } catch (e: Exception) {
        Log.w(TAG, "calendar query failed", e)
        emptyList()
    }

    /**
     * Of [ids], the events with at least one attendee who isn't the user. The user is an attendee
     * whose email is the event's calendar owner ([owners]), or the organizer when that's one of the
     * user's own accounts (the owners of calendars they own).
     */
    private fun withOthers(ids: Set<Long>, owners: Map<Long, String>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val mine = HashSet<String>()
        app.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars.OWNER_ACCOUNT),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_OWNER}", null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { mine += it.lowercase(Locale.ROOT) }
        }
        val found = HashSet<Long>()
        app.contentResolver.query(CalendarContract.Attendees.CONTENT_URI,
            arrayOf(CalendarContract.Attendees.EVENT_ID, CalendarContract.Attendees.ATTENDEE_EMAIL, CalendarContract.Attendees.ATTENDEE_RELATIONSHIP),
            "${CalendarContract.Attendees.EVENT_ID} IN (${ids.joinToString(",")})", null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id in found) continue
                val email = c.getString(1).orEmpty().trim().lowercase(Locale.ROOT)
                if (email.isEmpty()) continue // no one to tell apart (and no one to meet)
                val isUser = email == owners[id]?.lowercase(Locale.ROOT) ||
                    (c.getInt(2) == CalendarContract.Attendees.RELATIONSHIP_ORGANIZER && email in mine)
                if (!isUser) found += id
            }
        }
        return found
    }

    /**
     * Meetings (see [Event.meeting]) on now or starting before the horizon ([Meetings.horizon]: 03:00
     * tomorrow). The Next meeting item and the chip use only these.
     */
    fun meetings(now: Long): List<Event> {
        if (!allowed()) return emptyList()
        val horizon = Meetings.horizon(now, ZoneId.systemDefault())
        return events.filter { it.meeting && Meetings.inHorizon(it.begin, it.end, now, horizon) }
    }

    /** Every loaded meeting that hasn't ended, today's and later ones: the chip plans its next change from these. */
    fun upcomingMeetings(now: Long): List<Event> = if (!allowed()) emptyList() else events.filter { it.meeting && it.end > now }

    fun current(now: Long) = meetings(now).firstOrNull { it.begin <= now }
    fun next(now: Long) = meetings(now).firstOrNull { it.begin > now }

    /** Events overlapping the local day that starts at [dayStart] (all-day events use UTC dates). */
    fun on(dayStart: Long, dayEnd: Long, utcDayStart: Long): List<Event> = if (!allowed()) emptyList() else events.filter {
        if (it.allDay) it.begin <= utcDayStart && it.end > utcDayStart else it.begin < dayEnd && it.end > dayStart
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
internal fun calendarLoader(wiring: Refresher.Wiring, load: () -> List<Calendar.Event>?): Refresher<Unit, List<Calendar.Event>> =
    Refresher(wiring, every = { _, _ -> Calendar.RELOAD_MS }, load = { _, _ -> load() })

/** The load under way is the first: nothing has been read yet, so finding no meeting would only mean not knowing. */
internal fun Refresher<Unit, List<Calendar.Event>>.loadingFirst(): Boolean = loading(Unit) && peek(Unit) == null
