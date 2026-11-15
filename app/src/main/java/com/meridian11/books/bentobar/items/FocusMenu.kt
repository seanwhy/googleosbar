package com.meridian11.books.bentobar.items

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.ui.ChoiceRow
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Dates
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym

/**
 * Focus's popup: without the access, what it is for and the way to it; with nothing running, Start; while a session runs,
 * its time left, +10 min and End now. Everything is read again with each tick.
 */
@Composable
internal fun FocusMenu(item: ItemConfig, host: MenuHost) {
    rememberTick()
    val s = FocusItem.shown()
    val title = stringResource(R.string.item_focus_title)
    when {
        s != null -> {
            val left = FocusRules.left(s, Now.wall())
            MenuCard(Sym.SELF_IMPROVEMENT, title,
                if (left == null) stringResource(R.string.focus_endless) else stringResource(R.string.focus_left, Fmt.clock(left))) {
                if (left != null) InfoRow(stringResource(R.string.focus_ends), endsAt(s.endsAt))
                MenuNote(stringResource(R.string.focus_note))
                MenuDivider()
                if (left != null) MenuEntry(Sym.MORE_TIME, stringResource(R.string.focus_more)) { FocusItem.more() }
                MenuEntry(Sym.STOP, stringResource(R.string.focus_end_now)) { FocusItem.endNow(); host.close() }
            }
        }
        !FocusItem.granted() -> MenuCard(Sym.SELF_IMPROVEMENT, title, stringResource(R.string.focus_needs_access)) {
            MenuNote(stringResource(Accesses.explain(Access.DO_NOT_DISTURB)))
            MenuEntry(Sym.TOGGLE_ON, stringResource(Accesses.allow(Access.DO_NOT_DISTURB))) {
                host.close()
                Accesses.open(Env.app, Access.DO_NOT_DISTURB)
            }
        }
        else -> MenuCard(Sym.SELF_IMPROVEMENT, title, stringResource(R.string.focus_ready)) {
            if (FocusItem.refused) MenuNote(stringResource(R.string.focus_refused))
            MenuEntry(Sym.PLAY_ARROW, stringResource(R.string.focus_start), detail = length(FocusRules.minutes(FocusItem.minutes.of(item)))) {
                FocusItem.start(item)
            }
            MenuNote(stringResource(R.string.focus_note))
        }
    }
}

/** The clock time a session ends at, in the user's 12 or 24 hours. */
private fun endsAt(millis: Long): String = Dates.format(Dates.timeSkeleton(DateFormat.is24HourFormat(Env.app)), millis)

@Composable
private fun length(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.focus_until_off) else stringResource(R.string.common_duration_min, minutes)

/** The item's options: how long a click's session is, and (Android 15 and up) the dark theme with it. */
@Composable
internal fun FocusOptions(item: ItemConfig, set: (ItemConfig) -> Unit) {
    ChoiceRow(stringResource(R.string.focus_length), listOf("25", "50", "90", "0").map { it to length(it.toInt()) }, FocusItem.minutes.of(item)) {
        set(FocusItem.minutes.set(item, it))
    }
    if (FocusItem.darkOffered) SwitchRow(stringResource(R.string.focus_dark), FocusItem.dark.of(item), help = stringResource(R.string.focus_dark_help)) {
        set(FocusItem.dark.set(item, it))
    }
}
