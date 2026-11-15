package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.items.AirLabs.Failure
import com.meridian11.books.bentobar.items.FlightRules.Tracked
import com.meridian11.books.bentobar.net.Http
import com.meridian11.books.bentobar.net.Reply
import com.meridian11.books.bentobar.net.Request
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * The Flight item's requests, and what it keeps. Everything here runs on a background thread (a
 * `Refresher`'s load and restore, an `Ask`'s work) except [take], [stop], [clearWithout], [forget] and
 * [newKey], which follow the user's own acts (the menu, the settings, the switch, a new key) on the
 * main thread and touch a few small files.
 *
 * What leaves the device: the flight number and the user's own key, to airlabs.co, and nothing else.
 * Never without a key, never while the service is switched off: asked here before any request, though
 * the request code would refuse too.
 *
 * What is kept, by the item's id and outside the layout (so in no backup and no copied settings):
 * what the item follows (the number, the day that flight leaves and the airport it leaves from) as
 * the feature's own note, and the last answer as [Tracked] with what the service sent. Switching the
 * service off deletes the answers and keeps the notes; removing the key deletes both. The key itself
 * is `Online`'s and is added to the request by the transport; it goes into nothing that is kept, shown or said.
 */
object FlightLoad {
    private const val OWN = "flight"
    private val service = Online.Service.AIRLABS
    private val json = Json { ignoreUnknownKeys = true }
    /** The answer for an item and its note are read and written under this, so that a load that comes back late can't put an old flight over a new one. */
    private val lock = Any()

    /**
     * What an item was told to follow: the number as it was entered, closed up, the day that flight
     * leaves at its own airport, and that airport. A number can fly more than once a day, and the
     * airport says which of its flights this is. (None in a note from before there was a choice: any
     * airport's then, as it was.)
     */
    @Serializable
    private class Following(val number: String, val day: String? = null, val from: String? = null)

    /** One press of Track: which item asks, the number, the day that was chosen (null: the next flight), and where: a day chip is a day of the device's ([zone]). */
    data class Question(val item: String, val number: FlightNumber, val day: LocalDate? = null, val zone: ZoneId? = null) {
        /** Whose flight it is stays out of anything that prints a value. */
        override fun toString(): String = "Question(a flight)"
    }

    /** What a press of Track came to. */
    sealed interface Outcome {
        /** The flight, as it is kept once the answer is taken. */
        class Found(val tracked: Tracked) : Outcome
        /**
         * The number flies more than once on the day that was asked for: the flights to choose from, in
         * the order they leave, each as it is kept once it is the one chosen. Nothing is followed, and
         * nothing kept, until then.
         */
        class Several(val flights: List<Tracked>) : Outcome
        class Failed(val failure: Failure) : Outcome
        /** Nothing was asked (no key, the service switched off, the bar gone under the lookup): there is nothing to say. */
        data object Unasked : Outcome

        /**
         * While this waits to be acted on, the bar shows the number as it does while it is looked up: a
         * flight until it is taken, which is the next thing to happen, and several for as long as the
         * menu asks which.
         */
        val waits: Boolean get() = this is Found || this is Several

        /** One of the several flights a press of Track found, by its place in the list; null where this is no list, or it has no such place. */
        fun chosen(index: Int): Tracked? = (this as? Several)?.flights?.getOrNull(index)
    }

    /** What is done with what a press of Track came to, when its turn comes ([turn]). */
    enum class Turn { TAKE, DROP, KEEP }

    /**
     * What becomes of the one press of Track there is at a time ([state]) when a tick or a menu looks
     * at it. [focused]: the item whose menu is open, null for none. [opening]: that menu opens just
     * now, so whatever is here was found while it was closed.
     *
     * A flight that was found is taken, whoever looks: one flight is never left for want of a menu,
     * and the lookups it cost are not lost. Several flights are a question for the menu of their
     * item, for as long as it stays open: with it closed nobody is there to say which, and a list
     * found waiting when the menu opens again is from an earlier look (a hidden item has no tick
     * that would have let it go), so none is taken and the question is dropped. Anything else stays:
     * a lookup on its way, and what went wrong, which waits for the menu to say it.
     */
    fun turn(state: Ask.State<Question, Outcome>, focused: String?, opening: Boolean = false): Turn {
        val done = state as? Ask.State.Done ?: return Turn.KEEP
        return when (done.answer) {
            is Outcome.Found -> Turn.TAKE
            is Outcome.Several -> if (focused == done.question.item && !opening) Turn.KEEP else Turn.DROP
            else -> Turn.KEEP
        }
    }

