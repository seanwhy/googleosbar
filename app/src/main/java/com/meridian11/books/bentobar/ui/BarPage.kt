package com.meridian11.books.bentobar.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.BarOverflow
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Step
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.hide
import com.meridian11.books.bentobar.data.landedIn
import com.meridian11.books.bentobar.data.partOf
import com.meridian11.books.bentobar.data.parts
import com.meridian11.books.bentobar.data.setWhenActive
import com.meridian11.books.bentobar.data.show
import com.meridian11.books.bentobar.data.stepped
import com.meridian11.books.bentobar.data.without
import com.meridian11.books.bentobar.data.withoutArea
import com.meridian11.books.bentobar.items.ItemState
import com.meridian11.books.bentobar.items.ItemType
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.items.Ticker
import com.meridian11.books.bentobar.util.Sym
import kotlinx.coroutines.delay

/** What the panel shows: the bar's list (below the width that has room for both), the library, or the selected item's options. */
enum class PanelSide { BAR, ADD, OPTIONS }

/** A row the list goes to after a change: scrolled into view, and focused if the row it stands for had the focus. */
internal data class Follow(val id: String, val focus: Boolean, val serial: Int)

/**
 * One key of the Bar page: the press ([key] with Alt or Ctrl, or bare; Shift held too is the same press), and what it
 * does in words ([does]). The page's key handler, the keycaps the window draws and the system's list of shortcuts
 * ([BarKeys.listed], which the window hands the system) all read the binding, so they cannot disagree. [also] is a
 * second key that does the same and is not listed.
 */
internal data class Binding(val key: Key, val alt: Boolean = false, val ctrl: Boolean = false, @StringRes val does: Int, val also: Key? = null) {
    fun pressed(e: KeyEvent): Boolean = (e.key == key || (also != null && e.key == also)) && e.isAltPressed == alt && e.isCtrlPressed == ctrl && !e.isMetaPressed

    /** As the system counts keys: for its list of shortcuts. */
    val keycode: Int get() = key.nativeKeyCode
    val modifiers: Int get() = (if (alt) android.view.KeyEvent.META_ALT_ON else 0) or (if (ctrl) android.view.KeyEvent.META_CTRL_ON else 0)

    /** The key held with it, as a keycap's words: "Alt", "Ctrl", or none. */
    fun held(res: Resources): List<String> = listOfNotNull(res.getString(R.string.key_alt).takeIf { alt }, res.getString(R.string.key_ctrl).takeIf { ctrl })

    /** The key itself as a keycap's words: an arrow as its sign, a key by its name. */
    fun sign(res: Resources): String = when (key) {
        Key.DirectionLeft -> "←"
        Key.DirectionRight -> "→"
        Key.DirectionUp -> "↑"
        Key.DirectionDown -> "↓"
        Key.Escape -> res.getString(R.string.key_esc)
        Key.F -> res.getString(R.string.key_f)
        else -> android.view.KeyEvent.keyCodeToString(keycode).removePrefix("KEYCODE_")
    }

    /** The whole press as keycaps: "Ctrl", "F". */
    fun caps(res: Resources): List<String> = held(res) + sign(res)
}

/**
 * The Bar page's keys. Each binding is here and nowhere else. [handler] is the page's, for a press that no control took
 * (nothing in the window has the focus): the window hands it here.
 */
internal object BarKeys {
    val UP = Binding(Key.DirectionUp, alt = true, does = R.string.row_move_up)
    val DOWN = Binding(Key.DirectionDown, alt = true, does = R.string.row_move_down)
    val FIND = Binding(Key.F, ctrl = true, does = R.string.library_filter)
    val DUPLICATE = Binding(Key.D, ctrl = true, does = R.string.detail_duplicate)
    val REMOVE = Binding(Key.Delete, does = R.string.detail_remove, also = Key.Backspace)
    val UNDO = Binding(Key.Z, ctrl = true, does = R.string.bar_undo)
    val ESC = Binding(Key.Escape, does = R.string.key_esc_does)
    val all = listOf(UP, DOWN, FIND, DUPLICATE, REMOVE, UNDO, ESC)

    var handler: ((android.view.KeyEvent) -> Boolean)? = null
    /** Several lanes are on screen, so [AreaKeys] have somewhere to move a row. The page says so. */
    var several = false

