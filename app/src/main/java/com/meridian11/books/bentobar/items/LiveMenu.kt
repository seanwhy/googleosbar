package com.meridian11.books.bentobar.items

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.Setup
import com.meridian11.books.bentobar.ui.SetupState
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon

// The Live activity's popup and its options in settings. Which one goes first is LiveRules'; this draws it.

/**
 * The popup: one row for each thing happening, in the order [LiveRules.rest] gives (its symbol, its words, and what a
 * screen reader is told of it under them); a row that has somewhere to go opens it and closes the popup. With nothing
 * happening, it says so. Drawn from what the sources said at the last tick, so it changes as they do.
 */
@Composable
internal fun LiveMenu(item: ItemConfig, host: MenuHost) {
    val heard by LiveItem.heard.collectAsState()
    val staged by LiveItem.staged.collectAsState()
    val rows = LiveRules.rest(staged ?: LiveItem.on(item, heard))
    MenuCard(Sym.RADIO_BUTTON_CHECKED, stringResource(R.string.item_live_title), if (rows.isEmpty()) stringResource(R.string.live_none) else null) {
        rows.forEach { LiveRow(it, host) }
        if (rows.isNotEmpty()) MenuDivider()
        MenuEntry(Sym.TUNE, stringResource(R.string.live_choose)) { host.openItemSettings(item.id) }
    }
}

/** One thing happening. With somewhere to go it is an entry of the menu; without, the same row that takes no click. */
@Composable
private fun LiveRow(n: LiveNow, host: MenuHost) {
    val open = n.open
    if (open != null) {
        MenuEntry(n.icon, n.text, sub = n.desc) {
            host.close()
            // A source's own code: whatever it meets (an app that is gone, a refused start) must not reach the popup.
            try { open() } catch (_: Exception) {}
        }
        return
    }
    // As MenuEntry lays its rows out, so the two kinds stand in one column.
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box { SymIcon(n.icon, size = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(n.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(n.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The item's own settings: one switch for each type that can stand here, by its name. A source that needs an access it
 * does not have says so under its switch; the way to it is in that type's own options and in Setup, not here.
 */
@Composable
internal fun LiveOptions(item: ItemConfig, set: (ItemConfig) -> Unit) {
    // Observed: the settings window refreshes it as it comes back, so an access given meanwhile shows.
    val setup by Setup.state.collectAsState()
    for (source in LiveItem.sources()) {
        val switch = LiveItem.switch(source.type)
        val missing = missing(source, setup)
        SwitchRow(source.title, switch.of(item), help = missing?.let { stringResource(R.string.live_needs, stringResource(it)) }) { set(switch.set(item, it)) }
    }
}

/** The name of the first access [source] needs and does not have, as Setup's row names it; null: it has what it needs. */
private fun missing(source: ItemType, setup: SetupState): Int? = when {
    Manifest.permission.READ_CALENDAR in source.permissions && !setup.calendar -> R.string.setup_calendar_title
    source.notificationAccess && !setup.mediaAccess -> R.string.setup_listener_title
    else -> source.needs.firstOrNull { it !in setup.access }?.let(Accesses::title)
}
