package com.meridian11.books.bentobar.items

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.FlightRules.Tracked
import com.meridian11.books.bentobar.items.FlightSamples.Staged
import com.meridian11.books.bentobar.items.FlightText.Count
import com.meridian11.books.bentobar.items.FlightText.TimeForm
import com.meridian11.books.bentobar.items.FlightText.Word
import com.meridian11.books.bentobar.util.Dates
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * Flight: follows one flight, whose number the user enters in the item's menu. The bar counts down to
 * its departure with the gate, then to its landing, and says when it runs late; the times come from
 * AirLabs, with a key of the user's own. One of the two item types that go online ([online]), and
 * only once a key was saved on this install.
 *
 * Where things are: what a flight is doing is [FlightRules]' to say, the words are [FlightText]'s, the
 * requests and what is kept [FlightLoad]'s, the menu and the key's settings are in `FlightMenu.kt`.
 * This object holds them together: the loader for what each item follows ([tracker]: nothing is
 * scheduled, a flight is asked about when its item is looked at and its answer has grown old), the
 * one lookup at a time for a press of Track ([search]), whose flight is taken as it comes or, where
 * the number flies more than once that day, once the menu was told which ([choose]), and what a test
 * stages in their place.
 *
 * The key is `Online`'s. It is read in [FlightLoad], for the request, and is in no state, no
 * description, no tooltip and nothing a debug hook prints.
 */
object FlightItem : ItemType("flight", R.string.item_flight_title, Sym.FLIGHT, R.string.item_flight_desc) {
    override val group = ItemGroup.ONLINE
    override val onlineNote = R.string.about_privacy_flight
    override val refreshMs = 10_000L
    override val menuWidthDp = 340
    override val online = Online.Service.AIRLABS
    override val canBeActive = true
    // Which flight someone follows is their own business: the debug hook that prints what an item shows says how long this one's text is, not what it says.
    override val discreet = true

    /** How many hours before departure the item comes out. */
    private val before = Threshold("beforeHours", 24, 3..48, step = 3) { Env.plural(R.plurals.common_hours, it, it) }
    override val trigger = Trigger(R.string.trigger_flight, R.string.trigger_flight_short, before)


    /** What each item follows and what was last heard of it, by the item's id. A restart reads it back; nothing is asked while it is fresh. */
    internal val tracker: Refresher<String, Tracked> = Background.refresher(type, online,
        every = { _, t -> FlightRules.every(t, Instant.ofEpochMilli(Now.wall())) },
        restore = { id -> FlightLoad.kept(id, Now.wall(), ids()) },
        load = { id, last -> FlightLoad.load(id, last, Now.wall(), ids()) })

    /** A press of Track: one lookup at a time, whose answer is taken by [takeAnswer] or, where it is several flights, by [choose]. */
    internal val search: Ask<FlightLoad.Question, FlightLoad.Outcome> = Background.ask(type, online) { q -> FlightLoad.track(q, Now.wall()) }

    /**
     * What an item was just told to follow, or to follow no more, until the loader has read that back
     * from where it is kept (a moment): the bar and the menu go from one thing to the next with
     * nothing empty between. Main thread.
     */
    private val fresh = HashMap<String, Tracked>()

    /**
     * The flight an item follows is kept under its id, outside the layout. The item was removed and no Undo can bring
     * it back: nothing is held for it, and what was kept for it on the device goes.
     */
    override fun forgetOwn(id: String) {
        tracker.forget(id)
        fresh.remove(id)
        Background.executor(type).execute { runCatching { FlightLoad.sweep(ids()) } }
    }

    /** What the item [id] follows, as far as that is known: the loader's word, or what was taken a moment ago. Null: not read yet. Main thread. */
    internal fun followed(id: String): Tracked? = tracker.peek(id)?.also { fresh.remove(id) } ?: fresh[id]

