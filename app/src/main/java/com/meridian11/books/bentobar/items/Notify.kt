package com.meridian11.books.bentobar.items

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ChipMode
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.ui.MainActivity
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Sym
import android.text.format.DateFormat
import java.util.Date

/** Notification channels and the timer-finished alert. */
object Notify {
    /**
     * Live Updates (promoted notifications) exist from Android 16 QPR2 (API 36.1); minSdk is 34, and
     * calling them on an older Android would crash. Googlebooks run 17.
     */
    val liveUpdates: Boolean get() = Build.VERSION.SDK_INT > Build.VERSION_CODES.BAKLAVA ||
        (Build.VERSION.SDK_INT == Build.VERSION_CODES.BAKLAVA && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1)

    const val ALERTS = "alerts"
    const val LIVE = "live"
    const val ID_DONE = 10
    const val ID_CHIP = 20

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_alerts_desc)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        })
        nm.createNotificationChannel(NotificationChannel(LIVE, context.getString(R.string.channel_live), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.channel_live_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }

    fun allowed(context: Context) =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            com.meridian11.books.bentobar.data.Uses.on(com.meridian11.books.bentobar.data.Uses.NOTIFICATIONS)

    /** A finished timer rings through its notification (the alarm tone is the channel's), so ending the ring is cancelling it. */
    fun cancelDone(context: Context) { context.getSystemService(NotificationManager::class.java)?.cancel(ID_DONE) }

    fun timerDone(context: Context, title: String, text: String) {
        if (!allowed(context)) return
        channels(context)
        val n = Notification.Builder(context, ALERTS)
            .setSmallIcon(Glyphs.icon(Sym.TIMER))
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE))
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(ID_DONE, n)
    }
}

/** Material Symbols drawn into bitmaps, for notification icons (which must be plain alpha shapes). */
object Glyphs {
    private val cache = HashMap<String, Icon>()
    private var typeface: Typeface? = null

    fun bitmap(sym: String, px: Int, color: Int = Color.WHITE): Bitmap {
        val tf = typeface ?: Typeface.createFromAsset(Env.app.assets, "fonts/MaterialSymbolsRounded_Fill.ttf").also { typeface = it }
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = tf; textSize = px.toFloat(); this.color = color; textAlign = Paint.Align.CENTER }
        val fm = p.fontMetrics
        Canvas(b).drawText(sym, px / 2f, px / 2f - (fm.ascent + fm.descent) / 2f, p)
        return b
    }

    /**
     * Notification icons. Resource icons where BentoBar ships the glyph as a vector drawable: the
     * status bar's Live Update chip only appears for those (verified on Googlebook OS; a bitmap
     * icon gave a promoted notification but no chip).
     */
    fun icon(sym: String): Icon = synchronized(cache) {
        cache.getOrPut(sym) {
            val res = drawables[sym]
            if (res != null) Icon.createWithResource(Env.app, res) else Icon.createWithBitmap(bitmap(sym, 96))
        }
    }

    private val drawables = mapOf(
        Sym.TIMER to com.meridian11.books.bentobar.R.drawable.sym_timer,
        Sym.AVG_PACE to com.meridian11.books.bentobar.R.drawable.sym_avg_pace,
        Sym.EVENT to com.meridian11.books.bentobar.R.drawable.sym_event,
        Sym.COFFEE to com.meridian11.books.bentobar.R.drawable.sym_coffee,
        Sym.PAUSE to com.meridian11.books.bentobar.R.drawable.sym_pause,
        Sym.PLAY_ARROW to com.meridian11.books.bentobar.R.drawable.sym_play_arrow,
        Sym.ADD to com.meridian11.books.bentobar.R.drawable.sym_add,
        Sym.STOP to com.meridian11.books.bentobar.R.drawable.sym_stop,
        Sym.VIDEOCAM to com.meridian11.books.bentobar.R.drawable.sym_videocam,
        Sym.OPEN_IN_NEW to com.meridian11.books.bentobar.R.drawable.sym_open_in_new,
    )
}

/**
 * The Live Update chip: Android shows ONE promoted notification per app as a chip left of the
 * system icons (verified on Googlebook OS; longer than ~7 characters, the text is dropped). BentoBar
 * uses it for the running timer, else a meeting that is on or about to start.
 */
