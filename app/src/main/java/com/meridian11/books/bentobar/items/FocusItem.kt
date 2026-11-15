package com.meridian11.books.bentobar.items

import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.FocusRules.Session
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym

/**
 * The one Focus session and every road to its end, without Android (`FocusSettleTest`): [Wiring] is Do Not Disturb, the
 * alarm, the kept file and the clock. Main thread.
 *
 * The session is kept on disk ([Kept.own], "focus"), so a restart goes on with it. Until this keeper has read that file
 * ([loaded]) it knows of no session; the first road that needs to know reads it (off the main thread) and goes on when it
 * is in. A change made meanwhile (a click) wins over what is read. Every end, whichever road reaches it (the alarm, the
 * tick, a [Settle] moment, "End now"), is [end]: Do Not Disturb off, the session forgotten, its alarm cancelled.
 */
class FocusKeeper(private val w: Wiring) {
    class Wiring(
        val quiet: FocusQuiet,
        /** Sets the alarm for that wall-clock time, in place of any before it; null cancels it. */
        val alarm: (Long?) -> Unit,
        val kept: () -> Kept,
        val wall: () -> Long,
        /** The layout has a Focus item. */
        val placed: () -> Boolean,
        val background: (() -> Unit) -> Unit,
        val main: (() -> Unit) -> Unit,
        /** What the item shows has changed. */
        val changed: () -> Unit = {},
        /** A road failed after what was kept had to be read, where no caller's guard is left to catch it. */
        val failed: (Exception) -> Unit = {},
    )

    var session: Session? = null; private set
    var loaded = false; private set
    /** The last start was refused by Android: the menu says so until the next one. */
    var refused = false; private set

    private var loading = false
    private val waiting = ArrayList<() -> Unit>()
    /** When Android was last asked whether the mode is still on (wall clock). */
    private var checkedAt: Long? = null

    fun granted(): Boolean = w.quiet.granted()

    /** Runs [then] once what was kept has been read: at once if it has. It never throws (a later run has no caller to throw to). */
    fun load(then: () -> Unit = {}) {
        if (loaded) { guarded(then); return }
        waiting += then
        if (loading) return
        loading = true
        w.background {
            val text = try { w.kept().read(NAME)?.text } catch (e: Exception) { null }
            w.main {
                loading = false
                if (!loaded) {
                    loaded = true
                    session = FocusRules.read(text)
                    if (session != null) w.changed()
                }
                val ready = waiting.toList()
                waiting.clear()
                ready.forEach(::guarded)
            }
        }
    }

    private fun guarded(block: () -> Unit) {
        try { block() } catch (e: Exception) { w.failed(e) }
    }

    /** A click, or Start in the menu: true if a session began. Without the access nothing is asked of Android. */
    fun start(minutes: Int, dark: Boolean): Boolean {
        if (!w.quiet.granted()) return false
        refused = !w.quiet.on(dark)
        if (refused) { w.changed(); return false }
        set(FocusRules.start(w.wall(), minutes, dark))
        return true
    }

    /** "+10 min". */
    fun more() { session?.let { set(FocusRules.extend(it, FocusRules.MORE)) } }

    /** The end, whichever road reached it. */
    fun end() {
        if (session == null) return
        try { w.quiet.off() } finally { set(null) }
    }

    /** The tick, while an item is live: a session past its end ends; one switched off in Android's settings is forgotten. */
    fun sample() = load {
        val s = session ?: return@load
        val now = w.wall()
        if (FocusRules.over(s, now)) { end(); return@load }
        val at = checkedAt
        if (at != null && now - at < CHECK_MS && now >= at) return@load
        checkedAt = now
        if (FocusRules.seen(s, w.quiet.isOn()) == null) set(null)
    }

    /** The alarm came: the session ends if its time is up, else (the clock moved) its alarm is set again. [done] runs once, after. */
    fun alarm(done: () -> Unit) = load {
        try {
            val s = session
            if (FocusRules.over(s, w.wall())) end()
            else s?.endsAt?.takeIf { it != 0L }?.let(w.alarm)
        } finally {
            done()
        }
    }

    /** Exact alarms were switched in Setup: a session with an end gets its alarm again, of the kind now allowed. */
    fun rearm() = load { session?.endsAt?.takeIf { it != 0L }?.let(w.alarm) }

    fun settle(why: Settle) = load {
        val s = session
        when (FocusRules.settle(s, why, w.wall(), w.placed())) {
            FocusRules.Then.END -> end()
            FocusRules.Then.REARM -> s?.endsAt?.takeIf { it != 0L }?.let(w.alarm)
            FocusRules.Then.KEEP -> {}
        }
    }

