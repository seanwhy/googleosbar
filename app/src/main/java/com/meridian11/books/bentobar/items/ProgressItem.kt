package com.meridian11.books.bentobar.items

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.items.ProgressRules.Of
import com.meridian11.books.bentobar.ui.ChoiceRow
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.Meter
import com.meridian11.books.bentobar.ui.SliderRow
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Dates
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Day progress: how much of the day is gone, as a glyph that fills and a percentage; or of the workday, the week, the month
 * or the year, as the item chooses. Its menu has the day, the week, the month and the year, each with what is left. The
 * week starts where the user's locale says. Nothing is read but the clock; the arithmetic is [ProgressRules].
 */
object ProgressItem : ItemType("progress", R.string.item_progress_title, Sym.CLOCK_LOADER_40, R.string.item_progress_desc) {
    override val group = ItemGroup.TIME
    // The bar's figure is a whole percent: the day's moves every 14 minutes, a short workday's every few.
    override val refreshMs = 5_000L

    internal val of = Opt.oneOf("of", "day", "day", "workday", "week", "month", "year")
    internal val from = IntOpt("from", 9, 0..23)
    internal val to = IntOpt("to", 17, 1..24)

    /** The test hook's moment, shown instead of now; null: none staged. */
    @Volatile private var staged: ZonedDateTime? = null

    private fun now(): ZonedDateTime = staged ?: ZonedDateTime.ofInstant(Instant.ofEpochMilli(Now.wall()), ZoneId.systemDefault())
    private fun weekStart() = ProgressRules.weekStart(Locale.getDefault())

    private val words = ProgressRules.Words(
        minutes = { Env.str(R.string.common_bar_minutes, it) },
        hoursMinutes = { h, m -> Env.str(R.string.common_bar_hours_minutes, h, m) },
        days = { Env.plural(R.plurals.progress_days, it.toInt(), it) },
        left = { Env.str(R.string.progress_left, it) },
        over = { Env.str(R.string.progress_over) },
    )
    /** The same, as a screen reader says it: "9 hours 7 minutes left". */
    private val spoken = ProgressRules.Words(
        minutes = { Fmt.words.aloud(0, 0, it, 0) },
        hoursMinutes = { h, m -> Fmt.words.aloud(0, h, m, 0) },
        days = { Env.plural(R.plurals.progress_days, it.toInt(), it) },
        left = { Env.str(R.string.progress_left, it) },
        over = { Env.str(R.string.progress_over) },
    )

    @androidx.annotation.StringRes
    private fun name(of: Of): Int = when (of) {
        Of.DAY -> R.string.progress_day
        Of.WORKDAY -> R.string.progress_workday
        Of.WEEK -> R.string.progress_week
        Of.MONTH -> R.string.progress_month
        Of.YEAR -> R.string.progress_year
    }

    /** One period at [now] for [item]: how much is gone, and what is left in [w]'s words. */
    private class Reading(val fraction: Float, val left: String)

    private fun read(of: Of, item: ItemConfig, now: ZonedDateTime, w: ProgressRules.Words = words): Reading {
        val ws = weekStart()
        return Reading(ProgressRules.fraction(of, now, from.of(item), to.of(item), ws), ProgressRules.left(of, now, from.of(item), to.of(item), ws, w))
    }

    override fun state(item: ItemConfig): ItemState {
        val of = ProgressRules.of(of.of(item))
        val now = now()
        val r = read(of, item, now, spoken)
        val percent = ProgressRules.percent(r.fraction)
        return ItemState(icon = ProgressRules.icon(r.fraction), text = percent, widthKey = "progress",
            desc = Env.str(R.string.progress_desc, Env.str(name(of)), percent, r.left))
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, _ ->
        rememberTick()
        val now = now()
        val chosen = ProgressRules.of(of.of(item))
        // The chosen workday first, then the four that every day has.
        val rows = (if (chosen == Of.WORKDAY) listOf(Of.WORKDAY) else emptyList()) + listOf(Of.DAY, Of.WEEK, Of.MONTH, Of.YEAR)
        MenuCard(ProgressRules.icon(read(chosen, item, now).fraction), stringResource(R.string.item_progress_title), Dates.format("yMMMMEEEEd", now)) {
            rows.forEachIndexed { i, of ->
                val r = read(of, item, now)
                if (i > 0) Spacer(Modifier.height(10.dp))
                InfoRow(stringResource(name(of)), ProgressRules.percent(r.fraction),
                    if (of == chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Meter(r.fraction)
                MenuNote(r.left)
            }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        val choices = Of.entries.map { ProgressRules.key(it) to stringResource(name(it)) }
        ChoiceRow(stringResource(R.string.progress_of), choices, of.of(item)) { set(of.set(item, it)) }
        if (ProgressRules.of(of.of(item)) == Of.WORKDAY) {
            val h24 = DateFormat.is24HourFormat(LocalContext.current)
            val midnight = stringResource(R.string.progress_midnight)
            // An hour on a day with no clock change, so 2:00 reads 2:00 whatever today is.
            val hour = { h: Int -> if (h >= 24) midnight else Dates.format(Dates.timeSkeleton(h24), LocalDate.of(2000, 1, 1).atTime(h, 0).atZone(ZoneOffset.UTC)) }
            SliderRow(stringResource(R.string.progress_from), from.of(item), from.range, hour) { set(from.set(item, it)) }
            SliderRow(stringResource(R.string.progress_to), to.of(item), to.range, hour) { set(to.set(item, it)) }
        }
    }

    // ---- for tests on a device (debug builds)

    override val stages = listOf("stage morning", "stage evening", "stage yearend")

    /**
     * `progress`: the moment it shows. `progress stage morning|evening|yearend`: a fixed moment in the device's zone
     * (2027-03-10 at 9:00 and at 21:00, 2027-12-31 at 23:00), so no day's own reading looks the same; `progress off` ends it.
     */
    override fun debug(args: List<String>): String? = when {
        args.isEmpty() -> "progress: now=${now()} staged=${staged != null}"
        args == listOf("off") || args == listOf("stage", "off") -> { staged = null; Ticker.refresh(type); "progress: staging off" }
        args.size != 2 || args[0] != "stage" -> null
        else -> {
            val at = when (args[1]) {
                "morning" -> "2027-03-10T09:00"
                "evening" -> "2027-03-10T21:00"
                "yearend" -> "2027-12-31T23:00"
                else -> null
            }
            at?.let { staged = LocalDateTime.parse(it).atZone(ZoneId.systemDefault()); Ticker.refresh(type); "progress: staged ${args[1]}" }
        }
    }
}