    /** How many answers were taken since the app started: a menu that waits for one sees by it that one came, whoever took it. Main thread. */
    internal var takes = 0
        private set

    /** Where each flight's plane was last drawn on its line, in the bar or in the menu, by [FlightText.plane]: it only goes forward. Main thread. */
    private val places = HashMap<String, Double>()

    /** Debug builds: what the first Flight item shows instead of the real thing (`./bento debug flight show NAME`); nothing is asked for it. */
    @Volatile private var staged: Staged? = null
    @Volatile private var stagedAs: String? = null

    /** What [item] shows in the real thing's place, if a sample is staged and it is the first Flight item. */
    internal fun stagedFor(item: ItemConfig): Staged? = staged?.takeIf { first()?.id == item.id }

    private fun first(): ItemConfig? = Store.config.value.items.firstOrNull { it.type == type }

    // ---- the words and the clock for the pure rules

    private val utc: ZoneId = ZoneId.of("UTC")

    /**
     * The app's words, and how a time is written here (the system's 12 or 24 hours, the locale's
     * layout). A time comes in as a wall-clock reading (an airport's own, mostly) and is written as it
     * stands: it is never moved into the device's zone.
     */
    internal fun voice(): FlightText.Voice {
        val res = Env.app.resources
        val time = Dates.timeSkeleton(DateFormat.is24HourFormat(Env.app))
        return FlightText.Voice(Locale.getDefault(), ZoneId.systemDefault(),
            word = { res.getString(string(it)) },
            plural = { c, n ->
                res.getQuantityString(when (c) {
                    Count.COMMON_HOURS -> R.plurals.common_hours
                    Count.COMMON_MINUTES -> R.plurals.common_minutes
                    Count.FLIGHT_LOOKUPS_LEFT -> R.plurals.flight_lookups_left
                    Count.FLIGHT_CHOICE_TIMES -> R.plurals.flight_choice_times
                    Count.FLIGHT_CHOICE_FLIGHTS -> R.plurals.flight_choice_flights
                }, n, n)
            },
            clock = { t, form ->
                Dates.format(when (form) {
                    TimeForm.TIME -> time
                    TimeForm.DAY -> "EEE"
                    TimeForm.DAY_TIME -> "EEE$time"
                    TimeForm.WEEKDAY_TIME -> "EEEE$time"
                    TimeForm.DATE -> "MMMd"
                    TimeForm.DATE_TIME -> "MMMd$time"
                    TimeForm.DAY_DATE -> "EEEMMMd"
                }, t.toEpochSecond(ZoneOffset.UTC) * 1000, utc)
            })
    }