    /** What the system's list of shortcuts shows: the page's keys while it is on screen, the areas' while they do something. */
    fun listed(): List<Binding> = if (handler == null) emptyList() else if (several) all + AreaKeys.all else all

    /** The keycaps beside Order: "Alt", "↑ ↓". */
    fun orderCaps(res: Resources): List<String> = UP.held(res) + "${UP.sign(res)} ${DOWN.sign(res)}"
}

/**
 * The keys that move an item to the lane beside it: Alt with ← or →. The binding is here and nowhere else (the page's
 * key handler, the keycaps beside Area, what an empty lane says and the system's list of shortcuts all ask this), so
 * that it can become another one in this one place if a device keeps these for itself. Shift held too is the same
 * press, which is the way round such a device today.
 */
internal object AreaKeys {
    val LEFT = Binding(Key.DirectionLeft, alt = true, does = R.string.areas_key_left)
    val RIGHT = Binding(Key.DirectionRight, alt = true, does = R.string.areas_key_right)
    val all = listOf(LEFT, RIGHT)

    /** Whether [e] asks for the lane on the right (true) or on the left (false); null for any other key. */
    fun side(e: KeyEvent): Boolean? = when {
        RIGHT.pressed(e) -> true
        LEFT.pressed(e) -> false
        else -> null
    }

    /** The keycaps beside Area. */
    fun caps(res: Resources): List<String> = LEFT.held(res) + "${LEFT.sign(res)} ${RIGHT.sign(res)}"

    /** One of the two as it is said: "Alt+←". */
    fun spoken(res: Resources, right: Boolean): String = (if (right) RIGHT else LEFT).caps(res).joinToString("+")
}

/**
 * What the Bar page has of areas right now: the lanes [on] screen in the bar's order, the area the last change closed
 * ([fold], said in a slip with Undo) and the areas the bar has no room for ([noRoom]). Everything about areas is drawn
 * only while there are [several] lanes.
 */
internal data class Lanes(val on: List<Position>, val fold: Position? = null, val noRoom: Set<Position> = emptySet()) {
    val several: Boolean get() = on.size > 1
}

/**
 * The Bar page's commands, one for each thing a click, a key or a screen reader's action can do to the bar, so the three
 * can't differ. Each change to the layout is one step of [Undo] through `LayoutIntents`; this class writes no section,
 * rule or area itself. It also holds what the page remembers between frames: the panel's side, the library's filter,
 * the row to follow, and what was last said.
 */
@Stable
internal class BarEdit(private val res: Resources, side: MutableState<PanelSide>, filter: MutableState<String> = mutableStateOf("")) {
    var onSelect: (String?) -> Unit = {}
    /** The window has room for the list and the panel side by side. */
    var wide = true
    /** The selected item, if it is in the bar. */
    var selected: String? = null
    var side by side
    /** The side the panel shows right now, which is [side] where that can be shown. */
    var showing = PanelSide.ADD
    /** What the library's filter holds. */
    var filter by filter
    /** The filter is to take the keyboard (Ctrl+F, a letter typed with no field in focus). */
    var focusFilter by mutableStateOf(false)
    /** What the library goes to after a change. */
    var seek by mutableStateOf<Seek?>(null)
    var focusedRow: String? = null
    var slipFocused = false
    var follow by mutableStateOf<Follow?>(null)
    /** Why a key or a drop did nothing, for the line under the preview. */
    var said by mutableStateOf<String?>(null)
    /** What a change came to, for a screen reader: "CPU load, 5th of 7 in the bar". */
    var announced by mutableStateOf("")
    /**
     * The areas "Add an area" opened: the window's own, in no layout, so an empty one is gone when the Bar tab is left.
     * One stays here while it has rows and leaves when it closes ([settle]).
     */
    var opened by mutableStateOf(emptySet<Position>())
    /** The area the library adds to while several lanes are on screen; null is the area in use. */
    var addTo by mutableStateOf<Position?>(null)
    /** The item a drop from the library last brought: it arrived as its copy, so its row doesn't open as a clicked + one's does. */
    var landed: String? = null
    /** An empty lane that is to take the keyboard: the one just opened. */
    var focusLane by mutableStateOf<Position?>(null)
    /** The note above the list (a lane closed, an area was removed) is to take the keyboard: what had it went with the lane. */
    var focusFold by mutableStateOf(false)
    /** "Add an area" is to take the keyboard: the empty lane it opened closed under it. */
    var focusAddArea by mutableStateOf(false)
    private var serial = 0
    /** The last change, and whether several lanes were on screen before it. */
    private var judged: Pair<Undo.Kept, Boolean>? = null
    /** The change whose closed lane [settle] has taken out of [opened]. */
    private var settled: Undo.Kept? = null