    /** How many lookups the key has left this month, as the last reply said; null: no reply has yet. It is the key's: another key starts with none. */
    @Volatile var left: Int? = null
        private set

    private fun said(count: Int?) { if (count != null) left = count }

    /** The numbers that were not found, and when: finding that out costs two lookups, so it is remembered for an hour (in memory only). */
    private val missed = ConcurrentHashMap<String, Pair<Failure, Long>>()

    private fun name(q: Question) = "${q.number.code}@${q.day}"

    private fun notFoundLately(q: Question, now: Long): Failure? {
        val (failure, at) = missed[name(q)] ?: return null
        if (now - at in 0 until AirLabs.keep(failure).toMillis()) return failure
        missed.remove(name(q))
        return null
    }

    /** [text] and the chosen [day] as a question for [item], or null: what is not a flight number is never asked. */
    fun question(item: String, text: String, day: LocalDate?, zone: ZoneId? = null): Question? = FlightNumber.read(text)?.let { Question(item, it, day, zone) }

    private fun mayAsk() = Online.hasKey(service) && Online.on(service)

    /**
     * Finds the flight for a press of Track, or the flights where the number flies more than once on
     * the day that was asked for: two requests in the usual case (the one flight, and the timetable,
     * which says how often the number flies), three at most. A number that was not found in the last
     * hour is not asked for again.
     */
    fun track(q: Question, now: Long): Outcome {
        if (!mayAsk()) return Outcome.Unasked
        notFoundLately(q, now)?.let { return Outcome.Failed(it) }
        val a = AirLabs.candidates(q.number, q.day, Instant.ofEpochMilli(now), q.zone) { Http.get(it) } ?: return Outcome.Unasked
        said(a.left)
        if (a.flights.isEmpty()) {
            val failure = a.failure ?: Failure.NO_ANSWER
            if (failure == Failure.NOT_FOUND || failure == Failure.NOT_THAT_DAY) {
                if (missed.size >= 32) missed.clear()
                missed[name(q)] = failure to now
            }
            return Outcome.Failed(failure)
        }
        val found = a.flights.map { f ->
            Tracked(q.number.code, day(f), f, askedAt = now, heardAt = now, left = a.left, alertSince = FlightRules.alertSince(f, null, now), from = f.from.code)
        }
        return found.singleOrNull()?.let { Outcome.Found(it) } ?: Outcome.Several(found)
    }

    /** The day [f] leaves, at its own airport: what an item follows is that day's flight. */
    private fun day(f: Flight): String? = (f.from.planned ?: f.from.time)?.toLocalDate()?.toString()

    /**
     * The answer to a press of Track, or the one of several that was chosen, becomes what [item]
     * follows, in place of what it followed before. Main thread, when the answer is taken, never in
     * the lookup itself: an answer that nobody takes (a newer question overtook it, or nobody chose)
     * changes nothing. False if it could not be kept.
     */
    fun take(item: String, found: Tracked, now: Long): Boolean = synchronized(lock) {
        val note = runCatching { json.encodeToString(Following.serializer(), Following(found.number, found.day, found.from)) }.getOrNull() ?: return false
        if (!Kept.own(OWN).write(item, note, now)) return false
        store(item, found, now)
        true
    }

    /** [item] follows nothing any more: its note and the answer are deleted. */
    fun stop(item: String) = synchronized(lock) {
        Kept.own(OWN).remove(item)
        Kept.fetched(service).remove(item)
    }

    private fun following(item: String): Following? {
        val entry = Kept.own(OWN).read(item) ?: return null
        val asked = runCatching { json.decodeFromString(Following.serializer(), entry.text) }.getOrNull()?.takeIf { it.number.isNotEmpty() }
        if (asked == null) Kept.own(OWN).remove(item)
        return asked
    }

    /** (The airport too: another flight of the same number on the same day is not this one.) */
    private fun Following.isFor(t: Tracked) = number == t.number && day == t.day && from == t.from