    private fun string(w: Word): Int = when (w) {
        Word.COMMON_NO_CONNECTION -> R.string.common_no_connection
        Word.COMMON_UPDATED -> R.string.common_updated
        Word.COMMON_UPDATED_OFFLINE -> R.string.common_updated_offline
        Word.COMMON_UPDATED_NO_ANSWER -> R.string.common_updated_no_answer
        Word.COMMON_DURATION_MIN -> R.string.common_duration_min
        Word.COMMON_DURATION_H_MIN -> R.string.common_duration_h_min
        Word.FLIGHT_BAR_LOOKING -> R.string.flight_bar_looking
        Word.FLIGHT_BAR_PLAN -> R.string.flight_bar_plan
        Word.FLIGHT_BAR_GATE -> R.string.flight_bar_gate
        Word.FLIGHT_BAR_LANDED_BELT -> R.string.flight_bar_landed_belt
        Word.FLIGHT_BAR_LANDED_AT -> R.string.flight_bar_landed_at
        Word.FLIGHT_BAR_CANCELED -> R.string.flight_bar_canceled
        Word.FLIGHT_BAR_DIVERTED -> R.string.flight_bar_diverted
        Word.FLIGHT_BAR_NO_UPDATE -> R.string.flight_bar_no_update
        Word.FLIGHT_BAR_NOT_LIVE -> R.string.flight_bar_not_live
        Word.FLIGHT_BAR_LATE -> R.string.flight_bar_late
        Word.FLIGHT_BAR_EARLY -> R.string.flight_bar_early
        Word.FLIGHT_TOOLTIP -> R.string.flight_tooltip
        Word.FLIGHT_NONE -> R.string.flight_none
        Word.FLIGHT_LOOKING_UP -> R.string.flight_looking_up
        Word.FLIGHT_ANOTHER_SUBTITLE -> R.string.flight_another_subtitle
        Word.FLIGHT_HEADER -> R.string.flight_header
        Word.FLIGHT_ROUTE -> R.string.flight_route
        Word.FLIGHT_LEAVES_AT -> R.string.flight_leaves_at
        Word.FLIGHT_LEAVES_IN -> R.string.flight_leaves_in
        Word.FLIGHT_LANDS_IN -> R.string.flight_lands_in
        Word.FLIGHT_IN_AIR -> R.string.flight_in_air
        Word.FLIGHT_JUST_LANDED -> R.string.flight_just_landed
        Word.FLIGHT_LANDED_AGO -> R.string.flight_landed_ago
        Word.FLIGHT_LANDED -> R.string.flight_landed
        Word.FLIGHT_CANCELED -> R.string.flight_canceled
        Word.FLIGHT_DIVERTED -> R.string.flight_diverted
        Word.FLIGHT_TIMETABLE -> R.string.flight_timetable
        Word.FLIGHT_NO_UPDATE -> R.string.flight_no_update
        Word.FLIGHT_BADGE_PLANNED -> R.string.flight_badge_planned
        Word.FLIGHT_BADGE_ON_TIME -> R.string.flight_badge_on_time
        Word.FLIGHT_BADGE_DELAYED -> R.string.flight_badge_delayed
        Word.FLIGHT_BADGE_LATE -> R.string.flight_badge_late
        Word.FLIGHT_BADGE_EARLY -> R.string.flight_badge_early
        Word.FLIGHT_TERMINAL -> R.string.flight_terminal
        Word.FLIGHT_BELT -> R.string.flight_belt
        Word.FLIGHT_OPERATED_AS -> R.string.flight_operated_as
        Word.FLIGHT_NOTE -> R.string.flight_note
        Word.FLIGHT_STATUS_REFUSED -> R.string.flight_status_refused
        Word.FLIGHT_STATUS_USED_UP -> R.string.flight_status_used_up
        Word.FLIGHT_STATUS_FEW -> R.string.flight_status_few
        Word.FLIGHT_COPY_HEAD -> R.string.flight_copy_head
        Word.FLIGHT_COPY_LEFT -> R.string.flight_copy_left
        Word.FLIGHT_COPY_LANDS -> R.string.flight_copy_lands
        Word.FLIGHT_ERR_NOT_FOUND -> R.string.flight_err_not_found
        Word.FLIGHT_ERR_NOT_THAT_DAY -> R.string.flight_err_not_that_day
        Word.FLIGHT_ERR_REFUSED -> R.string.flight_err_refused
        Word.FLIGHT_ERR_USED_UP -> R.string.flight_err_used_up
        Word.FLIGHT_ERR_NO_ANSWER -> R.string.flight_err_no_answer
        Word.FLIGHT_CHOICE_DAY -> R.string.flight_choice_day
        Word.FLIGHT_CHOICE_NEXT -> R.string.flight_choice_next
        Word.FLIGHT_CHOICE_SPAN -> R.string.flight_choice_span
        Word.FLIGHT_DESC_CHOICE -> R.string.flight_desc_choice
        Word.FLIGHT_DESC_CHOICE_LEAVES -> R.string.flight_desc_choice_leaves
        Word.FLIGHT_DESC_CHOICE_LANDS -> R.string.flight_desc_choice_lands
        Word.FLIGHT_DESC_LEAVES_AT -> R.string.flight_desc_leaves_at
        Word.FLIGHT_DESC_LEAVES_IN -> R.string.flight_desc_leaves_in
        Word.FLIGHT_DESC_LANDS_IN -> R.string.flight_desc_lands_in
        Word.FLIGHT_DESC_LANDED -> R.string.flight_desc_landed
        Word.FLIGHT_DESC_LATE -> R.string.flight_desc_late
        Word.FLIGHT_DESC_EARLY -> R.string.flight_desc_early
        Word.FLIGHT_DESC_ON_TIME -> R.string.flight_desc_on_time
        Word.FLIGHT_DESC_GATE -> R.string.flight_desc_gate
        Word.FLIGHT_DESC_BELT -> R.string.flight_desc_belt
        Word.FLIGHT_DESC_CANCELED -> R.string.flight_desc_canceled
        Word.FLIGHT_DESC_DIVERTED -> R.string.flight_desc_diverted
        Word.FLIGHT_DESC_NO_UPDATE -> R.string.flight_desc_no_update
        Word.FLIGHT_DESC_NOT_LIVE -> R.string.flight_desc_not_live
        Word.FLIGHT_DESC_LOOKING -> R.string.flight_desc_looking
        Word.FLIGHT_DESC_HEADLINE_BADGE -> R.string.flight_desc_headline_badge
        Word.FLIGHT_ROUTE_FROM -> R.string.flight_route_from
        Word.FLIGHT_ROUTE_TO -> R.string.flight_route_to
        Word.FLIGHT_ROUTE_FROM_CODE -> R.string.flight_route_from_code
        Word.FLIGHT_ROUTE_TO_CODE -> R.string.flight_route_to_code
        Word.FLIGHT_TIME_WAS -> R.string.flight_time_was
    }

