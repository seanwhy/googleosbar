package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.MeetingChoices
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * Which calendar events are meetings, and which one the bar stands on. Pure Kotlin (no Android), so they're unit-tested on the
 * JVM: [Calendar] reads the provider into a [Reading], and everything after that is here. Android's numbers come in as ints
 * and are named once, below.
 *
 * Whose event it is (AGENTS.md, "Things learned the hard way"): the user is every account on the device ([mine]); an own
 * calendar ([Cal.own]) has the user on every event; a room or a calendar invited as a guest is no person ([isPerson]). An
 * event with other people that the user is not on is someone else's ([Verdict.NOT_MINE]), whatever the choices say. The
 * user's answer to an invitation is the row's on an own calendar and the user's attendee row's on another ([answer]), and
 * one event on two calendars is judged as its copy on an own calendar is ([verdicts]).
 * Nothing here keeps or prints a title or an address: no class that holds one is a data class.
 */
object Meetings {
    // ---- Android's numbers, as the provider writes them (CalendarContract)

    /** `Events.STATUS`: canceled. */
    const val STATUS_CANCELED = 2
    /** `Attendees.ATTENDEE_STATUS`, as `SELF_ATTENDEE_STATUS`: declined, invited (not answered), tentative ("Maybe"). */
    const val SELF_DECLINED = 2
    const val SELF_INVITED = 3
    const val SELF_TENTATIVE = 4
    /** `Events.AVAILABILITY`: free. */
    const val AVAILABILITY_FREE = 1
    /** `Attendees.ATTENDEE_TYPE`: a resource, such as a room. */
    const val TYPE_RESOURCE = 3
    /** `Calendars.CAL_ACCESS_CONTRIBUTOR`: the least access that can change events. */
    const val ACCESS_CONTRIBUTOR = 500
    /** `CalendarContract.ACCOUNT_TYPE_LOCAL`: a calendar of no account, so of nobody in particular. */
    const val ACCOUNT_TYPE_LOCAL = "LOCAL"

    // ---- what is read

    /**
     * A calendar. [owner] is its `OWNER_ACCOUNT`: the account for the primary calendar, a group address for a team or family
     * calendar, a colleague's address for a colleague's. [flagPrimary]: `IS_PRIMARY` as the provider answers it.
     */
    class Cal(
        val id: Long,
        val accountName: String,
        val accountType: String,
        val owner: String,
        val name: String,
        val color: Int,
        val access: Int,
        val visible: Boolean,
        val flagPrimary: Boolean,
    ) {
        /** The user's own: primary, or owned by the account it is synced with. Nothing lands on it without the user on it. */
        val own: Boolean get() = flagPrimary || (owner.isNotBlank() && owner.trim().equals(accountName.trim(), ignoreCase = true))

        /** The user can change its events (contributor or more): not holidays, birthdays or subscriptions. */
        val editable: Boolean get() = access >= ACCESS_CONTRIBUTOR

        /**
         * What names it in [MeetingChoices.State.calendarsOff]: account type, account and owner (its name where the owner is
         * blank). Not its row id, which changes when an account is added again.
         */
        val ident: String get() = listOf(accountType, accountName.trim().lowercase(Locale.ROOT),
            owner.trim().lowercase(Locale.ROOT).ifEmpty { name }).joinToString("\n")
    }

    /** One attendee: an address, its `ATTENDEE_TYPE` and its `ATTENDEE_STATUS` (the answer, numbered as `SELF_ATTENDEE_STATUS`). */
    class Guest(val email: String, val type: Int = 0, val status: Int = 0)

    /**
     * One occurrence of an event, as the rules need it: never its description, only what was found in it ([link], a call
     * link; [linksTask], a task app's link). [title] and [location] are cleaned where they are read ([title] may be blank).
     */
    class Row(
        val eventId: Long,
        val calendarId: Long,
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean = false,
        val color: Int = 0,
        val location: String = "",
        val status: Int = 1,
        val selfStatus: Int = 0,
        val availability: Int = 0,
        val organizer: String = "",
        val link: String? = null,
        val linksTask: Boolean = false,
    )

    /**
     * What one load read: the calendars by id, the occurrences, and the attendees of the events [asksAttendees] named.
     * [guestsFailed]: the kind of failure when the attendees could not be read (then nobody is known to be invited).
     */
    class Reading(
        val calendars: Map<Long, Cal>,
        val rows: List<Row>,
        val guests: Map<Long, List<Guest>> = emptyMap(),
        val guestsFailed: String? = null,
    )

