package com.meridian11.books.bentobar.items

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.Meter
import com.meridian11.books.bentobar.ui.SectionLabel
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Screen time: how long the screen was on today, warning from a number of hours, with the apps longest in front in its
 * menu. It needs Usage access, the optional access the Network and Storage menus already ask for, and asks for it the way
 * they do. What it reads is in [ScreenTimeLoad] (the apps only while the menu is open, and forgotten when it closes); the
 * arithmetic in [ScreenTimeRules]. The total is the screen's, not the apps' added up: on a desktop several apps are in
 * front at once.
 */
object ScreenTimeItem : ItemType("screentime", R.string.item_screentime_title, Sym.TIMELAPSE, R.string.item_screentime_desc) {
    override val group = ItemGroup.SYSTEM
    override val canBeActive = true
    // The bar's figure is to the minute.
    override val refreshMs = 5_000L

    /** From how many hours of the screen on the item warns and has something to say. */
    private val from = Threshold("hours", 4, 1..12) { Env.plural(R.plurals.common_hours, it) }
    override val trigger = Trigger(R.string.trigger_screentime, R.string.trigger_screentime_short, from)

    private val words = ScreenTimeRules.Words(
        minutes = { Env.str(R.string.common_bar_minutes, it) },
        hoursMinutes = { h, m -> Env.str(R.string.common_bar_hours_minutes, h, m) },
    )

    /** When the bar last came back (wall clock): the screen has been on since, which a reading from before can't know. */
    @Volatile private var liveSince = 0L
    /** The test hook's day, shown instead of what was read; null: none staged. */
    @Volatile private var staged: ScreenTimeRules.Staged? = null

