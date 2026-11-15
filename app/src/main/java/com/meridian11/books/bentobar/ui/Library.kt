package com.meridian11.books.bentobar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Part
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.fresh
import com.meridian11.books.bentobar.data.homeArea
import com.meridian11.books.bentobar.data.partOf
import com.meridian11.books.bentobar.items.ItemGroup
import com.meridian11.books.bentobar.items.ItemType
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon
import kotlinx.coroutines.launch

/** A type in the library; [inBar] while the bar has an item of it. */
data class Shelf(val type: ItemType, val name: String, val blurb: String, val inBar: Boolean)

/** The library's list for [cfg]: every type, group by group. An item of a type this version doesn't know has no row here. */
fun shelvesOf(cfg: BarConfig, name: (ItemType) -> String, blurb: (ItemType) -> String): List<Shelf> =
    ItemGroup.entries.flatMap { Items.inGroup(it) }.map { type ->
        Shelf(type, name(type), blurb(type), inBar = cfg.items.any { it.type == type.type })
    }

/** What the filter leaves: the types whose name or description has [query] in it. No query leaves everything. */
fun List<Shelf>.matching(query: String): List<Shelf> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { it.name.contains(q, ignoreCase = true) || it.blurb.contains(q, ignoreCase = true) }
}

/** Something in the library to bring into view, and to give the keyboard focus if what was pressed had it. */
internal data class Seek(val key: String, val focus: Boolean, val serial: Int) {
    companion object {
        fun type(type: String) = "type:$type"
    }
}

/**
 * The row and the button a [Seek] for [key] goes to. Asked once, after the list has settled, then forgotten; the scroll
 * it started goes on in the row's own scope, so forgetting doesn't stop it halfway.
 */
@Composable
private fun sought(key: String, edit: BarEdit): Pair<BringIntoViewRequester, FocusRequester> {
    val bring = remember { BringIntoViewRequester() }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val seek = edit.seek
    LaunchedEffect(seek) {
        if (seek?.key != key) return@LaunchedEffect
        withFrameNanos { }
        if (seek.focus) runCatching { focus.requestFocus() }
        scope.launch { runCatching { bring.bringIntoView() } }
        if (edit.seek == seek) edit.seek = null
    }
    return bring to focus
}

/**
 * The library, the panel's "Add items": the filter, and every type in its group with a +. What a + did is said on the
 * type's row, with Undo. With several [lanes] on screen "Add to" stands under the filter, set to the area in use or the
 * one just opened, and every + says where it adds. Every change is one of [edit]'s commands. A type's row can be
 * carried into the bar ([reorder]: it registers where it stands; the one carried is dimmed meanwhile). The library
 * takes no drop: a row of the bar is removed on the trash under the list.
 */