    private fun home() = if (wide) PanelSide.ADD else PanelSide.BAR
    private fun item(id: String) = Store.config.value.items.firstOrNull { it.id == id }

    /** A click on an item, in the list or the preview: the panel turns to its options. */
    fun select(id: String) {
        said = null
        onSelect(id)
        side = PanelSide.OPTIONS
    }

    /** Esc, or a click on an empty part of the bar's card: the item is let go and the panel turns back. */
    fun letGo() {
        said = null
        onSelect(null)
        side = home()
    }

    /**
     * The selected item is no longer in the bar, and not by this page's Remove (the bar's own menu took it out, or a
     * pasted layout): nothing is selected any more, and a panel that was on its options is where it stands with no item.
     * Left selected, it would turn the panel to its options by itself if a pasted layout brought it back.
     */
    fun left() {
        onSelect(null)
        if (side == PanelSide.OPTIONS) side = home()
    }

    private fun several(kept: Undo.Kept): Boolean {
        judged?.let { if (it.first === kept) return it.second }
        return (lanesOnScreen(kept.before, opened).size > 1).also { judged = kept to it }
    }

    /** The area the last change closed, until Undo or the next change; not one that "Add an area" has opened again since. */
    fun fold(cfg: BarConfig = Store.config.value, kept: Undo.Kept? = Undo.last.value): Position? {
        kept ?: return null
        return foldOf(kept, cfg, several(kept))?.takeUnless { kept === settled && it in opened }
    }

    /** The lanes on screen: the areas with rows and the opened ones, without the one that just closed. */
    fun lanes(cfg: BarConfig = Store.config.value, kept: Undo.Kept? = Undo.last.value): List<Position> =
        lanesOnScreen(cfg, opened - setOfNotNull(fold(cfg, kept)))

    /** After a frame: a lane that closed is not held open, and nothing is added to it or to a lane that is the only one. */
    fun settle(kept: Undo.Kept?, fold: Position?, lanes: Int) {
        if (fold != null && kept !== settled) {
            settled = kept
            if (fold in opened) opened = opened - fold
            if (addTo == fold) addTo = null
        }
        if (lanes < 2 && addTo != null) addTo = null
    }

    /** A choice of "Add an area": its lane is on screen, empty, with the keyboard, and the library adds to it. */
    fun openArea(area: Position) {
        said = null
        opened = opened + area
        addTo = area
        focusLane = area
    }

    /**
     * Remove an area, by its head's trash or a screen reader's action there: every item of it leaves the bar in one
     * step of [Undo] ([withoutArea]) and its lane closes; the page says what went above the list, with the Undo that
     * brings them all back. A selected item that was there is let go. A lane that "Add an area" opened and that is still
     * empty simply closes: nothing changed, so nothing is kept and nothing is said. With the bar's last items gone the
     * page is the empty bar's, one lane, so the lanes opened beside it close too. [focus]: the keyboard was on the
     * trash; from there or from a row of the lane it goes to the note's Undo, as after a lane closes, and with no note
     * to "Add an area".
     */
    fun removeArea(area: Position, focus: Boolean = false) {
        val ids = Store.config.value.items.filter { it.area == area }.map { it.id }
        val inLane = focus || focusedRow in ids
        said = null
        if (ids.isEmpty()) {
            if (area !in opened) return
            opened = opened - area
            if (addTo == area) addTo = null
            if (inLane) focusAddArea = true
            return
        }
        if (!Undo.change(area = area) { it.withoutArea(area) }) return
        opened = if (Store.config.value.items.isEmpty()) emptySet() else opened - area
        if (addTo == area) addTo = null
        if (selected in ids) left()
        if (inLane) focusFold = true
        // The note is a live region and says it.
        announced = ""
    }

