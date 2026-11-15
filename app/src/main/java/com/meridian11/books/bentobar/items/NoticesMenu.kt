package com.meridian11.books.bentobar.items

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.Setup
import com.meridian11.books.bentobar.ui.SliderRow
import com.meridian11.books.bentobar.ui.SmallIconButton
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Sym
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Notification icons' popup and its options in settings. What counts and in which order is NoticesRules'; this draws it.

/**
 * The popup: a row for each notification, the newest first (its app's icon, its title, its app and what it says), a
 * click opens it and closes the popup, a × dismisses one the user may dismiss, and "Clear all" clears those. Without the
 * access, what it is for and the way to it. Drawn from the list as it is now, so a notification that comes or goes while
 * the popup is open comes or goes in it.
 */
@Composable
internal fun NoticesMenu(item: ItemConfig, host: MenuHost) {
    val read by Notices.now.collectAsState()
    val staged by NoticesItem.staged.collectAsState()
    // Observed, and refreshed as the popup opens (NoticesItem.menuOpening): the access may have been given since.
    val setup by Setup.state.collectAsState()
    val granted = NoticesItem.granted(setup.access)
    val rows = NoticesRules.rows(staged?.notices ?: read)
    val subtitle = when {
        !granted -> stringResource(R.string.notices_needs_access)
        rows.isEmpty() -> stringResource(R.string.notices_none)
        else -> pluralStringResource(R.plurals.notices_count, rows.size, rows.size)
    }
    MenuCard(Sym.NOTIFICATIONS, stringResource(R.string.item_notices_title), subtitle) {
        if (!granted) {
            MenuNote(stringResource(Accesses.explain(Access.NOTICES)))
            if (remember { Env.sideloaded() }) MenuNote(stringResource(R.string.media_access_restricted))
            MenuEntry(Sym.TOGGLE_ON, stringResource(Accesses.allow(Access.NOTICES))) {
                host.close()
                runCatching { Accesses.open(Env.app, Access.NOTICES) }
            }
        } else {
            val words = NoticesItem.text.of(item)
            rows.forEach { n -> key(n.key) { NoticeRow(n, words, host) } }
            MenuNote(stringResource(R.string.notices_note))
            if (rows.any { it.clearable }) {
                MenuDivider()
                MenuEntry(Sym.CLEAR_ALL, stringResource(R.string.notices_clear_all)) { NoticeAccess.clearAll() }
            }
        }
    }
}

/**
 * One notification: its title (its app's name where it has none) and under it its app and, with "What they say" on, its
 * text; a progress at the end. The app's icon where Android shows BentoBar the app, else the bell.
 */
@Composable
private fun NoticeRow(n: Notices.Notice, words: Boolean, host: MenuHost) {
    val text = n.text?.takeIf { words }
    val sub = when {
        n.title == null -> text
        text == null -> n.app
        else -> stringResource(R.string.notices_row, n.app, text)
    }
    MenuEntry(Sym.NOTIFICATIONS, n.title ?: n.app, detail = n.progress?.let { Fmt.percent(it.toDouble()) }, sub = sub, image = appIcon(n.pkg),
        trailing = if (!n.clearable) null else {
            { SmallIconButton(Sym.CLOSE, stringResource(R.string.notices_dismiss, n.app)) { NoticeAccess.dismiss(n.key) } }
        }) { open(n, host) }
}

/** Opens [n] and closes the popup; says so when Android refused it and the app has no window to open instead. */
private fun open(n: Notices.Notice, host: MenuHost) {
    host.close()
    runCatching { if (!NoticeAccess.open(Env.app, n)) Toast.makeText(Env.app, Env.str(R.string.notices_cant_open), Toast.LENGTH_SHORT).show() }
}

/** An app's icon for a row: null until it is drawn, or for an app Android doesn't show BentoBar (the row then has the bell). */
@Composable
private fun appIcon(pkg: String): ImageBitmap? {
    val icon by produceState(NoticeIcons.drawn(pkg), pkg) { if (value == null) value = withContext(Dispatchers.Default) { NoticeIcons.draw(pkg) } }
    return icon
}

/** The item's own settings: how many apps the bar shows, whether ongoing ones count, and whether the popup shows what they say. */
@Composable
internal fun NoticesOptions(item: ItemConfig, set: (ItemConfig) -> Unit) {
    val resources = LocalResources.current
    SliderRow(stringResource(R.string.notices_limit), NoticesItem.limit.of(item), NoticesItem.limit.range,
        { resources.getQuantityString(R.plurals.notices_apps, it, it) }) { set(NoticesItem.limit.set(item, it)) }
    SwitchRow(stringResource(R.string.notices_ongoing), NoticesItem.ongoing.of(item), help = stringResource(R.string.notices_ongoing_help)) {
        set(NoticesItem.ongoing.set(item, it))
    }
    SwitchRow(stringResource(R.string.notices_text), NoticesItem.text.of(item), help = stringResource(R.string.notices_text_help)) {
        set(NoticesItem.text.set(item, it))
    }
}