    // ---- in the bar

    override fun state(item: ItemConfig): ItemState {
        // The rules are made to take whatever a reply or a file holds; should one trip all the same, the bar has its plane and no "!".
        val bar = try { bar(item) } catch (_: RuntimeException) { FlightText.Bar(Sym.FLIGHT, desc = Env.str(R.string.item_flight_title)) }
        return ItemState(icon = bar.icon, text = bar.text, desc = bar.desc, active = bar.active, tone = bar.tone, tooltip = bar.tooltip, textLimit = FlightText.LIMIT,
            route = bar.route)
    }

    private fun bar(item: ItemConfig): FlightText.Bar {
        val now = Instant.ofEpochMilli(Now.wall())
        val hours = before.of(item)
        when (val s = stagedFor(item)) {
            is Staged.Following -> return inBar(item, s.tracked, null, now, hours)
            is Staged.Looking -> return FlightText.bar(null, s.number, now, hours, voice())
            is Staged.Several -> return FlightText.bar(null, s.number, now, hours, voice())
            is Staged.Failed, Staged.Empty -> return FlightText.bar(null, null, now, hours, voice())
            Staged.NoKey -> return FlightText.Bar(Sym.FLIGHT, desc = Env.str(R.string.flight_desc_not_set_up))
            null -> {}
        }
        // One plane while there is nothing to follow with: no key, or switched off. Nothing is wanted then, so nothing is asked.
        if (!Online.hasKey(online)) return FlightText.Bar(Sym.FLIGHT, desc = Env.str(R.string.flight_desc_not_set_up))
        if (!Online.on(online)) return FlightText.Bar(Sym.FLIGHT, desc = Env.str(R.string.flight_desc_off))
        tracker.want(item.id)
        val t = followed(item.id)
        return inBar(item, t, looking(item.id, t), now, hours)
    }