    /** The area a + adds to: the one chosen in "Add to" while several lanes are on screen, else the area in use. */
    private fun target(): Position? = addTo?.takeIf { lanes().size > 1 }

    /** "Add items": the library, with the item still selected. */
    fun toLibrary() { side = PanelSide.ADD }

    /** Ctrl+F: the library, and the keyboard in its filter. The item stays selected. */
    fun find() {
        toLibrary()
        focusFilter = true
    }

    /**
     * The + on a type: a fresh item of it, in the area in use or the one "Add to" names, under the line if the type
     * comes waiting on its rule. The bar's list goes to the new row; the keyboard stays on the +.
     */
    fun add(type: ItemType) {
        said = null
        val before = Store.config.value
        val id = Store.addFresh(type, target())
        if (Undo.note(before, added = id)) moved(id, focus = false)
        if (type.type == "cloud_clipboard" && !com.meridian11.books.bentobar.items.CloudClipboardItem.hasAllFilesAccess(com.meridian11.books.bentobar.items.Env.app)) {
            MainActivity.current?.let { act ->
                Notice.post("已添加云剪切板 · 上传本地文件需开启文件读取权限", "去开启") {
                    MainActivity.requestFilePermissions(act)
                }
            }
        }
    }

    fun always(id: String) = change(id) { it.show(id) }

    /** Visibility's second part: "Only when active" by default, "Behind ‹" with the arrow. */
    fun below(id: String) = change(id) { c -> if (c.hiddenMode == HiddenMode.SHOW_ALL) c.setWhenActive(id, true) else c.hide(id) }

    /** Behind ‹: whether the item pops out by its rule. */
    fun popsOut(id: String, on: Boolean) {
        said = null
        Undo.change { it.setWhenActive(id, on) }
    }

    /** Area's choice, a lane's key and a screen reader's "Move to": the item lands in [area], in the part it was in. */
    fun area(id: String, area: Position) = change(id) { it.landedIn(id, area) }

    /**
     * Alt with ← or →: the item goes to the lane beside it on that side, and stays at the last one. With one lane on
     * screen it stays too: the key is a row's either way, so the focus does not wander off with it. False for no row.
     */
    fun sideways(id: String, right: Boolean): Boolean {
        val item = item(id) ?: return false
        laneBeside(lanes(), item.area, right)?.let { area(id, it) }
        return true
    }

    /** Move up or down by one row, across the part line where the item may; where it may not, the reason is [said]. */
    fun step(id: String, down: Boolean) {
        val item = item(id) ?: return
        when (val step = Store.config.value.stepped(id, down)) {
            is Step.Moved -> change(id) { c -> (c.stepped(id, down) as? Step.Moved)?.config ?: c }
            is Step.Stays -> said = when (step.why) {
                Step.Why.NO_RULE -> res.getString(R.string.visibility_no_rule_drop, itemName(res, item))
                Step.Why.PINNED -> res.getString(R.string.order_pinned_said, res.getString(areaRes(item.area)))
            }
            Step.End -> Unit
        }
    }

    /**
     * A drag was let go. Into the bar: the row at its new place, or the library's type in the bar where it was
     * dropped. On the trash: the row removed, as Remove does it. One change and one step of Undo each; the same place
     * again changes nothing and keeps nothing. Under a line the item can't go under, the page says why. True if the
     * layout changed.
     */
    fun dropped(c: Carried, drop: Drop): Boolean = when (drop) {
        is Drop.At -> {
            said = null
            if (c.from != From.BAR) landed = c.id
            val changed = Undo.change(added = c.id.takeIf { c.from != From.BAR }) { it.dropped(c, drop) }
            if (changed) moved(c.id, focus = false)
            changed
        }
        Drop.Off -> {
            val before = Store.config.value
            remove(c.id)
            Store.config.value !== before
        }
        is Drop.Refused -> {
            val item = item(c.id)
            said = if (c.from == From.BAR && item != null) res.getString(R.string.visibility_no_rule_drop, itemName(res, item))
            else Items.of(c.type)?.let { res.getString(R.string.bar_drop_no_rule_add, res.getString(it.titleRes)) }
            false
        }
        Drop.None -> false
    }