    /** Nothing a file holds is taken on trust either: a flight the rules could trip over is no flight. */
    private fun decode(text: String): Tracked? =
        runCatching { json.decodeFromString(Tracked.serializer(), text) }.getOrNull()?.takeIf { t -> t.flight?.let(FlightRules::sound) != false }

    /** Only the model made here is ever written: what a reply said, field by field, and never its text. Not kept while the service is off. */
    private fun store(item: String, t: Tracked, now: Long) {
        runCatching { json.encodeToString(Tracked.serializer(), t) }.getOrNull()?.let { Kept.fetched(service).write(item, it, now) }
    }

    /**
     * For this long after the last Flight item went from the layout what was kept for one is left alone: a layout
     * that was pasted or put back may be followed by another within the moment. It is not Undo's time. The Bar page's
     * Undo is offered until the next change there, however long that takes, and it needs no wait: an item that Undo
     * can still bring back counts as there ([FlightItem.ids]).
     */
    const val UNDO_MS = 30_000L

    /**
     * With no Flight item left in the layout, what was kept for one goes for good: the number, the day
     * and the last answer. Asked [UNDO_MS] after the last item went, with the [items] there are then.
     * True if it cleared; false, and nothing touched, while there is an item (it came back, or another
     * was added, whose first load removes what belonged to the one that is gone).
     */
    fun clearWithout(items: Set<String>): Boolean = synchronized(lock) {
        if (items.isNotEmpty()) return false
        Kept.own(OWN).clear()
        Kept.fetched(service).clear()
        true
    }

    /**
     * A Flight item is gone for good, or the app starts: what is kept for an item that is not among [items] goes, and
     * with no item left, all of it. [items]: the Flight items of the layout, and one Undo can still bring back among
     * them.
     */
    fun sweep(items: Set<String>) = synchronized(lock) { if (!clearWithout(items)) tidy(items) }

    /** What belonged to an item that is no longer in the layout goes with it. (With an empty [items] the last one went a moment ago: [clearWithout] comes [UNDO_MS] later.) */
    private fun tidy(items: Set<String>) {
        if (items.isEmpty()) return
        Kept.own(OWN).keepOnly(items)
        Kept.fetched(service).keepOnly(items)
    }

    /**
     * What was kept for [item] from an earlier run, and how old it is: a restart shows the flight
     * without asking. Null: nothing was (or not for what the item follows now). A flight that is
     * [FlightRules.cleared] is put away here. [items]: the ids of the Flight items in the layout.
     */
    fun kept(item: String, now: Long, items: Set<String>): Refresher.Restored<Tracked>? = synchronized(lock) {
        tidy(items)
        val entry = Kept.fetched(service).read(item) ?: return null
        val t = decode(entry.text)
        if (t == null || following(item)?.isFor(t) != true) {
            Kept.fetched(service).remove(item)
            return null
        }
        if (FlightRules.cleared(t, Instant.ofEpochMilli(now))) {
            stop(item)
            return Refresher.Restored(Tracked(), 0)
        }
        t.left?.let { if (left == null) left = it }
        Refresher.Restored(t, (now - entry.savedAt).coerceAtLeast(0))
    }

    /**
     * Asks again about what [item] follows: [AirLabs.again], one request in the usual case. With
     * nothing heard of it yet (the service was switched off and on again, which deletes the answer and
     * keeps the note) it is the lookup for that number on its day from its airport, which finds the
     * flight that was followed and never asks which. [last]: what was known. The answer is a [Tracked]
     * whatever came of it: a failed ask keeps the flight and says why.
     *
     * Null: nothing to say. The request was not sent (no key, switched off, the bar gone), or the item
     * follows something else by now.
     */
    fun load(item: String, last: Tracked?, now: Long, items: Set<String>): Tracked? {
        if (!mayAsk()) return null
        val asked = synchronized(lock) { tidy(items); following(item) } ?: return Tracked()
        val was = last?.takeIf { asked.isFor(it) }
        val number = FlightNumber.read(asked.number)
        if (number == null || (was != null && FlightRules.cleared(was, Instant.ofEpochMilli(now)))) {
            // It is put away, unless the item was told to follow something else this very moment: then that is another load's business.
            return synchronized(lock) {
                if (following(item)?.let { it.number == asked.number && it.day == asked.day && it.from == asked.from } == true) { stop(item); Tracked() } else null
            }
        }
        val flight = was?.flight
        val next = (if (was == null || flight == null) lookUp(asked, number, was, now) else again(number, was, flight, now)) ?: return null
        return synchronized(lock) {
            if (following(item)?.isFor(next) == true) next.also { store(item, it, now) } else null
        }
    }

