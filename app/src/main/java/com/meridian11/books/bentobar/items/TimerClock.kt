package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.Prefs
import com.meridian11.books.bentobar.items.Timers.Mode
import com.meridian11.books.bentobar.items.Timers.Phase
import com.meridian11.books.bentobar.items.Timers.State
import com.meridian11.books.bentobar.util.ClockAnchor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * The one shared timer's rules, as [Timers] runs them: when a countdown or a Pomodoro round ends, what comes next, how a
 * clock change is followed. Pure Kotlin, like [Refresher]: the clock, the saved state and the Android side (the alarm, the
 * alert, the chip) come in through [Wiring], and the tests run it by hand.
 */
open class TimerClock(private val w: Wiring) {
    /**
     * [now] is the wall clock and [anchor] the same moment in real time too. [arm] sets the alarm and the in-process
     * callback for what is left, in milliseconds, or takes them back with null. [done] says a timer ended, [changed] that
     * the state did (the Live Update chip follows it).
     */
    class Wiring(
        val now: () -> Long,
        val anchor: () -> ClockAnchor,
        val prefs: () -> Prefs,
        val arm: (delayMs: Long?) -> Unit,
        val done: (Done) -> Unit,
        val changed: () -> Unit,
        val log: (String) -> Unit,
        /** The user's Stop: the finished timer's alert, which is what rings, goes with it. */
        val stopped: () -> Unit = {},
    )

    /** What is said when a timer ends. */
    sealed interface Done {
        /** A Pomodoro phase ended, [after]; [next] starts now and lasts [minutes]. */
        data class Pomodoro(val after: Phase, val next: Phase, val minutes: Int) : Done
        data class Countdown(val label: String, val lengthMs: Long) : Done
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> get() = _state

    /** Wall time the last timer finished; the bar flashes the item for a minute after. */
    @Volatile var finishedAt = 0L; private set

    private var loaded = false

    /** Reads what was saved, finishes a timer whose time is up, and arms the alarm again for one that's still running. */
    fun load() {
        loaded = true
        _state.value = w.prefs().string("state")?.let { runCatching { json.decodeFromString(State.serializer(), it) }.getOrNull() }
        finishedAt = w.prefs().long("finishedAt", 0)
        check()
        // Alarms don't survive a reboot (and a process restart loses the in-process check): arm
        // again for a timer that's still running.
        _state.value?.let { if (it.running) schedule(it) }
    }

    /** Re-arms the alarm, e.g. after exact alarms were switched on or off in Setup. */
    fun reschedule() { if (loaded) _state.value?.let { if (it.running) schedule(it) } }

    fun now() = w.now()

    fun remaining(s: State, now: Long = now()): Long = when {
        s.mode == Mode.STOPWATCH -> 0
        s.running -> (s.at - now).coerceAtLeast(0)
        else -> s.pausedMs
    }

    fun elapsed(s: State, now: Long = now()): Long = when {
        s.mode != Mode.STOPWATCH -> s.lengthMs - remaining(s, now)
        s.running -> (now - s.at).coerceAtLeast(0)
        else -> s.pausedMs
    }

    fun startTimer(ms: Long, label: String = "") =
        set(State(Mode.TIMER, true, now() + ms, lengthMs = ms, label = label))

    fun startStopwatch() = set(State(Mode.STOPWATCH, true, now()))

    fun startPomodoro() = set(State(Mode.POMODORO, true, now() + Timers.POMO_WORK, lengthMs = Timers.POMO_WORK, phase = Phase.WORK))

    fun pause() {
        val s = _state.value ?: return
        if (!s.running) return
        set(s.copy(running = false, pausedMs = if (s.mode == Mode.STOPWATCH) elapsed(s) else remaining(s)))
    }

    fun resume() {
        val s = _state.value ?: return
        if (s.running) return
        set(s.copy(running = true, at = if (s.mode == Mode.STOPWATCH) now() - s.pausedMs else now() + s.pausedMs))
    }

    fun toggle() { val s = _state.value ?: return; if (s.running) pause() else resume() }

    /** Adds (or with negative [ms], removes) time from a countdown. */
    fun add(ms: Long) {
        val s = _state.value ?: return
        if (s.mode == Mode.STOPWATCH) return
        if (s.running) {
            val end = (s.at + ms).coerceAtLeast(now() + 1000)
            set(s.copy(at = end, lengthMs = (s.lengthMs + ms).coerceAtLeast(end - now())))
        } else {
            val left = (s.pausedMs + ms).coerceAtLeast(1000)
            set(s.copy(pausedMs = left, lengthMs = (s.lengthMs + ms).coerceAtLeast(left)))
        }
    }

    fun stop() { set(null); finishedAt = 0; w.prefs().edit { put("finishedAt", 0L) }; w.stopped() }

    /** Called every tick and by the alarm: finishes a countdown whose time is up. */
    fun check() {
        var s = _state.value ?: return
        if (s.running) {
            // The clock was set since [at] was: move [at] with it, so the time left (or, for the
            // stopwatch, the time taken) stays what it really is.
            val jump = s.anchor.jump(w.anchor())
            if (jump != 0L) { w.log("clock moved ${jump / 1000} s: timer follows"); s = s.copy(at = s.at + jump); set(s) }
        }
        if (s.mode == Mode.STOPWATCH || !s.running || now() < s.at) return
        finish(s)
    }

    /**
     * The alarm, or the in-process callback, came due. They count real time and [State.at] is a wall
     * time, so after a small clock correction (under two seconds isn't followed as a clock change)
     * they can be a moment early: then [check] finishes nothing, and they are armed again. Without
     * that, a timer ending while nothing ticks (the bar hidden, the device asleep) stayed silent
     * until the bar next showed.
     */
    fun due() {
        check()
        _state.value?.let { if (it.running && it.mode != Mode.STOPWATCH && now() < it.at) schedule(it) }
    }

    private fun finish(s: State) {
        finishedAt = now()
        w.prefs().edit { put("finishedAt", finishedAt) }
        if (s.mode == Mode.POMODORO) {
            val round = if (s.phase == Phase.WORK) s.round + 1 else s.round
            val next = when {
                s.phase != Phase.WORK -> Phase.WORK
                round % 4 == 0 -> Phase.LONG_BREAK
                else -> Phase.BREAK
            }
            val len = when (next) { Phase.WORK -> Timers.POMO_WORK; Phase.BREAK -> Timers.POMO_BREAK; Phase.LONG_BREAK -> Timers.POMO_LONG }
            w.done(Done.Pomodoro(s.phase, next, (len / 60_000).toInt()))
            set(State(Mode.POMODORO, true, now() + len, lengthMs = len, phase = next, round = round))
        } else {
            w.done(Done.Countdown(s.label, s.lengthMs))
            set(null)
        }
    }

    private fun set(state: State?) {
        val s = state?.let { val a = w.anchor(); it.copy(anchorWall = a.wall, anchorElapsed = a.elapsed, anchorBoot = a.boot) }
        _state.value = s
        w.prefs().edit { put("state", s?.let { json.encodeToString(State.serializer(), it) }) }
        schedule(s)
        w.changed()
    }

    private fun schedule(s: State?) {
        w.arm(if (s == null || !s.running || s.mode == Mode.STOPWATCH) null else (s.at - now()).coerceAtLeast(0))
    }
}