    /**
     * What [item] shows of [t] at [now]: with the flight's route line, unless the item is shown as text
     * alone (the line stands in the glyph's place). The plane is where the times put it and never
     * behind where it was last drawn, here or on the menu's line: the two are one plane. Main thread.
     */
    private fun inBar(item: ItemConfig, t: Tracked?, looking: String?, now: Instant, hours: Int): FlightText.Bar {
        val plane = t?.let(FlightText::plane)
        val bar = FlightText.bar(t, looking, now, hours, voice(), line = item.display.line, shown = plane?.let(places::get))
        if (plane != null) FlightText.flown(bar.route)?.let { drawn(plane, it) }
        return bar
    }

    override val liveSource = true

    /**
     * For the Live activity: the first Flight item's flight from boarding until it lands ([LiveRules.flight]), in the words
     * its bar has as text alone (no line: the words keep their tones). Nothing without a key or with the service off. Woken
     * with that item hidden, nobody runs [state], so this asks for its flight as [state] does: what is fresh is not asked
     * for again.
     */
    override fun liveNow(now: Long, items: List<ItemConfig>): LiveNow? {
        val item = items.firstOrNull() ?: return null
        val t = when (val s = stagedFor(item)) {
            is Staged.Following -> s.tracked
            null -> {
                if (!Online.hasKey(online) || !Online.on(online)) return null
                tracker.want(item.id)
                followed(item.id)
            }
            else -> null
        } ?: return null
        val at = Instant.ofEpochMilli(Now.wall())
        val f = t.flight ?: return null
        val since = LiveRules.flight(FlightRules.shown(f, at, t.ended), at) ?: return null
        val bar = FlightText.bar(t, null, at, before.of(item), voice())
        val text = bar.text ?: return null
        return LiveNow(LiveRank.FLIGHT, bar.icon, text, bar.desc, bar.tone, since = now - (at.toEpochMilli() - since.toEpochMilli()).coerceAtLeast(0))
    }

    /**
     * The number that is being looked up for the item [id], as it is shown: a press of Track (until
     * its answer is taken, which is the next thing to happen once a flight was found, and while
     * several flights wait for the menu to be told which), or a flight that is asked for afresh.
     */
    internal fun looking(id: String, t: Tracked?): String? {
        val asked = when (val s = search.state.value) {
            is Ask.State.Busy -> s.question
            is Ask.State.Done -> s.question.takeIf { s.answer.waits }
            else -> null
        }
        return asked?.takeIf { it.item == id }?.number?.shown
            ?: t?.takeIf { it.following && it.flight == null && tracker.loading(id) }?.let { FlightNumber.shown(it.number) }
    }

    /** Where the plane of [card]'s flight is drawn now: where the times put it, but never behind where it was drawn before. */
    internal fun place(card: FlightText.Card): Double? = FlightRules.forward(places[card.plane], card.share).also { if (it != null) drawn(card.plane, it) }

    /** The plane called [plane] was drawn [at] this share of its way, in the bar or in the menu. */
    private fun drawn(plane: String, at: Double) {
        if (places.size > 16) places.clear()
        places[plane] = at
    }

    // ---- what the menu and the settings do

    /** A press of Track. The bar shows the number while it is looked up. (It ends a staged sample: the real thing is asked for.) */
    internal fun track(q: FlightLoad.Question, item: ItemConfig) {
        if (stagedFor(item) != null) unstage()
        search.ask(q)
        Ticker.refresh(item)
    }

    /**
     * The answer to a press of Track becomes what its item follows, if the item is still there. From
     * the menu as soon as it is in; with the next tick if the menu was closed meanwhile, so the
     * lookups it cost are not lost. Main thread. True if a flight was taken.
     *
     * Several flights are no answer to take: the item's menu asks which ([choose]). With that menu
     * closed (it was closed while the number was looked up) there is nobody to ask, and none of them
     * is taken: the question is dropped, and nothing is followed or kept for it. Which of the three
     * it is, is [FlightLoad.turn]'s to say.
     */
    internal fun takeAnswer(): Boolean {
        val state = search.state.value
        return when (FlightLoad.turn(state, Ticker.focusItem)) {
            FlightLoad.Turn.KEEP -> false
            FlightLoad.Turn.DROP -> { drop(); false }
            FlightLoad.Turn.TAKE -> (state as Ask.State.Done).let { take(it.question.item, (it.answer as FlightLoad.Outcome.Found).tracked) }
        }
    }