object Chips {
    private const val CALENDAR_USER = "chip"
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    /** The chip changes at a meeting's times (15 minutes before, the start, the end), not only on events. */
    private val boundary = Runnable { app?.let { update(it) } }
    /** What the system's alarm for that is set to, so a change to something else doesn't set it again. */
    private var armedAt: Long? = null

    fun update(context: Context) {
        val app = context.applicationContext.also { this.app = it }
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        val cfg = Store.config.value
        val barUp = Env.running && cfg.enabled
        val wanted = when (cfg.chipMode) {
            ChipMode.OFF -> false
            ChipMode.FALLBACK -> !barUp
            ChipMode.ALWAYS -> true
        }
        main.removeCallbacks(boundary)
        val shows = wanted && Notify.allowed(app)
        val meetings = ChipRules.needsCalendar(shows, Timers.state.value != null, meetingChips())
        // Before build() reads it: a process just started finds it loading, and the load comes back here.
        if (meetings) Calendar.hold(CALENDAR_USER) else Calendar.release(CALENDAR_USER)
        val n = if (shows) build(app) else null
        if (n != null) { Notify.channels(app); nm.notify(Notify.ID_CHIP, n) }
        else if (!ChipRules.leaveUp(meetings, Calendar.loadingFirst) { showingTimeout(nm) }) nm.cancel(Notify.ID_CHIP)
        var next: Long? = null
        if (meetings) {
            // Keep the calendar current while the strip (which otherwise asks for it) may be away; a
            // load that finds changes calls update() again.
            Calendar.refresh()
            // From every loaded meeting, not only today's (Calendar.meetings stops at 03:00): after the
            // day's last one, nothing else would bring the chip up for tomorrow morning's.
            val now = System.currentTimeMillis()
            next = ChipRules.nextChange(Calendar.upcomingMeetings(now), now)?.also { main.postDelayed(boundary, it - now + 1_000) }
        }
        arm(app, next?.plus(1_000))
    }

