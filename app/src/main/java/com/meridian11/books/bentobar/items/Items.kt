package com.meridian11.books.bentobar.items

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.util.Now
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Every item type, in catalog order. */
object Items {
    val all: List<ItemType> = listOf(
        CpuItem, NetworkItem, BatteryItem, MemoryItem, StorageItem, HeatItem, DevicesItem, DisplaysItem,
        CalendarItem, EventItem, ClockItem, TimerItem, CountdownItem,
        CaffeineItem, MediaItem, SoundItem, ClipboardItem, CloudClipboardItem, RemoteKeyboardItem, ToolsItem,
        AntigravityItem, CodexItem, GrokItem, GrokBotItem,
        ShortcutItem, FolderItem, AppItem, TextItem, SpacerItem,
        WeatherItem, AirItem, FlightItem, StocksItem, FxItem, AiUsageItem,
    )
    private val byType = all.associateBy { it.type }

    fun of(type: String): ItemType? = byType[type]
}

/**
 * The one clock that drives item states: ticks once a second, on the second, while anything
 * that shows items is on screen (the bar, a menu, the settings preview), and not otherwise.
 */
object Ticker {
    private const val TAG = "BentoBar"
    private val main = Handler(Looper.getMainLooper())
    private val users = HashSet<String>()
    private val lastRun = HashMap<String, Long>()
    /** When the last scheduled tick ran: what a recompute by hand is stamped with ([TickRules.stamp]). */
    private var lastTickAt: Long? = null
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
     * Tells the types which of them are being sampled now: [ItemType.onLive] for one that starts,
     * [ItemType.onIdle] for one that stops. A type's listeners and polls hang on these two, so none
     * exists while the bar is hidden, the screen is off, or no item of the type is outside Off.
     */
    private fun lifeCycle(types: Set<String>) {
        for (t in types) if (liveTypes.add(t)) hook(t, "onLive") { it.onLive() }
        for (t in liveTypes.filter { it !in types }) {
            liveTypes -= t
            hook(t, "onIdle") { it.onIdle() }
            // What such a type last showed (a track's title) is not kept while nobody shows it.
            if (Items.of(t)?.discreet == true) forgetStates(t)
        }
    }

    private fun forgetStates(type: String) {
        val ids = Store.config.value.items.filter { it.type == type }.mapTo(HashSet()) { it.id }
        if (ids.any { it in _states.value }) _states.value = _states.value - ids
        ids.forEach { lastRun -= it; lastActive -= it; heldFor -= it }
    }

    private inline fun hook(type: String, what: String, call: (ItemType) -> Unit) {
        val t = Items.of(type) ?: return
        try { call(t) } catch (e: Throwable) { failed(t, "$what of $type failed", e) }
    }

    /**
     * A type's own code failed: the tick goes on without it (also for a method this device's Android
     * doesn't have, which is no Exception). What went wrong is logged, but for a type that goes online
     * or shows the user's own things only its kind: an exception's message can quote what was being
     * read (a reply, a title).
     */
    private fun failed(type: ItemType, what: String, e: Throwable) {
        if (e is VirtualMachineError) throw e
        if (type.online != null || type.discreet) Log.w(TAG, "$what: ${e.javaClass.simpleName}") else Log.w(TAG, what, e)
    }

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
    private fun needed(cfg: BarConfig): List<ItemConfig> = TickRules.needed(cfg, "settings" in users, revealHidden, focusItem)

    private val loop = object : Runnable {
        override fun run() {
            runOnce(scheduled = true)
            val now = System.currentTimeMillis()
            // On the second boundary; every other second under battery saver.
            val period = if (Env.powerSave(SystemClock.elapsedRealtime())) 2000 else 1000
            main.postAtTime(this, TAG, SystemClock.uptimeMillis() + (period - now % period) + 5)
        }
    }

