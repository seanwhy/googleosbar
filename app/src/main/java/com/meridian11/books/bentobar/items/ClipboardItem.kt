package com.meridian11.books.bentobar.items

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.ui.ChoiceRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.SearchField
import com.meridian11.books.bentobar.ui.SectionLabel
import com.meridian11.books.bentobar.ui.SmallIconButton
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Clipboard and scratchpad: pinned snippets kept with the item and recent plain-text copies captured
 * in memory while this menu or settings window is open. Clicking an entry copies it.
 *
 * What someone copies or pins is their own business ([discreet]): no snippet text is ever logged.
 */
object ClipboardItem : ItemType("clipboard", R.string.item_clipboard_title, Sym.CONTENT_PASTE, R.string.item_clipboard_desc) {
    override val group = ItemGroup.TOOLS
    override val menuWidthDp = 340
    override val canBeActive = true
    override val discreet = true
    override val trigger = Trigger(R.string.trigger_clipboard, R.string.trigger_clipboard_short)

    /** Recent plain-text copies in this session, held in memory only. */
    private val recent = MutableStateFlow<List<String>>(emptyList())
    @Volatile private var staged: List<String>? = null
    private var listenerRegistered = false

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { readPrimaryClip() }

    private fun clipboard(): ClipboardManager? = Env.app.getSystemService(ClipboardManager::class.java)

    /** Reads the current primary clip if Android gives it to us (while one of BentoBar's windows has focus). */
    internal fun readPrimaryClip() {
        if (staged != null) return
        val cm = clipboard() ?: return
        // The clip's own text only: nothing is opened on the main thread to read what a copied file or link holds.
        val text = runCatching {
            val clip = cm.primaryClip?.takeIf { it.itemCount > 0 } ?: return@runCatching null
            val sensitive = clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
            ClipboardRules.recentText(clip.getItemAt(0)?.text, sensitive)
        }.getOrNull()
        if (text != null) {
            val next = ClipboardRules.recordRecent(recent.value, text)
            if (next != recent.value) {
                recent.value = next
                Ticker.refresh(type)
            }
        }
    }

    override fun onLive() {
        if (!listenerRegistered) {
            listenerRegistered = runCatching { clipboard()?.addPrimaryClipChangedListener(clipListener) }.isSuccess
        }
        readPrimaryClip()
    }

    override fun onIdle() {
        if (listenerRegistered) {
            runCatching { clipboard()?.removePrimaryClipChangedListener(clipListener) }
            listenerRegistered = false
        }
    }

    private fun currentRecent(): List<String> = staged ?: recent.value

    override fun state(item: ItemConfig): ItemState {
        val pins = ClipboardRules.pins(item)
        val bar = ClipboardRules.bar(
            item = item,
            pins = pins,
            recent = currentRecent(),
            emptyDesc = Env.str(R.string.clipboard_empty),
            countDesc = { n -> Env.plural(R.plurals.clipboard_desc_count, n, n) },
        )
        return ItemState(icon = bar.icon, filled = bar.filled, text = bar.text, active = bar.active,
            desc = bar.desc, tooltip = bar.tooltip, textLimit = ClipboardRules.BAR_CHARS)
    }