    /**
     * The next change, also for when no process is left to wait for it: the chip shows exactly while the accessibility service,
     * which keeps the process, may be off. Inexact, so no permission is needed and the handler above stays what is on time;
     * the wall clock, which meetings are in; no wake-up, so a sleeping device hears of it when it next wakes.
     */
    private fun arm(context: Context, at: Long?) {
        if (at == armedAt) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(context, 0, Intent(context, ChipAction::class.java).setAction(ChipAction.BOUNDARY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (at == null) am.cancel(pi) else am.set(AlarmManager.RTC, at, pi)
        armedAt = at
    }

    /** What the chip up now was posted with ([ChipRules.leaveUp]); null with none up. */
    private fun showingTimeout(nm: NotificationManager) =
        nm.activeNotifications.firstOrNull { it.id == Notify.ID_CHIP }?.notification?.timeoutAfter

    private fun meetingChips() =
        Store.config.value.items.any { it.type == EventItem.type }

    private fun build(context: Context): Notification? {
        val t = Timers.state.value
        if (t != null) return timer(context, t)
        if (!meetingChips()) return null
        val now = System.currentTimeMillis()
        val e = Calendar.current(now) ?: Calendar.next(now)?.takeIf { it.begin - now <= ChipRules.LEAD_MS } ?: return null
        return event(context, e, now)
    }

    private fun base(context: Context, sym: String, title: String, text: String) =
        Notification.Builder(context, Notify.LIVE)
            .setSmallIcon(Glyphs.icon(sym))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .apply { if (Notify.liveUpdates) setRequestPromotedOngoing(true) }
            .setContentIntent(PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE))

    private fun action(context: Context, sym: String, label: String, cmd: String) =
        Notification.Action.Builder(Glyphs.icon(sym), label, PendingIntent.getBroadcast(context, cmd.hashCode(),
            Intent(context, ChipAction::class.java).setAction(cmd), PendingIntent.FLAG_IMMUTABLE)).build()

    private fun launch(context: Context, sym: String, label: String, request: Int, intent: Intent) =
        Notification.Action.Builder(Glyphs.icon(sym), label, PendingIntent.getActivity(context, request,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()

    private fun timer(context: Context, t: Timers.State): Notification {
        val now = Timers.now()
        val title = when (t.mode) {
            Timers.Mode.STOPWATCH -> context.getString(R.string.timer_stopwatch)
            Timers.Mode.POMODORO -> if (t.phase == Timers.Phase.WORK) context.getString(R.string.chip_focus_round, t.round + 1) else context.getString(R.string.timer_break)
            Timers.Mode.TIMER -> t.label.ifBlank { context.getString(R.string.item_timer_title) }
        }
        // The system's time format: 12/24-hour as set in Android, not just the locale's default.
        val time = DateFormat.getTimeFormat(context)
        val b = base(context, if (t.mode == Timers.Mode.STOPWATCH) Sym.AVG_PACE else Sym.TIMER, title,
            when {
                !t.running -> context.getString(R.string.common_paused)
                t.mode == Timers.Mode.STOPWATCH -> context.getString(R.string.chip_started_at, time.format(Date(t.at)))
                else -> context.getString(R.string.common_ends_at, time.format(Date(t.at)))
            })
        if (t.running) {
            b.setWhen(t.at).setShowWhen(true).setUsesChronometer(true)
                .setChronometerCountDown(t.mode != Timers.Mode.STOPWATCH)
        } else {
            if (Notify.liveUpdates) b.setShortCriticalText(Fmt.clock(t.pausedMs, elapsed = t.mode == Timers.Mode.STOPWATCH))
        }
        b.addAction(action(context, if (t.running) Sym.PAUSE else Sym.PLAY_ARROW,
            context.getString(if (t.running) R.string.common_pause else R.string.common_resume), ChipAction.TOGGLE))
        if (t.mode != Timers.Mode.STOPWATCH) b.addAction(action(context, Sym.ADD, context.getString(R.string.common_plus_one_min), ChipAction.PLUS))
        b.addAction(action(context, Sym.STOP, context.getString(R.string.chip_stop), ChipAction.STOP))
        return b.build()
    }

    private fun event(context: Context, e: Calendar.Event, now: Long): Notification {
        val on = e.begin <= now
        val time = DateFormat.getTimeFormat(context)
        val b = base(context, Sym.EVENT, e.title,
            if (on) context.getString(R.string.common_ends_at, time.format(Date(e.end)))
            else context.getString(R.string.chip_starts_at, time.format(Date(e.begin))))
            .setWhen(if (on) e.end else e.begin).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            // At the meeting's end, with or without a process left to take it away.
            .setTimeoutAfter(ChipRules.timeoutAfter(e.end, now))
        // These open the meeting the chip shows, straight from the notification: a broadcast that then
        // started an activity would be a trampoline Android blocks, and could pick a different meeting.
        Calendar.joinIntent(e)?.let { b.addAction(launch(context, Sym.VIDEOCAM, context.getString(R.string.common_join), 2, it)) }
        b.addAction(launch(context, Sym.OPEN_IN_NEW, context.getString(R.string.common_open), 3, Calendar.openIntent(e)))
        return b.build()
    }
}

/** Buttons on the Live Update chip's card, and the alarm for its next change. Not exported: only BentoBar's own intents get here. */
class ChipAction : BroadcastReceiver() {
    companion object {
        const val TOGGLE = "com.meridian11.books.bentobar.chip.TOGGLE"
        const val PLUS = "com.meridian11.books.bentobar.chip.PLUS"
        const val STOP = "com.meridian11.books.bentobar.chip.STOP"
        const val BOUNDARY = "com.meridian11.books.bentobar.chip.BOUNDARY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Env.init(context)
        when (intent.action) {
            TOGGLE -> Timers.toggle()
            PLUS -> Timers.add(60_000)
            STOP -> Timers.stop()
            BOUNDARY -> {
                Chips.update(context)
                // A process started for this alarm is cached as soon as this returns, with the first load still running and the
                // next alarm not set. It stays a receiver until the load has come back through Chips.update, or a few seconds.
                if (Calendar.loadingFirst) goAsync().let { Calendar.whenLoaded(it::finish) }
            }
        }
    }
}
