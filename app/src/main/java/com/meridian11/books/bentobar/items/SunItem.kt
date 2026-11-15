package com.meridian11.books.bentobar.items

import android.icu.util.TimeZone as IcuZone
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.ui.ChoiceRow
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Dates
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Sunset: the time until the next sunset or sunrise, around the main city of the device's time zone (ruling R8). Worked out
 * on the device ([SunRules]) from a table made of tzdata ([SunZones]): no location, no permission, nothing asked of any
 * service. A zone with no city (UTC) says so.
 */
object SunItem : ItemType("sun", R.string.item_sun_title, Sym.WB_TWILIGHT, R.string.item_sun_desc) {
    override val group = ItemGroup.TIME
    // The bar's figure is to the minute.
    override val refreshMs = 5_000L

    internal val show = Opt.oneOf("show", "next", "next", "sunset", "sunrise")

    /**
     * The zone's name as the table may hold it. Android names some zones by an older alias (`Asia/Calcutta`), and ICU's
     * canonical name is CLDR's, which keeps those aliases, so the IANA name (Android 16 and up) and the zone's other names
     * are tried too. A test gives it a fake.
     */
    internal var canonical: (String) -> String = { tableName(it) }

    /** [iana] false: as before Android 16, where ICU has no IANA name to give (a test of that road on a newer Android). */
    internal fun tableName(id: String, iana: Boolean = true): String {
        val names = buildList<String?> {
            if (iana && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) add(IcuZone.getIanaID(id))
            add(IcuZone.getCanonicalID(id))
            for (i in 0 until IcuZone.countEquivalentIDs(id)) add(IcuZone.getEquivalentID(id, i))
        }
        return names.firstOrNull { it != null && it in SunZones.TABLE } ?: id
    }

    /** Where and when: the place (null: the zone has no city), its zone, and the moment. */
    private class Here(val place: SunZones.Place?, val zone: ZoneId, val now: Long)

    /** The test hook's place and moment, shown instead of the device's; null: none staged. */
    @Volatile private var staged: Here? = null
    /** The last zone looked up and its place: the zone rarely changes, and an alias costs a few lookups in ICU. */
    @Volatile private var looked: Pair<String, SunZones.Place?>? = null

    private fun here(): Here {
        staged?.let { return it }
        val zone = ZoneId.systemDefault()
        val place = looked?.takeIf { it.first == zone.id }?.second
            ?: SunRules.place(zone.id, canonical).also { looked = zone.id to it }
        return Here(place, zone, Now.wall())
    }

    private val words = SunRules.Words(
        minutes = { Env.str(R.string.common_bar_minutes, it) },
        hoursMinutes = { h, m -> Env.str(R.string.common_bar_hours_minutes, h, m) },
    )

    private fun which(item: ItemConfig): Boolean? = when (show.of(item)) { "sunrise" -> true; "sunset" -> false; else -> null }

    private fun clock(at: Long, zone: ZoneId) = Dates.format(Dates.timeSkeleton(DateFormat.is24HourFormat(Env.app)), at, zone)

    /** The glyph of what comes next: a sunset, a sunrise; the sun up all day, or down. */
    private fun icon(next: SunRules.Next?, day: SunRules.Day): String = when {
        next != null -> if (next.rises) Sym.SUNNY else Sym.WB_TWILIGHT
        day == SunRules.Day.NeverUp -> Sym.NIGHTS_STAY
        else -> Sym.SUNNY
    }

    private fun today(h: Here, p: SunZones.Place) = SunRules.day(Instant.ofEpochMilli(h.now).atZone(h.zone).toLocalDate(), p.lat, p.lon)