    /** No Focus item is left anywhere: BentoBar's mode leaves Android's list too (a new session makes it again). */
    fun removeMode() { if (loaded && session == null) w.quiet.remove() }

    private fun set(s: Session?) {
        session = s
        loaded = true
        w.background {
            try {
                if (s == null) w.kept().remove(NAME) else w.kept().write(NAME, FocusRules.write(s), w.wall())
            } catch (e: Exception) {
                // The session in memory stands; a restart then finds the one before it, which its own road ends.
            }
        }
        w.alarm(s?.endsAt?.takeIf { it != 0L })
        w.changed()
    }

    companion object {
        /** The file the session is kept in, under [Kept.own] "focus". */
        const val NAME = "session"
        /** How often the tick asks Android whether the mode is still on. */
        private const val CHECK_MS = 5_000L
    }
}

/** Focus on the device: the mode, the alarm, the kept file, the clock and the thread. */
private object FocusOnDevice {
    val wiring = FocusKeeper.Wiring(
        quiet = FocusQuietOnDevice,
        alarm = { FocusAlarm.arm(Env.app, it) },
        kept = { Kept.own("focus") },
        wall = Now::wall,
        placed = { Store.config.value.items.any { it.type == FocusItem.type } },
        background = { Background.executor(FocusItem.type).execute(it) },
        main = { Background.onMain(it) },
        changed = { Ticker.refresh(FocusItem.type) },
        failed = { Log.w(Env.TAG, "Focus failed: ${it.javaClass.simpleName}") },
    )
}

/**
 * Focus: a click turns on Do Not Disturb for a while ("BentoBar Focus", a mode in Android's own list), and it goes off
 * again at its time, whatever BentoBar is doing then. While a session runs a click opens the menu (time left, +10 min,
 * End now). One session at a time, whichever Focus item began it. Its rules are [FocusRules], its roads [FocusKeeper].
 */
object FocusItem : ItemType("focus", R.string.item_focus_title, Sym.SELF_IMPROVEMENT, R.string.item_focus_desc) {
    override val group = ItemGroup.TIME
    override val canBeActive = true
    override val liveSource = true
    override val needs = listOf(Access.DO_NOT_DISTURB)
    override val trigger = Trigger(R.string.trigger_focus, R.string.trigger_focus_short)

    internal val minutes = Opt.oneOf("minutes", "50", "25", "50", "90", "0")
    /** The dark theme during a session: Android 15 and up, where a mode can bring it. */
    internal val dark = Opt.bool("dark", false)
    internal val darkOffered get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    /** The session and its roads. A test gives it fakes. */
    internal var keeper = FocusKeeper(FocusOnDevice.wiring)

    /** Whether the access is given, as last asked (on going live, opening the menu, every few seconds, a click). */
    @Volatile private var grantedNow: Boolean? = null
    private var grantedAt: Long? = null

    /** Debug builds: a look staged from adb, in place of the real session and access. Nothing real is switched while it stands. */
    private data class Staged(val granted: Boolean, val session: Session?)
    @Volatile private var staging: Staged? = null

    internal fun granted(): Boolean = staging?.granted ?: grantedNow ?: keeper.granted().also { grantedNow = it }
    /** The session the bar and the menu show. */
    internal fun shown(): Session? = staging.let { if (it != null) it.session else keeper.session }
    internal val refused get() = staging == null && keeper.refused

    private fun minutesOf(item: ItemConfig) = FocusRules.minutes(minutes.of(item))
    private fun darkOf(item: ItemConfig) = darkOffered && dark.of(item)

    override fun onLive() {
        grantedNow = null
        keeper.load()
    }

    override fun menuOpening(item: ItemConfig) {
        grantedNow = keeper.granted()
        keeper.load()
    }

    override fun sample(now: Long) {
        keeper.sample()
        val at = grantedAt
        if (at == null || now - at >= 5_000L || now < at) { grantedAt = now; grantedNow = keeper.granted() }
    }

    override fun settle(why: Settle) = keeper.settle(why)

    /** From Setup, when exact alarms are switched: see [FocusKeeper.rearm]. */
    internal fun rearm() = keeper.rearm()

    /** From [FocusEnd]: the alarm at a session's end. */
    internal fun alarmRang(done: () -> Unit) = keeper.alarm(done)