    /**
     * Remove from the bar: the item is deleted from the layout, and [Undo] has it until the next change. The focus, if
     * the row had it, goes to the row that takes its place (the one before, at a part's end). The library goes to the
     * item's type, whose + makes a new one; removed in its options, the panel turns to the library for that, with the
     * filter emptied so that the type is there.
     */
    fun remove(id: String) {
        val cfg = Store.config.value
        val item = item(id) ?: return
        val part = cfg.partOf(item) ?: return
        val rows = cfg.parts(item.area)[part]
        val at = rows.indexOfFirst { it.id == id }
        val next = (rows.getOrNull(at + 1) ?: rows.getOrNull(at - 1))?.id
        val focus = focusedRow == id
        said = null
        if (!Undo.change(removed = id) { it.without(id) }) return
        if (selected == id) {
            onSelect(null)
            side = PanelSide.ADD
            filter = ""
        }
        seek = Seek(Seek.type(item.type), false, ++serial)
        if (focus && next != null) follow = Follow(next, true, ++serial)
        else if (focus && fold() != null) focusFold = true
        announced = res.getString(R.string.bar_removed, itemName(res, item))
    }

    /** A copy right after the item, selected. */
    fun duplicate(id: String) {
        val before = Store.config.value
        val item = item(id) ?: return
        if (Items.of(item.type) == null || before.partOf(item) == null) return
        val focus = focusedRow == id
        val copy = Store.duplicate(item)
        if (!Undo.note(before)) return
        select(copy)
        moved(copy, focus)
    }

    /**
     * Ctrl+Z, Undo in a removed row's place, in a closed lane's slip or in a removed area's note, and the library's Undo.
     * A row that comes back, to the bar or to its lane, is followed, and of an area that comes back its first; the library's
     * Undo is gone with what it took back, so the keyboard, if it had it ([focus]), goes to the type's +.
     */
    fun undo(focus: Boolean = false): Boolean {
        said = null
        val kept = Undo.last.value
        val back = kept?.removed ?: movedBy(kept) ?: kept?.area?.let { area -> kept.before.parts(area).let { it.upper + it.lower }.firstOrNull()?.id }
        val row = focusedRow != null || slipFocused
        val added = kept?.added?.let(::item)
        if (!Undo.undo()) return false
        if (back != null) moved(back, row)
        if (focus && added != null) seek = Seek(Seek.type(added.type), true, ++serial)
        return true
    }

    private fun change(id: String, step: (BarConfig) -> BarConfig) {
        said = null
        val focus = focusedRow == id
        if (Undo.change(step = step)) moved(id, focus)
    }

    private fun moved(id: String, focus: Boolean) {
        follow = Follow(id, focus, ++serial)
        val item = item(id) ?: return
        announced = orderWords(res, Store.config.value, item, lanes().size > 1)?.let { res.getString(R.string.row_moved, itemName(res, item), it) }.orEmpty()
    }

    /**
     * The page's keys ([BarKeys]), on the focused row or else the selected item: Alt with ↑ or ↓ moves it by a row,
     * [AreaKeys] to the lane beside it. Esc does one thing, the nearest first: it cancels a
     * drag, empties the filter of the library on screen, or lets the item go. Ctrl+F finds from anywhere. While text is
     * being typed ([typing]) every other key is the field's (in an item's options Esc too); with no field in focus a letter or a digit typed beside the
     * library starts its filter. While something let go is still on its way (into its place, or back) no key does
     * anything: the drop is written when it arrives, and a key would act on the layout before it.
     */
    fun key(e: KeyEvent, typing: Boolean, reorder: Reorder): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        if (reorder.busy && !reorder.live) return true
        if (BarKeys.ESC.pressed(e)) {
            if (reorder.dragging != null) { reorder.cancel(); return true }
            if (showing == PanelSide.ADD && filter.isNotEmpty()) { filter = ""; return true }
            // The library's one field is its filter, and an empty filter has no use for Esc: the item goes from there too.
            if (selected == null || typing && showing != PanelSide.ADD) return false
            letGo()
            return true
        }
        // No field has a use for Ctrl+F, so it finds from inside one too.
        if (BarKeys.FIND.pressed(e)) { find(); return true }
        if (typing) return false
        val target = focusedRow ?: selected
        return when {
            BarKeys.UP.pressed(e) || BarKeys.DOWN.pressed(e) -> target?.let { step(it, down = BarKeys.DOWN.pressed(e)) } != null
            AreaKeys.side(e) != null -> target?.let { sideways(it, AreaKeys.side(e) == true) } == true
            e.isAltPressed -> false
            BarKeys.UNDO.pressed(e) -> { undo(); true }
            BarKeys.DUPLICATE.pressed(e) -> target?.let { duplicate(it) } != null
            e.isCtrlPressed || e.isMetaPressed -> false
            BarKeys.REMOVE.pressed(e) -> target?.let { remove(it) } != null
            showing == PanelSide.ADD && Character.isLetterOrDigit(e.nativeKeyEvent.unicodeChar) -> {
                filter += String(Character.toChars(e.nativeKeyEvent.unicodeChar))
                focusFilter = true
                true
            }
            else -> false
        }
    }
}

