package com.meridian11.books.bentobar.items

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.os.PowerManager
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.meridian11.books.bentobar.items.ScreenTimeRules.Flip
import com.meridian11.books.bentobar.items.ScreenTimeRules.Front
import com.meridian11.books.bentobar.util.Now
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What Screen time reads, with Usage access, from Android's own record of the day (`UsageStatsManager.queryEvents` from
 * local midnight to now): when the screen went on and off, for the bar, and, for the menu only and only while it is open,
 * which apps came to the front and left it. Off the main thread, through two [Refresher]s, at most once a minute each
 * while wanted; nothing is kept on disk, and no app's name is ever logged. The arithmetic is [ScreenTimeRules].
 *
 * Android's aggregated per-app totals are not used: their daily buckets don't start at local midnight (Android's docs:
 * they cover "at least" the range asked), so "today" would take in part of yesterday.
 */
object ScreenTimeLoad {
    private const val MINUTE = 60_000L
    /** Without Usage access, asked again this soon: the user may be giving it right now. */
    private const val NO_ACCESS_MS = 15_000L
    private const val ICON_PX = 64

    /**
     * Today as read at [at] (wall clock): [access] whether Usage access was given; [onMs] the screen's time on from
     * [dayStart] to [at]; [apps] the apps longest in front with their times (only when asked for), and [leftOut] whether
     * apps in front today were left out because BentoBar can't name them (it sees only apps with a launcher entry).
     */
    @Immutable
    class Day(val access: Boolean, val dayStart: Long, val at: Long, val onMs: Long, val apps: List<App>? = null, val leftOut: Boolean = false) {
        // For logs and dumps: never an app's name.
        override fun toString() = "Day(access=$access, on=${onMs / MINUTE} min, apps=${apps?.size})"
    }

    @Immutable
    class App(val label: String, val ms: Long, val icon: ImageBitmap?)

    private fun every(day: Day): Long = if (day.access) MINUTE else NO_ACCESS_MS

    /** The bar's: the screen's time on today. */
    val day = Background.refresher<Unit, Day>(ScreenTimeItem.type, every = { _, d -> every(d) }, load = { _, _ -> read(Env.app, withApps = false) })

    /** The menu's: the same, with the apps in front. Wanted only while the menu is open, and forgotten when it closes. */
    val apps = Background.refresher<Unit, Day>(ScreenTimeItem.type, every = { _, d -> every(d) }, load = { _, _ -> read(Env.app, withApps = true) })

    /** Background thread. Usage access taken back while this runs is "no access", never a throw. */
    private fun read(context: Context, withApps: Boolean): Day {
        val now = Now.wall()
        val start = ScreenTimeRules.dayStart(ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()))
        val usm = context.getSystemService(UsageStatsManager::class.java)
        if (usm == null || !Usage.granted(context)) return Day(false, start, now, 0)
        val flips = ArrayList<Flip>()
        val fronts = ArrayList<Front>()
        try {
            val events = usm.queryEvents(start, now) ?: return Day(true, start, now, 0, if (withApps) emptyList() else null)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                if (!events.getNextEvent(e)) break
                val at = e.timeStamp
                when (e.eventType) {
                    UsageEvents.Event.SCREEN_INTERACTIVE -> flips += Flip(at, on = true)
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> flips += Flip(at, on = false)
                    // A device starts with its screen on, and shuts down with it off; neither leaves an app in front.
                    UsageEvents.Event.DEVICE_STARTUP -> { flips += Flip(at, on = true); if (withApps) fronts += Front(at, null, false) }
                    UsageEvents.Event.DEVICE_SHUTDOWN -> { flips += Flip(at, on = false); if (withApps) fronts += Front(at, null, false) }
                    UsageEvents.Event.ACTIVITY_RESUMED -> if (withApps) e.packageName?.let { fronts += Front(at, it, true) }
                    UsageEvents.Event.ACTIVITY_PAUSED -> if (withApps) e.packageName?.let { fronts += Front(at, it, false) }
                }
            }
        } catch (e: SecurityException) {
            return Day(false, start, now, 0)
        }
        val onNow = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val onMs = ScreenTimeRules.screenOn(flips, start, now, ScreenTimeRules.onAtStart(flips, start, onNow))
        if (!withApps) return Day(true, start, now, onMs)
        val times = ScreenTimeRules.inFront(fronts, start, now)
        val pm = context.packageManager
        val labels = HashMap<String, String?>()
        fun label(pkg: String) = labels.getOrPut(pkg) { label(pm, pkg) }
        val top = ScreenTimeRules.top(times, cap = onMs) { label(it) != null }
        val leftOut = times.keys.any { label(it) == null }
        return Day(true, start, now, onMs, top.map { (pkg, ms) -> App(label(pkg)!!, ms, icon(pm, pkg)) }, leftOut)
    }

    /** An app's name, or null for one BentoBar can't see (package visibility lets it see the apps with a launcher entry). */
    private fun label(pm: PackageManager, pkg: String): String? = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun icon(pm: PackageManager, pkg: String): ImageBitmap? = runCatching {
        val d = pm.getApplicationIcon(pkg)
        val b = createBitmap(ICON_PX, ICON_PX)
        d.setBounds(0, 0, ICON_PX, ICON_PX)
        d.draw(Canvas(b))
        b.asImageBitmap()
    }.getOrNull()
}
