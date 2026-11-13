package com.meridian11.books.bentobar.items

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.SharedPrefs
import com.meridian11.books.bentobar.util.ClockAnchor
import kotlinx.serialization.Serializable

/**
 * The one shared timer: countdown, stopwatch or Pomodoro. State is persisted (wall-clock times)
 * so a timer survives BentoBar's process being restarted, and the end is also scheduled with
 * AlarmManager so it fires even when the bar isn't running. Setting the clock doesn't change a
 * timer: [State.anchor] tells a clock change from time passing, and the alarm counts real time.
 * The rules are [TimerClock]'s; this is it on Android.
 */
object Timers : TimerClock(TimersOnDevice.wiring) {
    enum class Mode { TIMER, STOPWATCH, POMODORO }
    enum class Phase { WORK, BREAK, LONG_BREAK }

    @Serializable
    data class State(
        val mode: Mode,
        val running: Boolean,
        /** TIMER/POMODORO: wall time it ends (while running). STOPWATCH: wall time it started. */
        val at: Long,
        /** While paused: remaining (TIMER/POMODORO) or elapsed (STOPWATCH) milliseconds. */
        val pausedMs: Long = 0,
        /** Full length, for progress (TIMER/POMODORO). */
        val lengthMs: Long = 0,
        val phase: Phase = Phase.WORK,
        /** Completed work rounds (POMODORO). */
        val round: Int = 0,
        val label: String = "",
        /** When [at] was last set, in real time too ([ClockAnchor]); absent in state saved before. */
        val anchorWall: Long = 0,
        val anchorElapsed: Long = 0,
        val anchorBoot: Int = -1,
    ) {
        val anchor get() = ClockAnchor(anchorWall, anchorElapsed, anchorBoot)
    }

    const val POMO_WORK = 25 * 60_000L
    const val POMO_BREAK = 5 * 60_000L
    const val POMO_LONG = 15 * 60_000L

    fun init(context: Context) {
        TimersOnDevice.app = context.applicationContext
        load()
    }
}

/** What [Timers] reaches for on the device: its preferences, the alarm and the in-process callback, the alert, the chip. */
private object TimersOnDevice {
    private const val TAG = "BentoBar"
    lateinit var app: Context
    private val prefs by lazy { SharedPrefs(app.getSharedPreferences("timers", Context.MODE_PRIVATE)) }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    val wiring = TimerClock.Wiring(
        now = System::currentTimeMillis,
        anchor = { ClockAnchor.now(app) },
        prefs = { prefs },
        arm = ::arm,
        done = ::done,
        changed = { Chips.update(app) },
        log = { Log.i(TAG, it) },
        stopped = { Notify.cancelDone(app) },
    )

    private fun arm(delayMs: Long?) {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(app, 0, Intent(app, TimerAlarm::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        main.removeCallbacksAndMessages(Timers)
        if (delayMs == null) return
        // Exact when the user allowed it (Alarms & reminders); otherwise Android may run it a bit late
        // while the device sleeps. The in-process handler covers the awake case to the second.
        // Real time since boot (not the wall clock), so setting the clock doesn't move it.
        val at = android.os.SystemClock.elapsedRealtime() + delayMs
        try {
            if (am.canScheduleExactAlarms() && com.meridian11.books.bentobar.data.Uses.on(com.meridian11.books.bentobar.data.Uses.EXACT_ALARMS))
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            Log.w(TAG, "alarm not allowed", e)
        }
        main.postAtTime({ Timers.due() }, Timers, android.os.SystemClock.uptimeMillis() + delayMs + 50)
    }

    private fun done(d: TimerClock.Done) = when (d) {
        is TimerClock.Done.Pomodoro -> Notify.timerDone(app, app.getString(if (d.after == Timers.Phase.WORK) R.string.timer_alert_focus_done else R.string.timer_alert_break_over),
            if (d.next == Timers.Phase.WORK) app.getString(R.string.timer_alert_next_focus)
            else app.resources.getQuantityString(R.plurals.timer_alert_next_break, d.minutes, d.minutes))
        is TimerClock.Done.Countdown -> Notify.timerDone(app, d.label.ifBlank { app.getString(R.string.timer_alert_done) },
            app.getString(R.string.timer_alert_finished, com.meridian11.books.bentobar.util.Fmt.duration(d.lengthMs)))
    }
}

/** AlarmManager lands here when a countdown ends. */
class TimerAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Env.init(context)
        Timers.due()
    }
}