    private fun dayStart(now: Long) = ScreenTimeRules.dayStart(ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()))

    override fun onLive() {
        liveSince = Now.wall()
        // What was read before the bar went away is as old as the time it was away: read it again now.
        ScreenTimeLoad.day.refresh(Unit)
    }

    // Which apps were in front is not kept while nobody looks.
    override fun onIdle() = ScreenTimeLoad.apps.forget()

    override fun menuOpening(item: ItemConfig) {
        if (staged != null) return
        ScreenTimeLoad.day.refresh(Unit, floorMs = 10_000)
        ScreenTimeLoad.apps.refresh(Unit)
    }

    /** Today at [now]: [access] Usage access was given, [onMs] the screen's time on, [apps] those longest in front (null: not read). */
    private class Today(val access: Boolean, val onMs: Long, val apps: List<ScreenTimeLoad.App>?, val leftOut: Boolean)

    /** The staged day, or what was read today; null while nothing of today is read. Reads only what is at hand. */
    private fun today(now: Long): Today? {
        staged?.let { s -> return Today(s.access, s.onMs, s.apps.map { (name, ms) -> ScreenTimeLoad.App(name, ms, null) }, leftOut = false) }
        val start = dayStart(now)
        val d = ScreenTimeLoad.day.peek(Unit)?.takeIf { it.dayStart == start } ?: return null
        val a = ScreenTimeLoad.apps.peek(Unit)?.takeIf { it.dayStart == start && it.access }
        return Today(d.access, if (d.access) ScreenTimeRules.shown(d.onMs, d.at, liveSince, now, start) else 0, a?.apps, a?.leftOut == true)
    }

    override fun state(item: ItemConfig): ItemState {
        val now = Now.wall()
        if (staged == null) {
            // After midnight what was read is yesterday's: read now, whatever its age.
            val read = ScreenTimeLoad.day.peek(Unit)
            if (read != null && read.dayStart != dayStart(now)) ScreenTimeLoad.day.refresh(Unit) else ScreenTimeLoad.day.want(Unit)
        }
        val t = today(now) ?: return ItemState(icon = Sym.TIMELAPSE, desc = Env.str(R.string.screentime_desc_loading))
        if (!t.access) {
            val desc = Env.str(R.string.screentime_desc_no_access)
            return ItemState(icon = Sym.TIMELAPSE, desc = desc, tooltip = desc)
        }
        val over = ScreenTimeRules.over(t.onMs, from.of(item))
        return ItemState(icon = Sym.TIMELAPSE, text = ScreenTimeRules.text(t.onMs, words), tone = if (over) Tone.WARN else Tone.NORMAL,
            active = over, desc = Env.str(R.string.screentime_desc, Fmt.spokenMinutes(t.onMs)), widthKey = "screentime")
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        // The apps are read while the menu is open (once a minute: the refresher says when), and forgotten when it closes.
        LaunchedEffect(Unit) { while (true) { if (staged == null) ScreenTimeLoad.apps.want(Unit); delay(5_000) } }
        DisposableEffect(Unit) { onDispose { ScreenTimeLoad.apps.forget() } }
        val title = stringResource(R.string.item_screentime_title)
        val t = today(Now.wall())
        when {
            t == null -> MenuCard(Sym.TIMELAPSE, title, stringResource(R.string.usage_loading)) {}
            // Without access: as the Network and Storage menus ask for it, with what this item would read.
            !t.access -> MenuCard(Sym.TIMELAPSE, title, stringResource(R.string.screentime_needs_access)) {
                Text(stringResource(R.string.screentime_optin), style = MaterialTheme.typography.bodyMedium)
                MenuNote(stringResource(R.string.screentime_explain))
                MenuEntry(Sym.TOGGLE_ON, stringResource(R.string.usage_turn_on)) { host.close(); Usage.openSettings(Env.app) }
            }
            else -> {
                val text = ScreenTimeRules.text(t.onMs, words)
                val hours = from.shown(item)
                val over = ScreenTimeRules.over(t.onMs, hours)
                val color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                MenuCard(Sym.TIMELAPSE, title, stringResource(R.string.screentime_today, text)) {
                    InfoRow(stringResource(R.string.screentime_on_today), text, color)
                    Meter(t.onMs.toFloat() / (hours * ScreenTimeRules.HOUR), color)
                    Spacer(Modifier.height(4.dp))
                    MenuNote(stringResource(R.string.screentime_warns_from, pluralStringResource(R.plurals.common_hours, hours, hours)))
                    MenuDivider()
                    SectionLabel(stringResource(R.string.screentime_top))
                    val apps = t.apps
                    when {
                        apps == null -> MenuNote(stringResource(R.string.usage_loading))
                        apps.isEmpty() -> MenuNote(stringResource(R.string.screentime_none))
                        else -> apps.forEach { AppRow(it.label, ScreenTimeRules.text(it.ms, words), it.icon) }
                    }
                    if (t.leftOut && !apps.isNullOrEmpty()) MenuNote(stringResource(R.string.screentime_launcher_only))
                    if ((apps?.size ?: 0) > 1) MenuNote(stringResource(R.string.screentime_side_by_side))
                    MenuDivider()
                    MenuEntry(Sym.SETTINGS, stringResource(R.string.screentime_usage_settings)) { host.close(); Usage.openSettings(Env.app) }
                }
            }
        }
    }

    override val stages = listOf("stage short", "stage long", "stage over", "stage noaccess")

    // No app's name: what someone has in front is their own business.
    override fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        null -> "staged=${staged != null} day=${ScreenTimeLoad.day.peek(Unit)} apps=${ScreenTimeLoad.apps.peek(Unit)}"
        "stage" -> ScreenTimeRules.staged(args.getOrNull(1))?.let { staged = it; Ticker.refresh(type); "staged ${args[1]}" }
        "off" -> { staged = null; Ticker.refresh(type); "staging off" }
        else -> null
    }
}

/** One app in the menu's list, as the Network and Storage menus draw theirs: its icon, its name and its time. */
@Composable
private fun AppRow(label: String, time: String, icon: androidx.compose.ui.graphics.ImageBitmap?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (icon != null) Image(icon, null, Modifier.size(20.dp))
            else SymIcon(Sym.APPS, size = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(time, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Medium)
    }
}