    /**
     * What an event is, the first of these that applies ([judge]). [event]: it is shown at all (the month view); [meeting]:
     * Next meeting, the chip and Live activity count it. [word]: how `event check` names it.
     */
    enum class Verdict(val event: Boolean, val meeting: Boolean, val word: String) {
        HIDDEN_CALENDAR(false, false, "hidden-calendar"),
        CANCELED(false, false, "canceled"),
        DECLINED(false, false, "declined"),
        TASK(false, false, "task"),
        ALL_DAY(true, false, "all-day"),
        READ_ONLY(true, false, "read-only"),
        CALENDAR_OFF(true, false, "calendar-off"),
        NOT_MINE(true, false, "not-mine"),
        WORD(true, false, "word"),
        FREE(true, false, "free"),
        UNANSWERED(true, false, "unanswered"),
        WITH_OTHERS(true, true, "with-others"),
        ONLY_ME(true, false, "only-me"),
        ONLY_ME_CALL(true, true, "only-me-call"),
        ONLY_ME_ANY(true, true, "only-me-any"),
    }

    // ---- who the user is

    /** The user: every account a calendar on the device is synced with, lowercased. A local calendar's "account" is nobody. */
    fun mine(cals: Collection<Cal>): Set<String> = cals.asSequence().filter { it.accountType != ACCOUNT_TYPE_LOCAL }
        .map { it.accountName.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()

    /**
     * Whether an attendee is a person: an address, no resource by its type, and none of the calendar service's own addresses
     * (`resource.calendar.google.com` is a room whose type was not synced, `group.calendar.google.com` a calendar invited as a guest).
     */
    fun isPerson(type: Int, email: String): Boolean {
        val e = email.trim().lowercase(Locale.ROOT)
        return e.isNotEmpty() && type != TYPE_RESOURCE && !e.endsWith("calendar.google.com")
    }

    /** Who organized an event, as the rules see it. [THE_CALENDAR]: the calendar itself (a group address), or an own calendar's owner. */
    enum class Organizer(val word: String) { MINE("mine"), THE_CALENDAR("the-calendar"), ANOTHER("another"), NONE("none") }

    /**
     * Who is on an event. [others]: a person who is not the user, or an organizer who is another person: on an own calendar
     * only when the event has attendee rows, since a booking added from a file (a train, a dentist, a concert) names an
     * outside organizer and nobody else. [onIt]: an own calendar, the user among the persons, or the user its organizer.
     * [byAccount]: the user found among the persons by an account; [byOwnerOnly]: on an own calendar, only as the
     * calendar's owner (an alias, as far as the accounts go).
     */
    class Who(val others: Boolean, val onIt: Boolean, val byAccount: Boolean, val byOwnerOnly: Boolean, val persons: Int, val organizer: Organizer)

    fun who(row: Row, cal: Cal, guests: List<Guest>, mine: Set<String>): Who {
        val owner = cal.owner.trim().lowercase(Locale.ROOT)
        val persons = guests.filter { isPerson(it.type, it.email) }.map { it.email.trim().lowercase(Locale.ROOT) }
        fun me(e: String) = e in mine || (cal.own && e == owner)
        val byAccount = persons.any { it in mine }
        val byOwner = cal.own && persons.any { it == owner }
        val org = row.organizer.trim().lowercase(Locale.ROOT)
        val organizer = when {
            org.isEmpty() -> Organizer.NONE
            org in mine -> Organizer.MINE
            // A colleague's calendar is organized by the colleague: another person, though it is the calendar's owner.
            isPerson(0, org) && !(cal.own && org == owner) -> Organizer.ANOTHER
            else -> Organizer.THE_CALENDAR
        }
        // On an own calendar an outside organizer with nobody listed is a booking, not an invitation (a webinar of that kind
        // still counts by its call link). On another calendar it is whose calendar it is: a colleague's own block.
        val organizerCounts = organizer == Organizer.ANOTHER && (!cal.own || guests.isNotEmpty())
        return Who(
            others = persons.any { !me(it) } || organizerCounts,
            onIt = cal.own || byAccount || organizer == Organizer.MINE,
            byAccount = byAccount,
            byOwnerOnly = byOwner && !byAccount,
            persons = persons.size,
            organizer = organizer,
        )
    }

    // ---- the verdict

    /**
     * The user's answer to [row] (numbered as `SELF_ATTENDEE_STATUS`). On an own calendar the row's. On another (a team's, a
     * colleague's) the row's is that calendar owner's answer, so the user's own attendee row says it; of two (two accounts
     * invited) the one that counts most: yes, maybe or none asked before not answered, before no. Where the user has no
     * attendee row there, the row's.
     */
    fun answer(row: Row, cal: Cal, guests: List<Guest>, mine: Set<String>): Int {
        if (cal.own) return row.selfStatus
        return guests.filter { isPerson(it.type, it.email) && it.email.trim().lowercase(Locale.ROOT) in mine }
            .minByOrNull { answerRank(it.status) }?.status ?: row.selfStatus
    }

    private fun answerRank(status: Int) = when (status) { SELF_DECLINED -> 2; SELF_INVITED -> 1; else -> 0 }

    /** Of the rows 1 to 3 of [judge]: shown nowhere, whatever else is true. [answer]: the user's ([Meetings.answer]). */
    private fun gone(row: Row, cal: Cal?, answer: Int): Verdict? = when {
        cal == null || !cal.visible -> Verdict.HIDDEN_CALENDAR
        row.status == STATUS_CANCELED -> Verdict.CANCELED
        answer == SELF_DECLINED -> Verdict.DECLINED
        else -> null
    }

    /**
     * What [row] is, on [cal] (null: a calendar that wasn't read), with [guests] its attendees (none: not asked, or nobody
     * invited) and [mine] the user's accounts. The first that applies, in this order: a hidden calendar, canceled, declined,
     * a task, all-day, a calendar the user can't change, an unticked one, someone else's, a word in its title, free, an
     * invitation not answered, with other people, and with only the user. Tentative, accepted and no answer asked all count.
     * Declined and not answered are the user's own [answer]. One row alone: [verdicts] judges a copy on another calendar as
     * the one on the user's own.
     */
    fun judge(row: Row, cal: Cal?, guests: List<Guest>, mine: Set<String>, choices: MeetingChoices.State): Verdict {
        if (cal == null) return Verdict.HIDDEN_CALENDAR
        val said = answer(row, cal, guests, mine)
        gone(row, cal, said)?.let { return it }
        val who = who(row, cal, guests, mine)
        return when {
            row.linksTask && row.link == null && !who.others -> Verdict.TASK
            row.allDay -> Verdict.ALL_DAY
            !cal.editable -> Verdict.READ_ONLY
            cal.ident in choices.calendarsOff -> Verdict.CALENDAR_OFF
            who.others && !who.onIt -> Verdict.NOT_MINE
            hasWord(row.title, choices.words) -> Verdict.WORD
            row.availability == AVAILABILITY_FREE && !choices.free -> Verdict.FREE
            said == SELF_INVITED && !choices.unanswered -> Verdict.UNANSWERED
            who.others -> Verdict.WITH_OTHERS
            else -> when (choices.onlyMe) {
                MeetingChoices.OnlyMe.NEVER -> Verdict.ONLY_ME
                MeetingChoices.OnlyMe.CALL -> if (row.link != null) Verdict.ONLY_ME_CALL else Verdict.ONLY_ME
                MeetingChoices.OnlyMe.ALWAYS -> Verdict.ONLY_ME_ANY
            }
        }
    }

    /**
     * Whether [row]'s attendees are read: not gone (rows 1 to 3 of [judge]; on a calendar not the user's own, declined is
     * known only from the attendees, so they are read), and either a task app's link with no call link (an event with
     * others invited is no task) or timed on a calendar the user can change and has not unticked (it may be a meeting). A
     * call link doesn't spare the read: whose meeting it is decides first.
     */
    fun asksAttendees(row: Row, cal: Cal?, choices: MeetingChoices.State): Boolean {
        if (cal == null || gone(row, cal, if (cal.own) row.selfStatus else 0) != null) return false
        return (row.linksTask && row.link == null) || (!row.allDay && cal.editable && cal.ident !in choices.calendarsOff)
    }

    /** One event's copies on several calendars: the same start, end and title ([once] counts them once). */
    private fun copyKey(begin: Long, end: Long, title: String) = Triple(begin, end, title)

    /** Verdicts about a calendar, not about the event: they speak for no copy on another calendar. */
    private val ABOUT_A_CALENDAR = setOf(Verdict.HIDDEN_CALENDAR, Verdict.READ_ONLY, Verdict.CALENDAR_OFF)

    /**
     * Each row's verdict ([judge]), with one event on two calendars judged as one: where a copy is on one of the user's own
     * calendars (the same start, end and title), its verdict stands for the copies on other calendars, a verdict that shows
     * it nowhere too. The own copy carries the user's answer and the user's "free"; a team or colleague's calendar carries
     * its owner's. So an invitation declined, not answered or free on the user's own calendar is that on a shared one too.
     * A verdict about the own copy's calendar (hidden, read-only, unticked) speaks for no other copy, and a copy on a hidden
     * calendar stays hidden. Of two own copies (two accounts invited), the one that counts most: a meeting, else one shown.
     */
    fun verdicts(reading: Reading, choices: MeetingChoices.State): List<Verdict> {
        val mine = mine(reading.calendars.values)
        val judged = reading.rows.map { judge(it, reading.calendars[it.calendarId], reading.guests[it.eventId].orEmpty(), mine, choices) }
        val ownSays = HashMap<Triple<Long, Long, String>, Verdict>()
        reading.rows.forEachIndexed { i, r ->
            val v = judged[i]
            if (reading.calendars[r.calendarId]?.own != true || v in ABOUT_A_CALENDAR) return@forEachIndexed
            val key = copyKey(r.begin, r.end, r.title)
            val had = ownSays[key]
            if (had == null || weight(v) > weight(had)) ownSays[key] = v
        }
        if (ownSays.isEmpty()) return judged
        return reading.rows.mapIndexed { i, r ->
            val v = judged[i]
            if (v == Verdict.HIDDEN_CALENDAR || reading.calendars[r.calendarId]?.own != false) v
            else ownSays[copyKey(r.begin, r.end, r.title)] ?: v
        }
    }

    private fun weight(v: Verdict) = if (v.meeting) 2 else if (v.event) 1 else 0

    /** Every row of [reading] that is shown at all, as an event with its verdict ([verdicts]), by start. [noTitle] for a title left blank. */
    fun events(reading: Reading, choices: MeetingChoices.State, noTitle: String): List<Calendar.Event> {
        val verdicts = verdicts(reading, choices)
        return reading.rows.mapIndexedNotNull { i, r ->
            val cal = reading.calendars[r.calendarId]
            val v = verdicts[i]
            if (!v.event || cal == null) null else Calendar.Event(
                eventId = r.eventId, title = r.title.ifBlank { noTitle }, begin = r.begin, end = r.end, allDay = r.allDay,
                color = r.color, location = r.location, editable = cal.editable, meeting = v.meeting, link = r.link,
                calendarId = r.calendarId, verdict = v, ownCalendar = cal.own,
            )
        }.sortedBy { it.begin }
    }

    // ---- which meeting the bar stands on

    /**
     * The meeting the bar stands on at [now], of [meetings]. The one that is on (of several, the one that began last, and of
     * two that began together the one that ends first), else the next. A next one that begins before the one on now ends
     * takes over [leadMs] before its start: nothing would show it in time otherwise. Back to back, or with a gap, the one
     * that is on stays until it ends.
     */
    fun pick(meetings: List<Calendar.Event>, now: Long, leadMs: Long): Calendar.Event? {
        val current = meetings.filter { it.begin <= now && it.end > now }
            .minWithOrNull(compareByDescending<Calendar.Event> { it.begin }.thenBy { it.end })
        val next = meetings.filter { it.begin > now }.minWithOrNull(compareBy<Calendar.Event> { it.begin }.thenBy { it.end })
        if (current != null && next != null && next.begin - now <= leadMs && next.begin < current.end) return next
        return current ?: next
    }

    /**
     * One event on two calendars (an invitation from a team calendar is on it and on the user's own) counts once: of meetings
     * with the same start, end and title, the first, an own calendar's before any other. The order stays.
     */
    fun once(meetings: List<Calendar.Event>): List<Calendar.Event> {
        if (meetings.size < 2) return meetings
        val kept = HashMap<Triple<Long, Long, String>, Int>()
        meetings.forEachIndexed { i, e ->
            val key = copyKey(e.begin, e.end, e.title)
            val had = kept[key]
            if (had == null || (!meetings[had].ownCalendar && e.ownCalendar)) kept[key] = i
        }
        val chosen = kept.values.toHashSet()
        return meetings.filterIndexed { i, _ -> i in chosen }
    }

    /**
     * What Next meeting's menu lists, of [upcoming] (every meeting not ended): today's ([inHorizon]); with [any] and none
     * left today, the meetings of the first later day that has one.
     */
    fun listed(upcoming: List<Calendar.Event>, now: Long, horizon: Long, zone: ZoneId, any: Boolean): List<Calendar.Event> {
        val today = upcoming.filter { inHorizon(it.begin, it.end, now, horizon) }
        if (today.isNotEmpty() || !any) return today
        val later = upcoming.filter { it.begin >= horizon }
        val first = later.minOfOrNull { it.begin } ?: return emptyList()
        val day = dayOf(first, zone)
        return later.filter { dayOf(it.begin, zone) == day }.sortedBy { it.begin }
    }

    private fun dayOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    // ---- words in a title

    /** How many words or phrases "Leave out titles with" keeps, and how long each may be (a title is [TITLE_CHARS]). Setup says both. */
    const val MAX_WORDS = 20
    const val WORD_CHARS = 80

    /** What "Leave out titles with" keeps of [raw]: parted at commas and line breaks ([cleanWords]). */
    fun words(raw: String): List<String> = cleanWords(raw.split(',', '\n', '\r'))

    /**
     * Each as one line, blanks dropped, the same word once (ignoring case), [MAX_WORDS] at most. One longer than [WORD_CHARS]
     * is dropped whole, never kept cut: cut inside a word it would never be found, cut between words it would leave out
     * more than was asked.
     */
    fun cleanWords(words: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (w in words) {
            // Read a good way past the limit, so that what is over it is seen as over it and not cut to fit.
            val clean = TextRules.oneLine(w, WORD_CHARS * 2).trim()
            if (clean.isEmpty() || !TextRules.fits(clean, WORD_CHARS) || !seen.add(clean.lowercase(Locale.ROOT))) continue
            out += clean
            if (out.size == MAX_WORDS) break
        }
        return out
    }

    /**
     * Whether [title] holds one of [words] as a whole word, ignoring case: the character before and after it is no letter or
     * digit, or one of a script written without spaces. "Hold" finds "HOLD: desk" and "On hold", never "Shareholders call":
     * a meeting left out by accident is the worst this can do.
     */
    fun hasWord(title: String, words: List<String>): Boolean = words.any { w -> w.isNotEmpty() && found(title, w) }

    private fun found(title: String, word: String): Boolean {
        var i = 0
        while (i + word.length <= title.length) {
            if (title.regionMatches(i, word, 0, word.length, ignoreCase = true) &&
                (i == 0 || parts(title.codePointBefore(i))) &&
                (i + word.length == title.length || parts(title.codePointAt(i + word.length)))) return true
            i++
        }
        return false
    }

    /** A character a word can end at: no letter or digit, or one of a script that puts no spaces between its words. */
    private fun parts(cp: Int): Boolean = !Character.isLetterOrDigit(cp) || Character.isIdeographic(cp) ||
        Character.UnicodeScript.of(cp) in SPACELESS

    private val SPACELESS = setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR, Character.UnicodeScript.HAN)