    /**
     * The menu of [item] opens. Several flights found for it while the menu was closed are not
     * asked about now: a hidden item has no tick that would have let them go, and a list from an
     * earlier look is nobody's question. Before the menu reads what there is to show. Main thread.
     */
    override fun menuOpening(item: ItemConfig) {
        if (FlightLoad.turn(search.state.value, focused = item.id, opening = true) == FlightLoad.Turn.DROP) drop()
    }

    /**
     * One of the several flights a press of Track found was chosen in the menu: [index], in the order
     * they are listed. It becomes what [item] follows, as a single answer does. Main thread. True if
     * it was taken. (Of a staged list the one chosen is shown as followed, and nothing is kept.)
     */
    internal fun choose(item: ItemConfig, index: Int): Boolean {
        (stagedFor(item) as? Staged.Several)?.let { s ->
            stage(Staged.Following(s.flights.getOrNull(index) ?: return false), "${stagedAs.orEmpty()} ${index + 1}")
            return true
        }
        val done = search.state.value as? Ask.State.Done ?: return false
        val chosen = done.answer.chosen(index)?.takeIf { done.question.item == item.id } ?: return false
        return take(item.id, chosen)
    }

    /** [found] becomes what the item [id] follows, if the item is still there: the lookup that brought it is over. */
    private fun take(id: String, found: Tracked): Boolean {
        search.clear()
        if (id !in ids()) return false
        fresh[id] = found
        takes++
        val now = Now.wall()
        tracker.forget(id)
        Background.executor(type).execute { FlightLoad.take(id, found, now) }
        tracker.want(id)
        // What Track found is the one-flight question's word or the timetable's plan; the coming hours' list has the flight as it is.
        if (FlightRules.askAtOnce(found, Instant.ofEpochMilli(now))) tracker.refresh(id, afterRunning = true)
        // The bar has the flight now, and an open menu draws again.
        Ticker.refresh(type)
        return true
    }

    /**
     * A press of Track that did not end with a flight followed was read, or the text changed, or the
     * menu closed: its message goes, and so do the flights it offered to choose from. Nothing is
     * followed for it and nothing kept. (A staged list goes the same way, and leaves the staged field.)
     */
    internal fun dropFailure(id: String) {
        if (staged is Staged.Several && first()?.id == id) { stage(Staged.Empty, "none"); return }
        val done = search.state.value as? Ask.State.Done ?: return
        if (done.question.item != id || done.answer is FlightLoad.Outcome.Found) return
        if (done.answer is FlightLoad.Outcome.Several) drop() else search.clear()
    }

    /** The flights that waited to be chosen from are let go. The bar showed their number meanwhile: it is drawn again. */
    private fun drop() {
        search.clear()
        Ticker.refresh(type)
    }

    /** Stop tracking: the item follows nothing, and what was kept for it is deleted. */
    internal fun stop(item: ItemConfig) {
        if (stagedFor(item) != null) { unstage(); return }
        fresh[item.id] = Tracked()
        tracker.forget(item.id)
        Background.executor(type).execute { FlightLoad.stop(item.id) }
        tracker.want(item.id)
        Ticker.refresh(type)
    }

    /** What is kept for [id] has changed: what was known is dropped, and it is read anew (which asks nobody). */
    private fun reread(id: String) {
        tracker.forget(id)
        tracker.want(id)
    }

    /** Whether Refresh has anything to do for [id] yet: not while an ask is on its way, nor within two minutes of the last (ten seconds, if that one reached nobody). */
    internal fun mayRefresh(id: String): Boolean {
        val t = tracker.peek(id) ?: return false
        return !tracker.loading(id) && (tracker.age(id) ?: Long.MAX_VALUE) >= FlightRules.byHand(t)
    }

