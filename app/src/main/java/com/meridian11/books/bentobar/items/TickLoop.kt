package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one clock that drives item states: ticks once a second, on the second, while anything
 * that shows items is on screen (the bar, a menu, the settings preview), and not otherwise.
 *
 * Pure Kotlin, like [Refresher]: the main thread, the clocks, the layout and the types come in through [Wiring] (`Ticker`
 * makes the app's), and the tests run it on the calling thread, by hand.
 */
open class TickLoop(private val wiring: Wiring) {
    /**
     * [post] runs on the main thread and [onMain] says whether this is it. [schedule] runs a tick [delayMs][schedule] from
     * now and [cancel] takes back one that hasn't run. [elapsed] counts sleep and is what intervals are measured in;
     * [sampleClock] is what a type's [ItemType.sample] is told; [wall] aligns a tick to the second. [type] and [config] are the
     * registry and the layout. [sampleEnv] runs the shared samplers, [clocks] the timer's and keep awake's looks at the clock.
     * [failedText] is what an item that failed says it did. [location] is told, once per tick and before the types'
     * samplers, what the items of the layout and the sampled ones are, so My location has one owner however many
     * types use it ([ItemType.usesMyLocation]); sampled is empty when nothing shows items.
     */
    class Wiring(
        val post: (Runnable) -> Unit,
        val onMain: () -> Boolean,
        val schedule: (delayMs: Long, tick: Runnable) -> Unit,
        val cancel: () -> Unit,
        val elapsed: () -> Long,
        val wall: () -> Long,
        val sampleClock: () -> Long,
        val powerSave: (elapsed: Long) -> Boolean,
        val config: () -> BarConfig,
        val type: (String) -> ItemType?,
        val sampleEnv: (now: Long, types: Set<String>) -> Unit,
        val clocks: () -> Unit,
        val failedText: (ItemType) -> String,
        val log: (what: String, error: Throwable?) -> Unit,
        val location: (items: List<ItemConfig>, sampled: List<ItemConfig>) -> Unit = { _, _ -> },
    )

    private val users = HashSet<String>()
    private val lastRun = HashMap<String, Long>()
    /** When the last scheduled tick ran: what a recompute by hand is stamped with ([TickRules.stamp]). */
    private var lastTickAt: Long? = null
    /** The samplers the last run asked for: one that isn't among them yet has no reading to show. */
    private val samplersInUse = HashSet<String>()
    /** When each item last had something to say, for [TickRules.held]; and the settings it had then (a change ends its hold). */
    private val lastActive = HashMap<String, Long>()
    private val heldFor = HashMap<String, ItemConfig>()
    private val _states = MutableStateFlow<Map<String, ItemState>>(emptyMap())
    private val _tick = MutableStateFlow(0L)

    val states: StateFlow<Map<String, ItemState>> get() = _states
    val tick: StateFlow<Long> get() = _tick

    /**
     * Something that shows items is on screen (the bar, a menu, the settings preview). Read from any
     * thread: an online service is asked only while this holds (`Http.allowed`).
     */
    @Volatile var running = false; private set

    /** The types whose [ItemType.onLive] has run and whose [ItemType.onIdle] hasn't. Main thread. */
    private val liveTypes = HashSet<String>()

    /**
     * The types that had an item in the layout at the last run, kept across [stop]: a removal while nothing showed items is
     * seen at the next run. Main thread.
     */
    private var placed: Set<String> = emptySet()

    /**
     * A type whose last item has left the layout gives back what outlives the bar (a mute, a Do Not Disturb
     * rule): [ItemType.settle] with [Settle.LEFT_LAYOUT], once. The bar hiding is no such moment: the items are still placed.
     */
    private fun settleLeft(cfg: BarConfig) {
        val now = cfg.items.mapTo(HashSet()) { it.type }
        val left = placed - now
        placed = now
        for (t in left) hook(t, "settle") { it.settle(Settle.LEFT_LAYOUT) }
    }

    /**
     * Tells the types which of them are being sampled now: [ItemType.onLive] for one that starts,
     * [ItemType.onIdle] for one that stops. A type's listeners and polls hang on these two, so none
     * exists while the bar is hidden, the screen is off, or the layout has no item of the type.
     */
    private fun lifeCycle(types: Set<String>) {
        for (t in types) if (liveTypes.add(t)) hook(t, "onLive") { it.onLive() }
        for (t in liveTypes.filter { it !in types }) {
            liveTypes -= t
            hook(t, "onIdle") { it.onIdle() }
            // What such a type last showed (a track's title) is not kept while nobody shows it.
            if (wiring.type(t)?.discreet == true) forgetStates(t)
        }
    }

    private fun forgetStates(type: String) {
        val ids = wiring.config().items.filter { it.type == type }.mapTo(HashSet()) { it.id }
        if (ids.any { it in _states.value }) _states.value = _states.value - ids
        ids.forEach { lastRun -= it; lastActive -= it; heldFor -= it }
    }

    private fun hook(type: String, what: String, call: (ItemType) -> Unit) {
        val t = wiring.type(type) ?: return
        guarded(t, what) { call(t) }
    }

    /** [type]'s own code, behind [ItemGuard]: a click, a wheel step, a slider as much as a sampler or a hook. Null when it failed. */
    fun <T> guarded(type: ItemType, what: String, call: () -> T): T? = ItemGuard.run(kindOnly(type), "$what of ${type.type} failed", wiring.log, call)

    /**
     * A type's own code failed: the tick goes on without it (also for a method this device's Android
     * doesn't have, which is no Exception). What went wrong is logged, but for a type that goes online
     * or shows the user's own things only its kind: an exception's message can quote what was being
     * read (a reply, a title).
     */
    private fun failed(type: ItemType, what: String, e: Throwable) = ItemGuard.failed(kindOnly(type), what, e, wiring.log)

    private fun kindOnly(type: ItemType) = type.online != null || type.discreet

    /**
     * The items sampled at this tick ([needed]), set before the types' samplers run: for a type whose
     * sampler asks for something only while one of its items is on screen (Weather's My location).
     * Main thread.
     */
    var sampled: List<ItemConfig> = emptyList()
        private set

    /** Hidden items are on screen (bar expanded, or BentoBar's menu lists them): sample them too. */
    @Volatile var revealHidden = false
    /** The item whose menu is open, sampled even when it's hidden. */
    @Volatile var focusItem: String? = null

    /** Items worth sampling now: [TickRules.needed]. */
    private fun needed(cfg: BarConfig): List<ItemConfig> = TickRules.needed(cfg, "settings" in users, revealHidden, focusItem, wiring.type)

    private val loop = object : Runnable {
        override fun run() {
            runOnce(scheduled = true)
            val now = wiring.wall()
            // On the second boundary; every other second under battery saver.
            val period = if (wiring.powerSave(wiring.elapsed())) 2000 else 1000
            wiring.schedule(period - now % period + 5, this)
        }
    }

    /** [who] wants ticks (e.g. "bar", "menu", "settings"); idempotent. */
    fun start(who: String) {
        if (!wiring.onMain()) { wiring.post { start(who) }; return }
        val wasIdle = users.isEmpty()
        users += who
        running = true
        if (wasIdle) { wiring.cancel(); lastRun.clear(); loop.run() }
    }

    fun stop(who: String) {
        if (!wiring.onMain()) { wiring.post { stop(who) }; return }
        users -= who
        if (users.isEmpty()) {
            wiring.cancel()
            running = false
            // Nothing shows items any more: every type lets go of what it listens to.
            lifeCycle(emptySet())
            ItemGuard.run(false, "location failed", wiring.log) { wiring.location(wiring.config().items, emptyList()) }
        }
    }

    /** Recomputes now (after a settings change or a click), without waiting for the next second. */
    fun refresh() {
        if (!wiring.onMain()) { wiring.post { refresh() }; return }
        lastRun.clear(); runOnce(scheduled = false)
    }

    /**
     * Recomputes one item now, and nothing else: no sampler runs. For what only that item shows and
     * that changes many times a second (its slider being dragged), where [refresh] would feed every
     * sampler a reading per pointer move. Not while nothing shows items: its state isn't asked for
     * then, whatever the caller meant.
     */
    fun refresh(item: ItemConfig) {
        if (!wiring.onMain()) { wiring.post { refresh(item) }; return }
        if (users.isEmpty()) return
        val current = wiring.config().items.firstOrNull { it.id == item.id } ?: return
        val type = wiring.type(current.type) ?: return
        val now = wiring.elapsed()
        _states.value = _states.value + (current.id to held(type, current, now))
        lastRun[current.id] = TickRules.stamp(now, lastTickAt)
    }

    /**
     * Recomputes the items of one type now, and nothing else: what was loaded for it has just come
     * in. No sampler runs, so an answer that arrives between two ticks puts no extra reading into
     * the network or CPU chart. Only the items that are being sampled anyway are computed, so an item
     * that isn't on screen asks for nothing; menus that redraw with the tick redraw now. Main thread.
     */
    fun refresh(type: String) {
        if (!wiring.onMain()) { wiring.post { refresh(type) }; return }
        val t = wiring.type(type) ?: return
        if (users.isEmpty()) return
        val mine = needed(wiring.config()).filter { it.type == type }
        if (mine.isEmpty()) return
        val now = wiring.elapsed()
        _states.value = _states.value + mine.associate { it.id to held(t, it, now) }
        mine.forEach { lastRun[it.id] = TickRules.stamp(now, lastTickAt) }
        _tick.value = wiring.wall()
    }

    private fun compute(type: ItemType, item: ItemConfig): ItemState = try { type.state(item) } catch (e: Throwable) {
        failed(type, "item ${item.type} failed", e)
        ItemState(icon = type.icon, text = "!", desc = wiring.failedText(type))
    }

    /**
     * [type]'s state for [item], with "has something to say" held for a while after it last had
     * ([TickRules.held]): what the strip shows by, so an item at its threshold doesn't blink. A change to
     * the item's settings ends its hold, so a threshold moved in the settings shows at once.
     */
    private fun held(type: ItemType, item: ItemConfig, now: Long): ItemState {
        val state = compute(type, item)
        if (!type.holds) return state
        if (heldFor[item.id] != item) { lastActive -= item.id; heldFor[item.id] = item }
        if (state.active) { lastActive[item.id] = now; return state }
        return if (TickRules.held(false, lastActive[item.id], now)) state.copy(active = true) else state
    }

    /** [scheduled]: the tick on the second; else a recompute by hand, stamped with the tick before it. */
    private fun runOnce(scheduled: Boolean) {
        val now = wiring.elapsed()
        if (scheduled) lastTickAt = now
        val stamp = if (scheduled) now else TickRules.stamp(now, lastTickAt)
        val cfg = wiring.config()
        val live = needed(cfg)
        sampled = live
        val types = live.mapTo(HashSet()) { it.type }
        // The types a live item keeps live with it (the Live activity's sources): told and sampled as if they had an item
        // there, though their state() is never run, since none of their items is among the live ones.
        for (item in live) {
            val woken = wiring.type(item.type)?.let { t -> guarded(t, "wakes") { t.wakes(item) } } ?: continue
            for (w in woken) if (wiring.type(w) != null) types += w
        }
        // Only while something shows items: a refresh() with nothing on screen must not wake a type that nobody would put back to sleep.
        lifeCycle(if (users.isEmpty()) emptySet() else types)
        settleLeft(cfg)
        ItemGuard.run(false, "sampling failed", wiring.log) {
            // A type may read another type's sampler (Heat reads the battery's temperature): those run for it too.
            val sampled = HashSet(types)
            for (t in types) wiring.type(t)?.samples?.let { sampled += it }
            // Only the tick on the second feeds the samplers: a click, a wheel notch or a settings change a few milliseconds after
            // the last reading is a spike in the rate and a junk point in the chart. A sampler just taken up gets its first
            // reading all the same, or what it stands for shows nothing (a battery at 0%) until the tick.
            val due = if (scheduled) sampled else sampled - samplersInUse
            samplersInUse.clear(); samplersInUse += sampled
            if (due.isNotEmpty()) wiring.sampleEnv(now, due)
            if (users.isNotEmpty()) {
                // Before the types' samplers, so one that reads where the device is sees this tick's answer; once, whoever uses it.
                ItemGuard.run(false, "location failed", wiring.log) { wiring.location(cfg.items, live) }
                for (t in types) hook(t, "sample") { it.sample(wiring.sampleClock()) }
            }
        }
        // Behind a guard of their own: a sampler that fails must not cost a timer or keep awake this tick's look at the clock.
        ItemGuard.run(false, "clock check failed", wiring.log) { wiring.clocks() }
        val next = HashMap(_states.value)
        val ids = HashSet<String>()
        cfg.items.forEach { ids += it.id }
        for (item in live) {
            val type = wiring.type(item.type) ?: continue
            val last = lastRun[item.id] ?: 0L
            if (item.id in next && !TickRules.due(now, last, type.refreshMs)) continue
            next[item.id] = held(type, item, now)
            lastRun[item.id] = stamp
        }
        next.keys.retainAll(ids)
        lastActive.keys.retainAll(ids)
        heldFor.keys.retainAll(ids)
        _states.value = next
        _tick.value = wiring.wall()
    }

    /**
     * What [states] has for [item], or its icon while the next tick computes it: safe to call while
     * composing, without running [ItemType.state] on the UI frame.
     */
    fun placeholder(item: ItemConfig): ItemState = _states.value[item.id] ?: run {
        if (running) wiring.post { if (item.id !in _states.value) refresh(item) }
        ItemState(icon = wiring.type(item.type)?.icon)
    }

    /** The debug hook's reading of one item: asked in passing, so a type must keep [ItemType.state] free of what outlives it. */
    fun stateOf(item: ItemConfig): ItemState = _states.value[item.id]
        ?: wiring.type(item.type)?.let { runCatching { it.state(item) }.getOrNull() } ?: ItemState()
}