@Composable
internal fun Library(cfg: BarConfig, edit: BarEdit, lanes: Lanes, reorder: Reorder, modifier: Modifier = Modifier, selected: Boolean = false) {
    val c = Bento.colors
    val res = LocalResources.current
    val kept by Undo.last.collectAsState()
    val over by Undo.over.collectAsState()
    // What the + just added, while its note stands.
    val added = Undo.added(Undo.noted(kept, over), cfg)
    val shelves = remember(cfg, res) { shelvesOf(cfg, { res.getString(it.titleRes) }, { res.getString(it.blurbRes) }) }
    val query = edit.filter
    val shown = remember(shelves, query) { shelves.matching(query) }
    val scroll = rememberScrollState()
    val field = remember { FocusRequester() }
    LaunchedEffect(edit.focusFilter) {
        if (!edit.focusFilter) return@LaunchedEffect
        withFrameNanos { }
        runCatching { field.requestFocus() }
        edit.focusFilter = false
    }
    LaunchedEffect(query) { scroll.scrollTo(0) }
    // Where a + adds, said only while there is a choice.
    val to = if (lanes.several) edit.addTo ?: cfg.homeArea() else null
    val toName = to?.let { stringResource(areaRes(it)) }
    // Something to go to that the filter hides, or that has no row here: nothing goes there later, out of the blue.
    LaunchedEffect(edit.seek) {
        val seek = edit.seek ?: return@LaunchedEffect
        repeat(3) { withFrameNanos { } }
        if (edit.seek == seek) edit.seek = null
    }
    Column(modifier.fillMaxSize().padding(start = Space.l, top = 14.dp, end = 6.dp).testTag("library")) {
        FilterField(edit.filter, { edit.filter = it }, stringResource(R.string.library_filter), Modifier.padding(end = 10.dp).testTag("filter"), field,
            find = BarKeys.FIND.caps(res), clear = BarKeys.ESC.caps(res), held = reorder.busy, ends = selected)
        if (query.isNotBlank()) Text(pluralStringResource(R.plurals.add_filter_count, shown.size, shown.size, shelves.size),
            Modifier.padding(start = Space.xs, top = 10.dp, end = Space.xs).semantics { liveRegion = LiveRegionMode.Polite }.testTag("filter-count"),
            style = Bento.type.help, color = c.ink3)
        if (to != null) Row(Modifier.fillMaxWidth().padding(top = 10.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val label = stringResource(R.string.areas_add_to)
            val areas = areaChoices()
            Text(label, Modifier.padding(start = Space.xs), style = Bento.type.small, color = c.ink2, maxLines = lines())
            Spacer(Modifier.width(Space.m))
            Segment(areas.map { SegmentPart(it.second, spoken = "$label ${it.second}") }, areas.indexOfFirst { it.first == to }, { edit.addTo = areas[it].first },
                Modifier.weight(1f).testTag("add-to"), sides = true)
        }
        // That row is pinned and has its edge: the list scrolls under a line.
        if (to != null) Box(Modifier.padding(top = 10.dp, end = 10.dp).fillMaxWidth().height(1.dp).background(c.line).testTag("library-edge"))
        Column(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { reorder.libraryView = it }.scrollThumb(scroll, c.thumb).verticalScroll(scroll).padding(end = 10.dp, bottom = Space.m)) {
            ItemGroup.entries.forEach { group ->
                val here = shown.filter { it.type.group == group }
                if (here.isEmpty()) return@forEach
                key(group) { GroupHead(group) }
                here.forEach { shelf ->
                    key(shelf.type.type) { TypeRow(shelf, cfg, added?.takeIf { it.type == shelf.type.type }, edit, toName, reorder) }
                }
            }
        }
    }
}

/** A row of the library while it is carried into the bar: still in its place, dimmed. */
private const val CARRIED_ALPHA = 0.4f

@Composable
private fun GroupHead(group: ItemGroup) {
    val c = Bento.colors
    Column(Modifier.fillMaxWidth().padding(start = Space.xs, top = Space.m, bottom = Space.xs).testTag("group:${group.name}")) {
        Text(stringResource(group.title), Modifier.semantics { heading() }, style = Bento.type.help.copy(fontWeight = FontWeight.SemiBold), color = c.ink3)
        group.caption?.let { Text(stringResource(it), style = Bento.type.help, color = c.ink3) }
    }
}

/**
 * A type: glyph, name ("In your bar" after it), what it is, and for a type that comes waiting on its rule where its +
 * puts it. Right after a + it says "Added" in the description's place, with Undo, for a note's time ([NoteTime]:
 * the pointer over those words or the keyboard in that Undo holds it; the + itself, where both are after the press,
 * does not). Its + always adds a fresh item.
 * [to] is the area's name while "Add to" is on screen: the + and both lines then say it.
 */
@Composable
private fun TypeRow(shelf: Shelf, cfg: BarConfig, added: ItemConfig?, edit: BarEdit, to: String?, reorder: Reorder) {
    val c = Bento.colors
    val type = shelf.type
    val (bring, plus) = sought(Seek.type(type.type), edit)
    val source = From.TYPE to type.type
    val carried = reorder.carried?.let { it.from == From.TYPE && it.type == type.type } == true
    val hold = remember { NoteHold() }
    if (added != null) NoteTime(hold)
    Row(Modifier.bringIntoViewRequester(bring).testTag("type:${type.type}").fillMaxWidth().heightIn(min = TYPE_ROW)
        .onGloballyPositioned { reorder.sources[source] = it }.alpha(if (carried) CARRIED_ALPHA else 1f)
        .padding(start = Space.xs, top = Space.xs, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        SymIcon(type.icon, size = 18.sp, color = c.ink2)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f).padding(vertical = TYPE_AIR)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shelf.name, Modifier.weight(1f, fill = false), style = Bento.type.label, color = c.ink, maxLines = lines(), overflow = TextOverflow.Ellipsis)
                if (shelf.inBar) Text(stringResource(R.string.library_in_bar), Modifier.padding(start = Space.s), style = Bento.type.help, color = c.ink3, maxLines = lines())
            }
            if (added != null) {
                val rule = if (added.whenActive && cfg.partOf(added) == Part.LOWER) type.trigger?.short(added) ?: stringResource(R.string.row_when_active) else null
                Row(Modifier.holdsNote(hold).testTag("added"), verticalAlignment = Alignment.CenterVertically) {
                    SymIcon(Sym.CHECK, size = 14.sp, color = c.accent)
                    Spacer(Modifier.width(Space.xs))
                    val at = if (to != null) stringResource(areaRes(added.area)) else null
                    Text(when {
                        at != null && rule != null -> stringResource(R.string.add_added_to_rule, at, rule)
                        at != null -> stringResource(R.string.add_added_to, at)
                        rule != null -> stringResource(R.string.add_added_rule, rule)
                        else -> stringResource(R.string.add_added)
                    },
                        style = Bento.type.help.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = lines(2), overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text(shelf.blurb, style = Bento.type.help, color = c.ink3)
                if (type.addsWhenActive) {
                    val rule = remember(type) { type.trigger?.short(type.fresh("", Position.RIGHT)) } ?: stringResource(R.string.row_when_active)
                    val chevron = cfg.hiddenMode != HiddenMode.SHOW_ALL
                    val words = if (to != null) stringResource(R.string.library_adds_to, to, rule)
                        else stringResource(if (chevron) R.string.library_adds_behind else R.string.library_adds_when_active, rule)
                    Text(words, Modifier.said(arrowInWords(words)).testTag("adds"),
                        style = Bento.type.help.copy(fontWeight = FontWeight.Medium), color = c.ink2)
                }
            }
        }
        Spacer(Modifier.width(Space.m).onGloballyPositioned { reorder.stops[source] = it })
        if (added != null) {
            LibraryUndo(edit, hold)
            Spacer(Modifier.width(2.dp))
        }
        BentoIconButton(Sym.ADD, if (to != null) stringResource(R.string.library_add_to, shelf.name, to) else stringResource(R.string.library_add, shelf.name),
            { edit.add(type) }, Modifier.focusRequester(plus).testTag("add:${type.type}"))
    }
}

/** The one Undo, where the library says what it just did. */
@Composable
private fun LibraryUndo(edit: BarEdit, hold: NoteHold) {
    var focused by remember { mutableStateOf(false) }
    BentoButton(stringResource(R.string.bar_undo), { edit.undo(focused) },
        Modifier.deafAtFirst().holdsNote(hold).onFocusChanged { focused = it.isFocused }.testTag("library-undo"), kind = ButtonKind.TEXT)
}

/** A type's row at its lowest: a name and one line of help. */
private val TYPE_ROW = 64.dp
/**
 * The room above and below a type's words, inside the row's own padding. It is what a name and one line of help have
 * in a row of [TYPE_ROW]; a row that says more grows by its lines and keeps it, so the list keeps its rhythm.
 */
private val TYPE_AIR = 13.dp