    /** Refresh has nothing to do for [id] because the service just answered: the entry says "up to date". Not after a try that failed, nor while one is on its way. */
    internal fun upToDate(id: String): Boolean {
        val t = tracker.peek(id) ?: return false
        return !tracker.loading(id) && t.failure == null && t.flight != null && (tracker.age(id) ?: Long.MAX_VALUE) < FlightRules.byHand(t)
    }

    internal fun refresh(id: String) {
        tracker.peek(id)?.let { tracker.refresh(id, floorMs = FlightRules.byHand(it)) }
    }

    /** "Turn on flights": the user's own act, for a service that was set up here (it has a key). */
    internal fun turnOn() {
        Online.turnOn(online)
        Ticker.refresh()
    }

    /**
     * Saves the user's key, which turns the service on. How many lookups the old key had left goes
     * from everything that is kept and known: it says nothing of the new one. A flight that waited
     * for a key (the old one was refused or used up, or had too few lookups left) is asked about at
     * once, if its item is in the bar; every other flight keeps its turn: a new key is no reason to ask.
     */
    internal fun saveKey(key: String): Boolean {
        if (!Online.saveKey(online, key)) return false
        Background.executor(type).execute { FlightLoad.newKey() }
        val now = Instant.ofEpochMilli(Now.wall())
        for (item in Store.config.value.items) {
            if (item.type != type) continue
            val known = tracker.peek(item.id) ?: continue
            val waited = FlightRules.waitsForKey(known, now)
            // What is known is read back from what is kept, which has lost the old key's count; until then the item shows it without.
            fresh[item.id] = known.copy(left = null)
            reread(item.id)
            if (waited) tracker.refresh(item.id, afterRunning = true)
        }
        Ticker.refresh()
        return true
    }

    /** Remove key: the key, what the items follow and what was fetched all go ([forgetFetched] is told). */
    internal fun removeKey() = Online.removeKey(online)