    // ---- `event check`: the real calendar in counts

    /**
     * What `./bento debug event check` prints of [reading] for today and tomorrow (local days in [zone]): counts and reasons
     * only, never a title, an address, a calendar's name or an event's id. Each line is for a question of the device pass.
     */
    fun report(reading: Reading, choices: MeetingChoices.State, now: Long, zone: ZoneId): String {
        val mine = mine(reading.calendars.values)
        val all = verdicts(reading, choices)
        val today = dayOf(now, zone)
        val lines = ArrayList<String>()
        for ((name, day) in listOf("today" to today, "tomorrow" to today.plusDays(1))) {
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val verdicts = reading.rows.indices.filter { reading.rows[it].begin < to && reading.rows[it].end > from }.map { all[it] }
            fun n(v: Verdict) = verdicts.count { it == v }
            lines += "$name: events=${verdicts.count { it.event }} meetings=${verdicts.count { it.meeting }} " +
                "[with-others=${n(Verdict.WITH_OTHERS)} only-me-call=${n(Verdict.ONLY_ME_CALL)} only-me-any=${n(Verdict.ONLY_ME_ANY)}]"
            lines += "  not: " + listOf(Verdict.ONLY_ME, Verdict.NOT_MINE, Verdict.FREE, Verdict.UNANSWERED, Verdict.WORD, Verdict.ALL_DAY,
                Verdict.READ_ONLY, Verdict.CALENDAR_OFF).joinToString(" ") { "${it.word}=${n(it)}" }
            lines += "  gone: " + listOf(Verdict.CANCELED, Verdict.DECLINED, Verdict.TASK, Verdict.HIDDEN_CALENDAR).joinToString(" ") { "${it.word}=${n(it)}" }
        }
        val cals = reading.calendars.values
        lines += "calendars=${cals.size} accounts=${mine.size} own=${cals.count { it.own }} " +
            "editable-not-own=${cals.count { it.editable && !it.own }} read-only=${cals.count { !it.editable }} " +
            "primary-by-flag=${cals.count { it.flagPrimary }} primary-by-owner-only=${cals.count { it.own && !it.flagPrimary }}"
        // Over the two days: what the attendees, the user and the organizers look like.
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = reading.rows.filter { it.begin < to && it.end > from }
        val asked = rows.mapTo(HashSet()) { it.eventId }
        val guests = reading.guests.filterKeys { it in asked }.values.flatten()
        lines += "guests: rows=${guests.size} rooms-by-type=${guests.count { it.type == TYPE_RESOURCE }} " +
            "rooms-by-address-only=${guests.count { it.type != TYPE_RESOURCE && it.email.trim().lowercase(Locale.ROOT).endsWith("resource.calendar.google.com") }} " +
            "calendars-as-guests=${guests.count { it.email.trim().lowercase(Locale.ROOT).endsWith("group.calendar.google.com") }}" +
            (reading.guestsFailed?.let { " failed:$it" } ?: "")
        val judged = rows.mapNotNull { r ->
            reading.calendars[r.calendarId]?.takeIf { c -> gone(r, c, answer(r, c, reading.guests[r.eventId].orEmpty(), mine)) == null }?.let { r to it }
        }
        fun answered(r: Row, c: Cal) = answer(r, c, reading.guests[r.eventId].orEmpty(), mine)
        val whos = judged.map { (r, c) -> c to who(r, c, reading.guests[r.eventId].orEmpty(), mine) }
        lines += "you: found-by-account=${whos.count { it.second.byAccount }} found-by-owner-only=${whos.count { it.second.byOwnerOnly }} " +
            "own-with-guests-and-you-not-found=${whos.count { (c, w) -> c.own && w.persons > 0 && !w.byAccount && !w.byOwnerOnly }}"
        lines += "organizer: " + Organizer.entries.joinToString(" ") { o -> "${o.word}=${whos.count { it.second.organizer == o }}" }
        val events = events(reading, choices, "")
        val meetings = events.filter { it.meeting && it.end > now }
        val picked = pick(once(meetings), now, ChipRules.LEAD_MS)
        val pickedSaid = picked?.let { e ->
            val v = e.verdict?.word ?: "?"
            if (e.begin <= now) "$v on now" else "$v in ${(e.begin - now + 59_999) / 60_000}m"
        } ?: "none"
        lines += "free=${judged.count { it.first.availability == AVAILABILITY_FREE }} " +
            "unanswered=${judged.count { (r, c) -> answered(r, c) == SELF_INVITED }} tentative=${judged.count { (r, c) -> answered(r, c) == SELF_TENTATIVE }} " +
            "twice=${meetings.size - once(meetings).size} picked=$pickedSaid"
        return lines.joinToString("\n")
    }

    // ---- the horizon and what is kept of an event

    /** The horizon's time of day, tomorrow: today, plus 3 hours for a midnight or 1 a.m. meeting. */
    private val CUTOFF: LocalTime = LocalTime.of(3, 0)

    /**
     * The end of the horizon: "today, plus 3 hours". 03:00 after the current evening, so a midnight
     * or 1 a.m. meeting still counts. Between midnight and 03:00 the evening is still "today", so
     * it's 03:00 that same night, not 26 hours away. Nothing after it counts as today's.
     */
    fun horizon(now: Long, zone: ZoneId): Long {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        val day = if (t.toLocalTime() < CUTOFF) t.toLocalDate() else t.toLocalDate().plusDays(1)
        return day.atTime(CUTOFF).atZone(zone).toInstant().toEpochMilli()
    }

    /** On now, or starting before [horizon]; an event that has ended is out. */
    fun inHorizon(begin: Long, end: Long, now: Long, horizon: Long): Boolean = end > now && begin < horizon

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
