package com.meridian11.books.bentobar.items

import java.time.ZonedDateTime
import java.util.Locale

/**
 * Pure rules for the Screen time item: how long the screen was on today, from Android's record of the screen going on
 * and off, and how long each app was in front, from its record of apps coming to the front and leaving it. On a desktop
 * several apps are in front side by side, so the apps' times are never added up into the screen's. What Android is asked
 * is in [ScreenTimeLoad]; the item is [ScreenTimeItem].
 */
object ScreenTimeRules {
    private const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE

    /** The screen went on ([on]) or off at [at]: Android's SCREEN_INTERACTIVE and SCREEN_NON_INTERACTIVE. */
    data class Flip(val at: Long, val on: Boolean)

    /**
     * A window of [pkg] came to the front ([shown]) or left it at [at]: Android's ACTIVITY_RESUMED and ACTIVITY_PAUSED.
     * [pkg] null: every app left (the device shut down or started).
     */
    data class Front(val at: Long, val pkg: String?, val shown: Boolean)

    /** The words of [text]; English here, the app's from its text resources. */
    class Words(
        val minutes: (Long) -> String = { "$it min" },
        val hoursMinutes: (Long, Long) -> String = { h, m -> String.format(Locale.ROOT, "%d h %02d", h, m) },
    )

    /**
     * How long the screen was on between [dayStart] and [now]: on at the start as [onAtStart] says (or as a flip from before
     * the start says), then as each flip says. Flips out of order, doubled, or after [now] change nothing.
     */
    fun screenOn(flips: List<Flip>, dayStart: Long, now: Long, onAtStart: Boolean): Long {
        if (now <= dayStart) return 0
        var on = onAtStart
        var from = dayStart
        var total = 0L
        for (f in flips.sortedBy { it.at }) {
            if (f.at > now) break
            if (f.at <= dayStart) { on = f.on; continue }
            if (on) total += f.at - from
            from = f.at
            on = f.on
        }
        if (on) total += now - from
        return total
    }

    /**
     * Whether the screen was on when the day began: as the last flip before it says; else the opposite of the day's
     * first flip (the first word of the day is "off": it was on until then); with no flip at all, as it is now.
     */
    fun onAtStart(flips: List<Flip>, dayStart: Long, onNow: Boolean): Boolean {
        val sorted = flips.sortedBy { it.at }
        sorted.lastOrNull { it.at <= dayStart }?.let { return it.on }
        return sorted.firstOrNull()?.let { !it.on } ?: onNow
    }

    /**
     * How long each app was in front between [dayStart] and [now]: from its first window coming to the front until its
     * last leaves. An app that leaves with no word of it coming had been there since the day began. A shutdown or a start
     * ends every app's time in front, so one that was in front then is not counted on through a restart.
     */
    fun inFront(events: List<Front>, dayStart: Long, now: Long): Map<String, Long> {
        val open = HashMap<String, Int>()
        val since = HashMap<String, Long>()
        val seen = HashSet<String>()
        val total = HashMap<String, Long>()
        fun close(pkg: String, at: Long) {
            since.remove(pkg)?.let { total.merge(pkg, at - it, Long::plus) }
            open.remove(pkg)
        }
        for (e in events.sortedBy { it.at }) {
            if (e.at > now) break
            val at = e.at.coerceAtLeast(dayStart)
            val pkg = e.pkg
            if (pkg == null) {
                for (p in since.keys.toList()) close(p, at)
                open.clear()
                continue
            }
            val n = open[pkg] ?: 0
            when {
                e.shown -> { if (n == 0) since[pkg] = at; open[pkg] = n + 1 }
                n > 1 -> open[pkg] = n - 1
                n == 1 -> close(pkg, at)
                pkg !in seen -> total.merge(pkg, at - dayStart, Long::plus)
            }
            seen += pkg
        }
        for (p in since.keys.toList()) close(p, now)
        return total.filterValues { it > 0 }
    }

    /**
     * The apps to list: the [n] longest in front of those the menu can name ([named]), none under a minute, each at most
     * [cap] (the screen's own time today: an app left in front with the screen off is not counted beyond it).
     */
    fun top(times: Map<String, Long>, cap: Long, n: Int = 5, named: (String) -> Boolean): List<Pair<String, Long>> =
        times.entries.filter { it.value >= MINUTE && named(it.key) }
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(n).map { it.key to it.value.coerceAtMost(cap) }

    /** "47 min", "3 h 05": to the minute, rounded down. */
    fun text(ms: Long, words: Words = Words()): String {
        val minutes = ms.coerceAtLeast(0) / MINUTE
        return if (minutes < 60) words.minutes(minutes) else words.hoursMinutes(minutes / 60, minutes % 60)
    }

    /** At [hours] or more. */
    fun over(ms: Long, hours: Int): Boolean = ms >= hours * HOUR

    /**
     * The total to show at [now], from [onMs] as read at [loadedAt]: the screen has been on since, from [liveSince] at the
     * latest (the bar is sampled only while it shows), and never more than the day so far.
     */
    fun shown(onMs: Long, loadedAt: Long, liveSince: Long, now: Long, dayStart: Long): Long =
        (onMs + (now - maxOf(loadedAt, liveSince)).coerceAtLeast(0)).coerceAtMost((now - dayStart).coerceAtLeast(0))

    /** Local midnight of [now]'s day; where a clock change skips midnight, the day's first moment. */
    fun dayStart(now: ZonedDateTime): Long = now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()

    /** What the debug hook stages: Usage access given or not, the time on, and the apps in front by name. */
    class Staged(val access: Boolean, val onMs: Long, val apps: List<Pair<String, Long>>)

    /** The stages (`screentime stage NAME`): fixed lengths of time, the same whatever the day; null for a name it doesn't know. */
    fun staged(name: String?): Staged? = when (name) {
        "short" -> Staged(true, 47 * MINUTE, listOf("Notes" to 30 * MINUTE, "Browser" to 12 * MINUTE))
        "long" -> Staged(true, 3 * HOUR + 5 * MINUTE, listOf("Browser" to 2 * HOUR, "Docs" to 70 * MINUTE, "Mail" to 25 * MINUTE,
            "Terminal" to 18 * MINUTE, "Music" to 9 * MINUTE))
        "over" -> Staged(true, 5 * HOUR + 20 * MINUTE, listOf("Browser" to 4 * HOUR, "Docs" to 2 * HOUR))
        "noaccess" -> Staged(false, 0, emptyList())
        else -> null
    }
}