    private fun lookUp(asked: Following, number: FlightNumber, was: Tracked?, now: Long): Tracked? {
        val day = asked.day?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val went = Went()
        val a = AirLabs.lookup(number, day, asked.from, Instant.ofEpochMilli(now), went::get) ?: return null
        said(a.left)
        val left = a.left ?: was?.left
        val f = a.flight ?: return Tracked(asked.number, asked.day, failure = a.failure ?: Failure.NO_ANSWER,
            failures = went.counted(was?.failures ?: 0), askedAt = now, left = left, from = asked.from)
        return Tracked(asked.number, asked.day, f, askedAt = now, heardAt = now, left = left, alertSince = FlightRules.alertSince(f, null, now), from = asked.from)
    }

    /**
     * Asks through [get], and says whether any of a lookup's requests left the device: the failures in a row grow only then,
     * as for Weather. Without a network nothing went out and the back-off doesn't grow; a connection that broke after the
     * request was sent may have cost a lookup, and does.
     */
    private class Went {
        private var any = false
        fun get(request: Request): Reply = Http.get(request).also { if (it.sent) any = true }
        fun counted(failures: Int): Int = if (any) failures + 1 else failures
    }

    private fun again(number: FlightNumber, was: Tracked, flight: Flight, now: Long): Tracked? {
        val went = Went()
        val r = AirLabs.again(number, flight, Instant.ofEpochMilli(now), went::get) ?: return null
        said(r.left)
        val left = r.left ?: was.left
        return when (val a = r.again) {
            is AirLabs.Again.Is -> was.copy(flight = a.flight, failure = null, failures = 0, askedAt = now, heardAt = now, left = left,
                alertSince = FlightRules.alertSince(a.flight, was.alertSince, now), ended = false, waitSec = null)
            // The service is not there yet: the plan stands, as of now.
            AirLabs.Again.NotYet -> was.copy(failure = null, failures = 0, askedAt = now, heardAt = now, left = left, waitSec = null)
            // A key that is refused or used up may be put right, and the flight is still the one that is followed; anything else is the end of it.
            AirLabs.Again.Gone -> (r.failure == Failure.REFUSED || r.failure == Failure.USED_UP).let { ofTheKey ->
                was.copy(failure = r.failure.takeIf { ofTheKey }, failures = 0, askedAt = now, left = left, ended = !ofTheKey, waitSec = null)
            }
            AirLabs.Again.Failed -> was.copy(failure = r.failure ?: Failure.NO_ANSWER, failures = went.counted(was.failures), askedAt = now,
                left = left, waitSec = r.retryAfterSec)
        }
    }

    /**
     * The service was switched off, or ([keyGone]) its key removed: what it said is forgotten here too
     * (`Online` has emptied the answers already). Without the key, what the items followed goes as
     * well: Remove key deletes the key, the flight and the answer.
     */
    fun forget(keyGone: Boolean) {
        left = null
        missed.clear()
        if (keyGone) synchronized(lock) { Kept.own(OWN).clear() }
    }

    /**
     * A new key was saved: how many lookups the old one had left says nothing about it. The count goes
     * from here and from every answer that is kept, which stays as old as it was. Left in, a flight
     * that was not asked about for want of lookups would never be asked about again, and its menu
     * would say "few lookups left" of a key that has a thousand.
     */
    fun newKey() {
        left = null
        synchronized(lock) {
            val kept = Kept.fetched(service)
            for (name in kept.names()) {
                val entry = kept.read(name) ?: continue
                val t = decode(entry.text)?.takeIf { it.left != null } ?: continue
                runCatching { json.encodeToString(Tracked.serializer(), t.copy(left = null)) }.getOrNull()?.let { kept.write(name, it, entry.savedAt) }
            }
        }
    }
}