    override fun state(item: ItemConfig): ItemState {
        val h = here()
        val p = h.place ?: return Env.str(R.string.sun_nozone).let { ItemState(icon = Sym.WB_TWILIGHT, filled = false, desc = it) }
        val next = SunRules.next(h.now, p.lat, p.lon, h.zone, which(item))
        val day = today(h, p)
        if (next == null) {
            val polar = Env.str(if (day == SunRules.Day.NeverUp) R.string.sun_no_rise else R.string.sun_no_set)
            return ItemState(icon = icon(null, day), desc = Env.str(R.string.sun_desc_polar, polar, p.city))
        }
        val minutes = (next.at - h.now + 59_999) / 60_000
        val desc = Env.str(if (next.rises) R.string.sun_desc_rise else R.string.sun_desc_set, Fmt.spoken(minutes * 60_000), clock(next.at, h.zone), p.city)
        return ItemState(icon = icon(next, day), text = SunRules.text(next, h.now, words), desc = desc, widthKey = "sun")
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, _ ->
        rememberTick()
        val title = stringResource(R.string.item_sun_title)
        val h = here()
        val p = h.place
        if (p == null) MenuCard(Sym.WB_TWILIGHT, title, stringResource(R.string.sun_nozone)) {
            MenuNote(stringResource(R.string.sun_nozone_note))
        } else {
            val next = SunRules.next(h.now, p.lat, p.lon, h.zone, which(item))
            val day = today(h, p)
            val subtitle = when {
                next == null -> stringResource(if (day == SunRules.Day.NeverUp) R.string.sun_no_rise else R.string.sun_no_set)
                next.rises -> stringResource(R.string.sun_rise_in, SunRules.text(next, h.now, words))
                else -> stringResource(R.string.sun_set_in, SunRules.text(next, h.now, words))
            }
            MenuCard(icon(next, day), title, subtitle) {
                when (day) {
                    is SunRules.Day.Normal -> {
                        InfoRow(stringResource(R.string.sun_sunrise), clock(day.rise, h.zone))
                        InfoRow(stringResource(R.string.sun_sunset), clock(day.set, h.zone))
                        val length = (day.set - day.rise) / 60_000
                        InfoRow(stringResource(R.string.sun_length), words.hoursMinutes(length / 60, length % 60))
                    }
                    SunRules.Day.AlwaysUp -> MenuNote(stringResource(R.string.sun_polar_day))
                    SunRules.Day.NeverUp -> MenuNote(stringResource(R.string.sun_polar_night))
                }
                MenuNote(stringResource(R.string.sun_around, p.city))
            }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        ChoiceRow(stringResource(R.string.option_show), listOf("next" to stringResource(R.string.sun_show_next),
            "sunset" to stringResource(R.string.sun_sunset), "sunrise" to stringResource(R.string.sun_sunrise)), show.of(item),
            help = stringResource(R.string.sun_where_help)) { set(show.set(item, it)) }
    }

    // ---- for tests on a device (debug builds)

    override val stages = listOf("stage evening", "stage night", "stage polar", "stage nozone")

    /**
     * `sun`: the place and the moment it shows. `sun stage evening|night|polar|nozone`: a fixed place and moment (London on
     * 2027-03-10 at 16:00 and at 22:00 UTC, Tromsø at noon on 2027-06-21, a zone with no city), the same wherever and
     * whenever it runs; `sun off` ends it.
     */
    override fun debug(args: List<String>): String? = when {
        args.isEmpty() -> here().let { "sun: zone=${it.zone} place=${it.place?.city} staged=${staged != null}" }
        args == listOf("off") || args == listOf("stage", "off") -> { staged = null; Ticker.refresh(type); "sun: staging off" }
        args.size != 2 || args[0] != "stage" -> null
        else -> {
            val london = SunZones.TABLE["Europe/London"]
            fun utc(text: String) = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
            val stage = when (args[1]) {
                "evening" -> Here(london, ZoneId.of("Europe/London"), utc("2027-03-10T16:00"))
                "night" -> Here(london, ZoneId.of("Europe/London"), utc("2027-03-10T22:00"))
                "polar" -> Here(SunZones.Place("Tromsø", 69.65, 18.96), ZoneId.of("Europe/Oslo"), utc("2027-06-21T10:00"))
                "nozone" -> Here(null, ZoneOffset.UTC, utc("2027-03-10T16:00"))
                else -> null
            }
            stage?.let { staged = it; Ticker.refresh(type); "sun: staged ${args[1]}" }
        }
    }
}
