package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Sym
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Pure rules for the Day progress item: how much of the day, the workday, the week, the month or the year is gone. A period
 * runs from its first moment to the next one's, counted in real time, so a day with a clock change is still one whole day
 * (23 or 25 hours long), and a leap year has 366 days. The item is [ProgressItem].
 */
object ProgressRules {
    enum class Of { DAY, WORKDAY, WEEK, MONTH, YEAR }

    /** How the layout stores [of]. */
    fun key(of: Of): String = of.name.lowercase(Locale.ROOT)

    /** What the layout's [key] means; a day for anything else. */
    fun of(key: String?): Of = Of.entries.firstOrNull { key(it) == key } ?: Of.DAY

    /** The words of [left]; English here, the app's from its text resources. */
    class Words(
        val minutes: (Long) -> String = { "$it min" },
        val hoursMinutes: (Long, Long) -> String = { h, m -> String.format(Locale.ROOT, "%d h %02d", h, m) },
        val days: (Long) -> String = { if (it == 1L) "1 day" else "$it days" },
        val left: (String) -> String = { "$it left" },
        val over: () -> String = { "Over" },
    )

    /** [hour] o'clock on [date] in [zone], 24 being the next day's start; an hour a clock change skips is the first after it. */
    private fun at(date: LocalDate, hour: Int, zone: ZoneId): ZonedDateTime =
        if (hour >= 24) date.plusDays(1).atStartOfDay(zone) else date.atTime(hour, 0).atZone(zone)

    /**
     * The workday that counts at [now], from [from] to [to] o'clock. One that ends at or before the hour it starts runs past
     * midnight (a night's work; the same hour twice is a whole day's). The one under way; else the last one if it ended today
     * (the day's work is done); else today's, not yet begun.
     */
    private fun workday(now: ZonedDateTime, from: Int, to: Int): Pair<ZonedDateTime, ZonedDateTime> {
        val date = now.toLocalDate()
        fun shift(d: LocalDate) = at(d, from, now.zone) to at(if (to > from) d else d.plusDays(1), to, now.zone)
        val today = shift(date)
        val before = shift(date.minusDays(1))
        return when {
            !now.isBefore(today.first) -> today
            now.isBefore(before.second) -> before
            before.second.toLocalDate() == date -> before
            else -> today
        }
    }

    /** The period [of] that [now] is in: its first moment and the next period's. */
    fun span(of: Of, now: ZonedDateTime, workStart: Int, workEnd: Int, weekStart: DayOfWeek): Pair<ZonedDateTime, ZonedDateTime> {
        val date = now.toLocalDate()
        val zone = now.zone
        return when (of) {
            Of.DAY -> date.atStartOfDay(zone) to date.plusDays(1).atStartOfDay(zone)
            Of.WORKDAY -> workday(now, workStart, workEnd)
            Of.WEEK -> date.with(TemporalAdjusters.previousOrSame(weekStart)).let { it.atStartOfDay(zone) to it.plusDays(7).atStartOfDay(zone) }
            Of.MONTH -> date.withDayOfMonth(1).let { it.atStartOfDay(zone) to it.plusMonths(1).atStartOfDay(zone) }
            Of.YEAR -> date.withDayOfYear(1).let { it.atStartOfDay(zone) to it.plusYears(1).atStartOfDay(zone) }
        }
    }

    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    /**
     * How much of the period is gone, 0 to 1: a workday is 0 before it starts and 1 once it is over. Exactly 1 only when the
     * period is over: the last moments of a year are less, though a Float would round them up to it.
     */
    fun fraction(of: Of, now: ZonedDateTime, workStart: Int = 9, workEnd: Int = 17, weekStart: DayOfWeek): Float {
        val (start, end) = span(of, now, workStart, workEnd, weekStart)
        val total = ms(end) - ms(start)
        val gone = (ms(now) - ms(start)).coerceIn(0L, total)
        if (gone >= total) return 1f
        return (gone.toDouble() / total).toFloat().coerceAtMost(Math.nextDown(1f))
    }

    /** "62%", rounded down: 100% only when it is over. */
    fun percent(fraction: Float): String = Fmt.percent(kotlin.math.floor(fraction.toDouble() * 100).toInt().coerceIn(0, 100))

    /** The glyph that fills as the period goes. */
    fun icon(fraction: Float): String = when {
        fraction < 0.15f -> Sym.CLOCK_LOADER_10
        fraction < 0.3f -> Sym.CLOCK_LOADER_20
        fraction < 0.5f -> Sym.CLOCK_LOADER_40
        fraction < 0.7f -> Sym.CLOCK_LOADER_60
        fraction < 0.85f -> Sym.CLOCK_LOADER_80
        else -> Sym.CLOCK_LOADER_90
    }

    /**
     * What is left of the period: "5 h 12 left" on its last day (rounded up, so never "0 min" while some is), else the whole
     * days after today, "212 days left". Before a workday starts all of it is left; once it is over, "Over".
     */
    fun left(of: Of, now: ZonedDateTime, workStart: Int = 9, workEnd: Int = 17, weekStart: DayOfWeek, words: Words = Words()): String {
        val (start, end) = span(of, now, workStart, workEnd, weekStart)
        val left = ms(end) - maxOf(ms(now), ms(start))
        if (left <= 0) return words.over()
        val daysAfter = if (of == Of.DAY || of == Of.WORKDAY) 0L else ChronoUnit.DAYS.between(now.toLocalDate(), end.toLocalDate()) - 1
        if (daysAfter > 0) return words.left(words.days(daysAfter))
        val minutes = (left + 59_999) / 60_000
        return words.left(if (minutes < 60) words.minutes(minutes) else words.hoursMinutes(minutes / 60, minutes % 60))
    }

    /** The day the week starts on in [locale]: a first day the user chose (the "fw" in the locale), else the region's. */
    fun weekStart(locale: Locale): DayOfWeek {
        val fw = locale.getUnicodeLocaleType("fw") ?: ""
        val chosen = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun").indexOf(fw)
        return if (chosen >= 0) DayOfWeek.of(chosen + 1) else WeekFields.of(locale).firstDayOfWeek
    }
}