    /** The flight's page on the web, in the browser: the user's own act, and no request of BentoBar's. Found by the callsign, which is letters and digits only. */
    internal fun openPage(callsign: String) {
        Env.launch(Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("https").authority("www.flightaware.com").appendPath("live").appendPath("flight").appendPath(callsign).build()))
    }

    /** Where a key is got: the service's sign-up page, in the browser. */
    internal fun openSignUp() {
        Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(AirLabs.SIGN_UP)))
    }

    // ---- the life cycle

    /** Once a second while an item of this type is looked at: an answer that came with no menu open to take it is taken here. */
    override fun sample(now: Long) { takeAnswer() }

    /**
     * No Flight item is looked at any more: what was held for items that are gone from the layout is
     * let go. If that was the last Flight item, what is kept for it on the device goes too, a moment
     * later ([FlightLoad.UNDO_MS]) if none is back.
     */
    override fun onIdle() {
        val items = ids()
        tracker.keepOnly(items)
        fresh.keys.retainAll(items)
        if (items.isEmpty()) Background.later(FlightLoad.UNDO_MS) {
            if (ids().isEmpty()) {
                tracker.forget(); fresh.clear(); places.clear()
                Background.executor(type).execute { FlightLoad.clearWithout(ids()) }
            }
        }
    }

    /**
     * At the app's start: nothing stays kept for an item the layout no longer has. Neither [onIdle] nor
     * [forgetOwn] sees every way an item goes: a pasted layout tells neither while another Flight
     * item stays, and a process that ended while Undo still held it, or inside the half minute after
     * the last item went, never got to clear. No Undo outlives the process, so there is nothing to wait for here.
     */
    internal fun sweepAtStart() {
        Background.executor(type).execute { runCatching { FlightLoad.sweep(ids()) } }
    }

    /** The service was switched off or its key removed: the loader and the lookup have forgotten already; this is the rest. */
    override fun forgetFetched() {
        val keyGone = !Online.hasKey(online)
        FlightLoad.forget(keyGone = false)
        if (keyGone) Background.executor(type).execute { FlightLoad.forget(keyGone = true) }
        fresh.clear()
        places.clear()
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host -> FlightMenu(item, host) }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, _ -> FlightKey(item) }

    // ---- for tests on a device (debug builds)

    private fun unstage() {
        staged = null
        stagedAs = null
        places.clear()
        Ticker.refresh()
    }

    /**
     * `flight show NAME [TURN]` shows a sample in the first Flight item's place, made for the (staged)
     * clock's moment; `flight show` lists the names; `flight off` ends it; `flight` says what is staged
     * and how it reads now, after the clock was moved. `flight show several` and `several-next` stage
     * the flights of a number that flies three times a day, to choose from in the item's menu (closing
     * the menu drops them, as it does the real ones); a row's number after either is that flight,
     * followed. A sample is a made-up flight. Of the real thing nothing is printed but whether there is
     * a key and a switch: never the key, and never a number the user entered.
     */
    override val stages get() = FlightSamples.stages

    override fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        null -> "items=${ids().size} key=${Online.hasKey(online)} on=${Online.on(online)} staged=" + (staged?.let { "${stagedAs.orEmpty()}: ${reads(it)}" } ?: "nothing")
        "off" -> { unstage(); "the real thing again" }
        "show" -> show(args.getOrNull(1), args.getOrNull(2))
        else -> null
    }

    private fun show(name: String?, turn: String?): String? {
        if (name == null) return FlightSamples.names
        if (first() == null) return null
        val sample = FlightSamples.of(name, turn, Now.wall()) ?: return null
        stage(sample, listOfNotNull(name, turn).joinToString(" "))
        return "$stagedAs: ${reads(sample)}"
    }

    /** [sample] is what the first Flight item shows from now on, under the name [called]. */
    private fun stage(sample: Staged, called: String) {
        staged = sample
        stagedAs = called
        places.clear()
        Ticker.refresh()
    }

    /**
     * How a staged sample reads at the clock's moment: the bar's text and color, its line (how far along
     * the plane stands by the times, and how the flight does), and the menu's headline and badge.
     */
    private fun reads(sample: Staged): String {
        val now = Instant.ofEpochMilli(Now.wall())
        val hours = first()?.let(before::of) ?: before.default
        val v = voice()
        return when (sample) {
            is Staged.Following -> {
                val b = FlightText.bar(sample.tracked, null, now, hours, v, line = first()?.display?.line != false)
                val c = FlightText.card(sample.tracked, now, v)
                val line = b.route?.let { String.format(Locale.ROOT, "%.2f %s", it.share, it.stands) + (if (it.struck) " struck" else "") + (if (it.whole) " whole" else "") } ?: "none"
                "bar \"${b.text.orEmpty()}\" tone=${b.tone} active=${b.active} line=$line | menu \"${c?.headline.orEmpty()}\" badge \"${c?.badge.orEmpty()}\" | ${c?.note.orEmpty()}"
            }
            is Staged.Failed -> "menu \"${FlightText.error(sample.failure, sample.number, null, v)}\""
            is Staged.Looking -> "bar \"${FlightText.bar(null, sample.number, now, hours, v).text.orEmpty()}\""
            is Staged.Several -> {
                val rows = sample.flights.mapNotNull { it.flight }.joinToString(" | ") { f -> FlightText.choice(f, now, v).let { "${it.title}, ${it.detail}" } }
                "bar \"${FlightText.bar(null, sample.number, now, hours, v).text.orEmpty()}\" | menu \"${FlightText.several(sample.number, sample.day, sample.flights.size, v)}\" | $rows"
            }
            Staged.Empty -> "no flight tracked"
            Staged.NoKey -> "not set up"
        }
    }
}