    override fun state(item: ItemConfig): ItemState {
        val s = shown()
        if (s == null) return ItemState(icon = Sym.SELF_IMPROVEMENT, filled = false,
            desc = if (!granted()) Env.str(R.string.focus_noaccess_desc) else readyDesc(item))
        val now = Now.wall()
        val left = FocusRules.left(s, now)
        return ItemState(icon = Sym.SELF_IMPROVEMENT, filled = true, text = FocusRules.text(s, now, Env.str(R.string.common_on)), active = true,
            widthKey = if (left != null) "focus" else null, desc = runningDesc(left))
    }

    private fun readyDesc(item: ItemConfig): String = minutesOf(item).let { m ->
        if (m == 0) Env.str(R.string.focus_ready_endless_desc) else Env.str(R.string.focus_ready_desc, Fmt.spoken(m * 60_000L))
    }

    private fun runningDesc(left: Long?): String =
        if (left == null) Env.str(R.string.focus_endless_desc) else Env.str(R.string.focus_left_desc, Fmt.spokenLeft(left))

    override fun onClick(item: ItemConfig): Boolean = try {
        // Until the kept session is in, a click can't know whether one runs: the menu, never a second session.
        if (staging != null || !keeper.loaded) false
        else {
            val granted = keeper.granted().also { grantedNow = it }
            when (FocusRules.click(keeper.session, granted)) {
                // Refused by Android: the menu says so.
                FocusRules.Click.START -> keeper.start(minutesOf(item), darkOf(item))
                FocusRules.Click.MENU, FocusRules.Click.ASK -> false
            }
        }
    } catch (e: Exception) {
        Log.w(Env.TAG, "Focus click failed: ${e.javaClass.simpleName}")
        false
    }

    /** Start in the menu. */
    internal fun start(item: ItemConfig) = act {
        val s = staging
        if (s != null) staging = s.copy(session = FocusRules.start(Now.wall(), minutesOf(item), darkOf(item)))
        else keeper.start(minutesOf(item), darkOf(item))
    }

    internal fun more() = act {
        val s = staging
        if (s != null) staging = s.copy(session = s.session?.let { FocusRules.extend(it, FocusRules.MORE) })
        else keeper.more()
    }

    internal fun endNow() = act {
        val s = staging
        if (s != null) staging = s.copy(session = null) else keeper.end()
    }

    /** A menu's button: never throws into Compose. */
    private fun act(block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.w(Env.TAG, "Focus failed: ${e.javaClass.simpleName}") }
        Ticker.refresh(type)
    }

    override fun liveNow(now: Long, items: List<ItemConfig>): LiveNow? {
        val s = shown() ?: return null
        val wall = Now.wall()
        val left = FocusRules.left(s, wall)
        return LiveNow(LiveRank.FOCUS, Sym.SELF_IMPROVEMENT, FocusRules.text(s, wall, Env.str(R.string.common_on)), runningDesc(left),
            since = now - (wall - s.startedAt).coerceAtLeast(0L))
    }

    /** Its mode stays in Android's list while a Focus item could come back; once none is left anywhere, it goes. */
    override fun forgetOwn(id: String) {
        try {
            if (Store.config.value.items.none { it.type == type }) keeper.removeMode()
        } catch (e: Exception) {
            Log.w(Env.TAG, "Focus forget failed: ${e.javaClass.simpleName}")
        }
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host -> FocusMenu(item, host) }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set -> FocusOptions(item, set) }

    // ---- for tests on a device (debug builds)

    override val stages = listOf("ready", "running", "endless", "noaccess").map { "stage $it" }

    /**
     * `focus`: what it knows. `focus stage ready|running|endless|noaccess`: that look in the bar and the menu, with nothing
     * real switched (the menu's buttons change the staged session only); `focus off` or `focus stage off` ends it.
     */
    override fun debug(args: List<String>): String? = when {
        args.isEmpty() -> said()
        args == listOf("off") || args == listOf("stage", "off") -> { staging = null; Ticker.refresh(type); said() }
        args.size != 2 || args[0] != "stage" -> null
        else -> {
            val now = Now.wall()
            val min = 60_000L
            staging = when (args[1]) {
                "ready" -> Staged(true, null)
                "running" -> Staged(true, Session(now - 25 * min - 50_000, now + 24 * min + 10_000, false))
                "endless" -> Staged(true, Session(now - 12 * min, 0L, false))
                "noaccess" -> Staged(false, null)
                else -> return null
            }
            Ticker.refresh(type)
            said()
        }
    }

    private fun said(): String {
        val s = shown()
        val what = when { s == null -> "none"; s.endsAt == 0L -> "endless"; else -> "ends in ${(s.endsAt - Now.wall()) / 1000}s" }
        return "focus: session=$what loaded=${keeper.loaded} granted=${granted()} refused=$refused staged=${staging != null}"
    }
}