    /** Puts [snippet] on the clipboard and closes the popup. */
    internal fun pick(snippet: String, host: MenuHost) {
        Env.copy(snippet)
        recent.value = ClipboardRules.recordRecent(recent.value, snippet)
        Ticker.refresh(type)
        host.close()
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        val recents by recent.collectAsState()
        // When the popup opens, its dialog window gains focus a moment later, allowing Android 10+ clipboard access.
        LaunchedEffect(Unit) {
            readPrimaryClip()
            delay(120)
            readPrimaryClip()
        }
        val pins = remember(item) { ClipboardRules.pins(item) }
        val unpinned = ClipboardRules.unpinnedRecent(staged ?: recents, pins)
        val total = pins.size + unpinned.size
        MenuCard(
            Sym.CONTENT_PASTE,
            stringResource(R.string.item_clipboard_title),
            if (total == 0) stringResource(R.string.clipboard_empty) else pluralStringResource(R.plurals.clipboard_count, total, total),
        ) {
            if (pins.isNotEmpty()) {
                SectionLabel(stringResource(R.string.clipboard_pinned_section))
                pins.forEach { snippet ->
                    val line = ClipboardRules.preview(snippet, ClipboardRules.MENU_CHARS)
                    MenuEntry(
                        icon = Sym.PUSH_PIN,
                        label = line,
                        trailing = {
                            SmallIconButton(Sym.CLOSE, stringResource(R.string.clipboard_unpin)) {
                                Store.updateItem(item.id) { ClipboardRules.removePin(it, snippet) }
                            }
                        },
                    ) { pick(snippet, host) }
                }
            }
            if (unpinned.isNotEmpty()) {
                SectionLabel(stringResource(R.string.clipboard_recent_section))
                unpinned.forEach { snippet ->
                    val line = ClipboardRules.preview(snippet, ClipboardRules.MENU_CHARS)
                    MenuEntry(
                        icon = Sym.CONTENT_COPY,
                        label = line,
                        trailing = if (pins.size < ClipboardRules.MOST_PINS && ClipboardRules.pinsWhole(snippet)) {
                            {
                                SmallIconButton(Sym.PUSH_PIN, stringResource(R.string.clipboard_pin)) {
                                    Store.updateItem(item.id) { ClipboardRules.addPin(it, snippet) }
                                }
                            }
                        } else null,
                    ) { pick(snippet, host) }
                }
            }
            MenuNote(stringResource(R.string.clipboard_note))
            MenuDivider()
            if (unpinned.isNotEmpty()) {
                MenuEntry(Sym.DELETE, stringResource(R.string.clipboard_clear_recent)) {
                    recent.value = emptyList()
                    Ticker.refresh(type)
                }
            }
            MenuEntry(Sym.EDIT, stringResource(R.string.clipboard_edit)) { host.openItemSettings(item.id) }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        ChoiceRow(
            stringResource(R.string.clipboard_bar_label),
            listOf("preview" to stringResource(R.string.clipboard_bar_preview), "count" to stringResource(R.string.clipboard_bar_count)),
            item.opt("bar", "preview"),
        ) { set(item.with("bar", it.takeIf { v -> v != "preview" })) }

        key(item.id) {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(stringResource(R.string.clipboard_pinned_section), style = MaterialTheme.typography.labelLarge)
                val pins = remember(item) { ClipboardRules.pins(item) }
                if (pins.size < ClipboardRules.MOST_PINS) {
                    SearchField(
                        label = stringResource(R.string.clipboard_add_label),
                        placeholder = stringResource(R.string.clipboard_add_hint),
                        submit = stringResource(R.string.clipboard_add_button),
                        onChange = {},
                        onEnter = { text ->
                            ClipboardRules.clean(text)?.let { set(ClipboardRules.addPin(item, it)) }
                        },
                    )
                } else {
                    MenuNote(stringResource(R.string.clipboard_full))
                }
                if (pins.isEmpty()) {
                    MenuNote(stringResource(R.string.clipboard_empty))
                } else {
                    pins.forEachIndexed { i, pin ->
                        PinnedOptionRow(pin, first = i == 0, last = i == pins.lastIndex,
                            onMove = { d -> set(ClipboardRules.movePin(item, pin, d)) },
                            onRemove = { set(ClipboardRules.removePin(item, pin)) })
                    }
                }
            }
        }
    }

    override val stages = listOf("stage sample", "stage empty")

    override fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        null -> "staged=${staged != null} recent=${recent.value.size}"
        "stage" -> when (args.getOrNull(1)) {
            "sample" -> {
                staged = listOf("meridian11labs@gmail.com", "git status -sb", "https://github.com/Meridian11Labs/bentobar")
                Ticker.refresh(type)
                "staged 3 clips"
            }
            "empty" -> {
                staged = emptyList()
                Ticker.refresh(type)
                "staged empty clipboard"
            }
            else -> null
        }
        "off" -> {
            staged = null
            Ticker.refresh(type)
            "staging off"
        }
        else -> null
    }
}

@Composable
private fun PinnedOptionRow(pin: String, first: Boolean, last: Boolean, onMove: (Int) -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            ClipboardRules.preview(pin, ClipboardRules.MENU_CHARS),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val up = stringResource(R.string.clipboard_move_up)
        val down = stringResource(R.string.clipboard_move_down)
        val remove = stringResource(R.string.clipboard_remove)
        IconButton(onClick = { onMove(-1) }, enabled = !first, modifier = Modifier.semantics { contentDescription = up }) {
            SymIcon(Sym.ARROW_UPWARD)
        }
        IconButton(onClick = { onMove(1) }, enabled = !last, modifier = Modifier.semantics { contentDescription = down }) {
            SymIcon(Sym.ARROW_DOWNWARD)
        }
        IconButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = remove }) {
            SymIcon(Sym.CLOSE)
        }
    }
}