    /** [who] wants ticks (e.g. "bar", "menu", "settings"); idempotent. */
    fun start(who: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { start(who) }; return }
        val wasIdle = users.isEmpty()
        users += who
        running = true
        if (wasIdle) { main.removeCallbacksAndMessages(TAG); lastRun.clear(); loop.run() }
    }

    fun stop(who: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { stop(who) }; return }
        users -= who
        if (users.isEmpty()) {
            main.removeCallbacksAndMessages(TAG)
            running = false
            // Nothing shows items any more: every type lets go of what it listens to.
            lifeCycle(emptySet())
        }
    }

    /** Recomputes now (after a settings change or a click), without waiting for the next second. */
    fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refresh() }; return }
        lastRun.clear(); runOnce(scheduled = false)
    }

    /**
     * Recomputes one item now, and nothing else: no sampler runs. For what only that item shows and
     * that changes many times a second (its slider being dragged), where [refresh] would feed every
     * sampler a reading per pointer move.
     */
    fun refresh(item: ItemConfig) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refresh(item) }; return }
        val current = Store.config.value.items.firstOrNull { it.id == item.id } ?: return
        val type = Items.of(current.type) ?: return
        val now = SystemClock.elapsedRealtime()
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
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refresh(type) }; return }
        val t = Items.of(type) ?: return
        if (users.isEmpty()) return
        val mine = needed(Store.config.value).filter { it.type == type }
        if (mine.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        _states.value = _states.value + mine.associate { it.id to held(t, it, now) }
        mine.forEach { lastRun[it.id] = TickRules.stamp(now, lastTickAt) }
        _tick.value = System.currentTimeMillis()
    }

    private fun compute(type: ItemType, item: ItemConfig): ItemState = try { type.state(item) } catch (e: Throwable) {
        failed(type, "item ${item.type} failed", e)
        ItemState(icon = type.icon, text = "!", desc = Env.str(R.string.item_failed, type.title))
    }

    /**
     * [type]'s state for [item], with "has something to say" held for a while after it last had
     * ([TickRules.held]): what the strip shows by, so an item at its threshold doesn't blink. A change to
     * the item's settings ends its hold, so a threshold moved in the settings shows at once.
     */
    private fun held(type: ItemType, item: ItemConfig, now: Long): ItemState {
        val state = compute(type, item)
        if (!TickRules.holds(type.type)) return state
        if (heldFor[item.id] != item) { lastActive -= item.id; heldFor[item.id] = item }
        if (state.active) { lastActive[item.id] = now; return state }
        return if (TickRules.held(false, lastActive[item.id], now)) state.copy(active = true) else state
    }

    /** [scheduled]: the tick on the second; else a recompute by hand, stamped with the tick before it. */
    private fun runOnce(scheduled: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (scheduled) lastTickAt = now
        val stamp = if (scheduled) now else TickRules.stamp(now, lastTickAt)
        val cfg = Store.config.value
        val live = needed(cfg)
        sampled = live
        val types = live.mapTo(HashSet()) { it.type }
        // Only while something shows items: a refresh() with nothing on screen must not wake a type that nobody would put back to sleep.
        lifeCycle(if (users.isEmpty()) emptySet() else types)
        try {
            // A type may read another type's sampler (Heat reads the battery's temperature): those run for it too.
            val sampled = HashSet(types)
            for (t in types) Items.of(t)?.samples?.let { sampled += it }
            Env.tick(now, sampled)
            if (users.isNotEmpty()) for (t in types) hook(t, "sample") { it.sample(Now.elapsed()) }
            Timers.check()
            Caffeine.check()
        } catch (e: Exception) {
            Log.w(TAG, "sampling failed", e)
        }
        val next = HashMap(_states.value)
        val ids = HashSet<String>()
        cfg.items.forEach { ids += it.id }
        for (item in live) {
            val type = Items.of(item.type) ?: continue
            val last = lastRun[item.id] ?: 0L
            if (item.id in next && !TickRules.due(now, last, type.refreshMs)) continue
            next[item.id] = held(type, item, now)
            lastRun[item.id] = stamp
        }
        next.keys.retainAll(ids)
        lastActive.keys.retainAll(ids)
        heldFor.keys.retainAll(ids)
        _states.value = next
        _tick.value = System.currentTimeMillis()
    }

    /**
     * What [states] has for [item], or its icon while the next tick computes it: safe to call while
     * composing, without running [ItemType.state] on the UI frame.
     */
    fun placeholder(item: ItemConfig): ItemState = _states.value[item.id] ?: run {
        if (running) main.post { if (item.id !in _states.value) refresh(item) }
        ItemState(icon = Items.of(item.type)?.icon)
    }

    fun stateOf(item: ItemConfig): ItemState = _states.value[item.id]
        ?: Items.of(item.type)?.let { runCatching { it.state(item) }.getOrNull() } ?: ItemState()
}