private val CARD = RoundedCornerShape(Radius.large)
/** The switch of three parts where the page is one wide pane: as wide as it needs, not as the page. */
private val SWITCH_MID = 452.dp

/**
 * The Bar page: the preview, the bar's one list, and a panel that is the library or the selected item's options. From
 * 1100 dp the list and the panel stand side by side; below, the list is the panel's first part. [turn] counts the
 * times the bar's own menu asked for an item's options: each turns the panel to them. Before BentoBar is [running] the
 * bar can be arranged all the same: only the preview says that it is not in the status bar yet.
 */
@Composable
internal fun BarPage(selected: String?, onSelect: (String?) -> Unit, turn: Int = 0, running: Boolean = true) {
    val cfg by Store.config.collectAsState()
    val states by Ticker.states.collectAsState()
    val layout = LocalWindowLayout.current
    val wide = layout.kind == WindowClass.WIDE
    val res = LocalResources.current
    val view = LocalView.current
    val sideState = rememberSaveable { mutableStateOf(if (selected != null) PanelSide.OPTIONS else PanelSide.BAR) }
    val filter = rememberSaveable { mutableStateOf("") }
    val edit = remember { BarEdit(res, sideState, filter) }
    val reorder = remember { Reorder() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val item = cfg.items.firstOrNull { it.id == selected }
    val kept by Undo.last.collectAsState()
    val overflow by BarOverflow.ids.collectAsState()
    val fold = edit.fold(cfg, kept)
    val lanes = Lanes(edit.lanes(cfg, kept), fold, noRoomAreas(cfg, overflow) { states[it.id]?.active == true })
    val side = when {
        edit.side == PanelSide.OPTIONS && item == null -> if (wide) PanelSide.ADD else PanelSide.BAR
        edit.side == PanelSide.BAR && wide -> PanelSide.ADD
        else -> edit.side
    }
    SideEffect {
        edit.onSelect = onSelect
        edit.wide = wide
        edit.selected = item?.id
        edit.showing = side
        reorder.config = cfg
        reorder.density = density
        reorder.scope = scope
        reorder.onDrop = edit::dropped
        edit.settle(kept, fold, lanes.on.size)
    }
    // Only an item in the bar is selected.
    val left = selected != null && item == null
    LaunchedEffect(selected, left) { if (left) edit.left() }
    LaunchedEffect(turn) { if (turn > 0 && selected != null) edit.side = PanelSide.OPTIONS }
    // A reason is read, then the line is the preview's hint again.
    LaunchedEffect(edit.said) {
        if (edit.said == null) return@LaunchedEffect
        delay(4000)
        edit.said = null
    }
    DisposableEffect(edit, reorder, view) {
        BarKeys.handler = { native -> edit.key(KeyEvent(native), view.onCheckIsTextEditor(), reorder) }
        onDispose { BarKeys.handler = null; BarKeys.several = false }
    }
    SideEffect { BarKeys.several = lanes.several }
    // The one drag of the page: its gesture is here, over the preview, the bar's card and the panel, and so is the copy it carries.
    Box(Modifier.fillMaxSize().onGloballyPositioned { reorder.container = it }.drags(reorder)) {
        Column(Modifier.fillMaxSize().onKeyEvent { edit.key(it, view.onCheckIsTextEditor(), reorder) }
            .padding(start = layout.marginDp.dp, end = layout.marginDp.dp, top = PREVIEW_AIR, bottom = Space.xl)) {
            BarPreview(states, item?.id, edit.said, lanes.noRoom, reorder, lanes.several, off = !running) { id -> if (id != null) edit.select(id) }
            // What a change came to, for a screen reader; the page draws nothing for it.
            Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = edit.announced })
            Spacer(Modifier.height(Space.m + 2.dp))
            if (wide) Row(Modifier.weight(1f)) {
                BarList(cfg, states, item?.id, edit, reorder, lanes, Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(Space.xl))
                Column(Modifier.width(layout.panelDp.dp)) {
                    PanelSwitch(side, item, wide, edit)
                    Spacer(Modifier.height(10.dp))
                    Panel(side, item, cfg, states, edit, reorder, lanes, Modifier.weight(1f))
                }
            } else {
                PanelSwitch(side, item, wide, edit)
                Spacer(Modifier.height(10.dp))
                Panel(side, item, cfg, states, edit, reorder, lanes, Modifier.weight(1f))
            }
        }
        DragCopy(reorder, cfg, states, lanes.several, Modifier.align(AbsoluteAlignment.TopLeft))
    }
}

