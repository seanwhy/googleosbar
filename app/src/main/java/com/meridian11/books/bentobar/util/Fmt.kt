package com.meridian11.books.bentobar.util

import java.util.Locale
import kotlin.math.abs

/** Short, width-stable number formats for a 36 dp tall bar. */
object Fmt {
    /**
     * The words these formats are built of: English here, so the pure rules and their tests need nothing else. The app's come from its
     * text resources ([com.meridian11.books.bentobar.items.Env.init]), so translating BentoBar is a translation and no code.
     */
    class Words(
        val percent: (String) -> String = { "$it%" },
        val byteUnit: (Int) -> String = { listOf("B", "KB", "MB", "GB", "TB")[it] },
        val rateUnit: (Int) -> String = { listOf("B", "K", "M", "G")[it] },
        val daysHours: (Long, Long) -> String = { d, h -> "${d}d ${h}h" },
        val hoursMinutes: (Long, Long) -> String = { h, m -> String.format(Locale.ROOT, "%dh %02dm", h, m) },
        val minutes: (Long) -> String = { "${it}m" },
        val seconds: (Long) -> String = { "${it}s" },
        /** A length of time as it is said aloud, for what a screen reader reads out: "1 hour 5 minutes". */
        val aloud: (days: Long, hours: Long, minutes: Long, seconds: Long) -> String = { d, h, m, s -> english(d, h, m, s) },
    ) {
        private companion object {
            fun unit(n: Long, one: String, other: String) = "$n ${if (n == 1L) one else other}"
            fun english(d: Long, h: Long, m: Long, s: Long) = listOfNotNull(
                unit(d, "day", "days").takeIf { d > 0 }, unit(h, "hour", "hours").takeIf { h > 0 },
                unit(m, "minute", "minutes").takeIf { m > 0 }, unit(s, "second", "seconds").takeIf { s > 0 || (d == 0L && h == 0L && m == 0L) }).joinToString(" ")
        }
    }

    @Volatile var words = Words()

    /** "85%": how the language writes a percentage. */
    fun percent(whole: Int): String = words.percent(whole.toString())
    fun percent(number: String): String = words.percent(number)

    /** Bytes per second as "0K", "840K", "1.2M", "12M". */
    fun rate(bytesPerSec: Double): String = if (bytesPerSec < 1000) "0K" else compact(bytesPerSec)

    /** Bytes as "840 KB", "1.2 GB". */
    fun bytes(b: Double): String {
        var v = b
        var i = 0
        while (v >= 1000 && i < 4) { v /= 1000; i++ }
        return if (i == 0) "${v.toInt()} ${words.byteUnit(0)}" else "${oneDecimal(v)} ${words.byteUnit(i)}"
    }

    private fun compact(b: Double): String {
        var v = b
        var i = 0
        while (v >= 1000 && i < 3) { v /= 1000; i++ }
        return if (i == 0) "${v.toInt()}${words.rateUnit(0)}" else oneDecimal(v) + words.rateUnit(i)
    }

    /** One decimal below 10, none above: "1.2", "12", "123". */
    fun oneDecimal(v: Double): String =
        if (abs(v) < 9.95) String.format(Locale.ROOT, "%.1f", v) else String.format(Locale.ROOT, "%.0f", v)

    /** Durations: "45s", "12m", "1h 05m", "3d 4h". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        return when {
            d > 0 -> words.daysHours(d, h)
            h > 0 -> words.hoursMinutes(h, m)
            m > 0 -> words.minutes(m)
            else -> words.seconds(s)
        }
    }

    /**
     * A length of time as words, for what is read aloud: "12 minutes", "1 hour 5 minutes", "3 days 4 hours", never "12m". The two
     * largest units that aren't zero, so it stays short; [duration] is the bar's written form of the same time.
     */
    fun spoken(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val d = total / 86400
        val h = (total % 86400) / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            d > 0 -> words.aloud(d, h, 0, 0)
            h > 0 -> words.aloud(0, h, m, 0)
            m > 0 -> words.aloud(0, 0, m, s)
            else -> words.aloud(0, 0, 0, s)
        }
    }

    /**
     * [spoken] to the minute: what the bar writes for a meeting or a countdown, said aloud. Seconds only when nothing larger is left,
     * since the bar has none above a minute, and the same words for a whole minute, so the item isn't drawn again for them.
     */
    fun spokenMinutes(ms: Long): String = spoken(if (ms >= 60_000) ms - ms % 60_000 else ms)

    /** [spoken] for a timer's time left, rounded up as [clock] rounds its face, so the two never differ and "0 seconds" means it is over. */
    fun spokenLeft(ms: Long): String = spoken(ms + 999)

    /**
     * Timer faces: "4:05", "12:30", "1:02:03". Time left rounds up (it reads 0:00 only when it's
     * over); time taken, [elapsed] (the stopwatch), rounds down, so it reads 0:01 after a second.
     */
    fun clock(ms: Long, elapsed: Boolean = false): String {
        val s = if (elapsed) ms / 1000 else (ms + 999) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    fun percent(f: Double) = percent((f * 100).toInt())

    /**
     * The widest text [text] can become while its numbers change, for a fixed-width slot in the
     * strip. A number, whole or with one decimal ("0", "8.4", "999"), becomes "888": the formats
     * here switch from 9.9 to 10 and stay under 1000 before the unit steps up, and with tabular
     * digits three 8s are wider than any of them. Clock digits keep their shape ("9:42" holds
     * "88:88"), and K/M/G/T after a number take the widest letter.
     */
    fun widthTemplate(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!c.isDigit()) {
                // A unit right after a number ("4K", "15.9 GB"), not the start of a word ("Keep").
                val afterNumber = text.getOrNull(i - 1)?.isDigit() == true ||
                    (text.getOrNull(i - 1) == ' ' && text.getOrNull(i - 2)?.isDigit() == true)
                val unit = c in "KMGT" && afterNumber && text.getOrNull(i + 1)?.isLowerCase() != true
                out.append(if (unit) 'M' else c)
                i++
                continue
            }
            var j = i
            while (j < text.length && text[j].isDigit()) j++
            val clock = text.getOrNull(i - 1) == ':' || text.getOrNull(j) == ':'
            if (clock) {
                val len = j - i
                repeat(if (text.getOrNull(j) == ':' && text.getOrNull(i - 1) != ':') maxOf(len, 2) else len) { out.append('8') }
                i = j
                continue
            }
            // Take a decimal part with it: "8.4" is one number.
            if (j + 1 < text.length && (text[j] == '.' || text[j] == ',') && text[j + 1].isDigit()) {
                j++
                while (j < text.length && text[j].isDigit()) j++
            }
            val digits = text.substring(i, j).count { it.isDigit() }
            repeat(maxOf(digits, 3)) { out.append('8') }
            i = j
        }
        return out.toString()
    }
}
