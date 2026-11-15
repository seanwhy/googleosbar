package com.meridian11.books.bentobar.items

import java.util.Locale

/**
 * Pure rules for the Break reminder (ruling R9): work is time the device is awake and the bar sampled, and a break is a
 * stretch without it (the screen off, or no sample at all: the bar hidden, the device asleep) as long as the break's
 * length. Nothing else is watched: no keys, no pointer, no other app. Times are elapsed realtime. The item is [BreaksItem].
 */
object BreaksRules {
    /** [workedMs] since the last break, [awayMs] of the stretch away that is going on now (0 while working), [lastAt] the last sample (0: none yet). */
    data class Watch(val workedMs: Long = 0L, val awayMs: Long = 0L, val lastAt: Long = 0L)

    /** A longer silence between samples is time away: the ticker samples every second, every other under battery saver. */
    const val GAP_MS = 90_000L

    /** The words of [text]; English here, the app's from its text resources. */
    class Words(
        val minutes: (Long) -> String = { "$it min" },
        val hoursMinutes: (Long, Long) -> String = { h, m -> String.format(Locale.ROOT, "%d h %02d", h, m) },
        val due: () -> String = { "Break" },
    )

    /**
     * A sample at [now], the screen on as [interactive] says: the time since the last one is work, or time away (the screen
     * off, or a silence longer than [GAP_MS]). Away reaching [breakMs] is a break: what was worked starts again. Work ends a
     * stretch away, so short ones never add up to a break. The first sample, or one from a clock that went back, only starts the count.
     */
    fun step(w: Watch, now: Long, interactive: Boolean, breakMs: Long): Watch {
        val dt = now - w.lastAt
        if (w.lastAt == 0L || dt < 0) return w.copy(lastAt = now)
        if (interactive && dt <= GAP_MS) return Watch(w.workedMs + dt, 0L, now)
        val away = w.awayMs + dt
        return Watch(if (away >= breakMs) 0L else w.workedMs, away, now)
    }

    /** A break is due: [everyMs] worked since the last. */
    fun due(w: Watch, everyMs: Long): Boolean = w.workedMs >= everyMs

    /** "I took one", at [now]: the count starts again. */
    fun tookOne(now: Long): Watch = Watch(0L, 0L, now)

    /** The time until the next break, rounded up ("12 min", "1 h 20"), or "Break" when one is due. */
    fun text(w: Watch, everyMs: Long, words: Words = Words()): String =
        if (due(w, everyMs)) words.due() else length(everyMs - w.workedMs, words, up = true)

    /** [ms] as "12 min" or "1 h 20": to the minute, rounded down, or [up] (a time still to come, which never reads 0 before it is). */
    fun length(ms: Long, words: Words = Words(), up: Boolean = false): String {
        val minutes = (ms.coerceAtLeast(0) + if (up) 59_999 else 0) / 60_000
        return if (minutes < 60) words.minutes(minutes) else words.hoursMinutes(minutes / 60, minutes % 60)
    }
}