/**
 * The panel's switch: "Add items", and the selected item by glyph and name ("Select an item", dimmed, with none);
 * below the width with room for both, "Your bar" first.
 */
@Composable
private fun PanelSwitch(side: PanelSide, item: ItemConfig?, wide: Boolean, edit: BarEdit) {
    val res = LocalResources.current
    val sides = if (wide) listOf(PanelSide.ADD, PanelSide.OPTIONS) else PanelSide.entries
    val parts = sides.map {
        when (it) {
            PanelSide.BAR -> SegmentPart(stringResource(R.string.page_bar))
            PanelSide.ADD -> SegmentPart(stringResource(R.string.page_add), icon = Sym.ADD)
            PanelSide.OPTIONS ->
                if (item != null) SegmentPart(itemName(res, item), icon = Items.of(item.type)?.icon ?: Sym.UPDATE)
                else SegmentPart(stringResource(R.string.panel_select_item), enabled = false)
        }
    }
    Segment(parts, sides.indexOf(side), {
        when (sides[it]) {
            PanelSide.BAR -> edit.side = PanelSide.BAR
            PanelSide.ADD -> edit.toLibrary()
            PanelSide.OPTIONS -> edit.side = PanelSide.OPTIONS
        }
    }, (if (LocalWindowLayout.current.kind == WindowClass.MID) Modifier.width(SWITCH_MID.ofText()) else Modifier.fillMaxWidth()).testTag("panel-switch"), role = Role.Tab)
}

/** The panel's card. The card stays; only what is in it travels, a short way, when the side changes. */
@Composable
private fun Panel(side: PanelSide, item: ItemConfig?, cfg: BarConfig, states: Map<String, ItemState>, edit: BarEdit, reorder: Reorder, lanes: Lanes,
                  modifier: Modifier) {
    val shift = with(LocalDensity.current) { Space.l.roundToPx() }
    // The options on their way out are still the item's that was let go.
    val last = remember { object { var item: ItemConfig? = null } }
    if (item != null) last.item = item
    Box(modifier.fillMaxWidth().clip(CARD).background(Bento.colors.panel).testTag("panel")) {
        AnimatedContent(side, transitionSpec = {
            val way = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (slideInHorizontally(Motion.spec(tween(Motion.SETTLE))) { way * shift } + fadeIn(Motion.spec(tween(Motion.SETTLE)))) togetherWith
                (slideOutHorizontally(Motion.spec(tween(Motion.SETTLE))) { -way * shift } + fadeOut(Motion.spec(tween(Motion.SETTLE))))
        }, label = "panel") { shown ->
            when (shown) {
                PanelSide.BAR -> BarList(cfg, states, item?.id, edit, reorder, lanes, Modifier.fillMaxSize())
                PanelSide.ADD -> Library(cfg, edit, lanes, reorder, selected = item != null)
                PanelSide.OPTIONS -> (item ?: last.item)?.let { of -> key(of.id) { ItemOptions(of, cfg, states[of.id], edit, lanes.several) } }
            }
        }
    }
}
