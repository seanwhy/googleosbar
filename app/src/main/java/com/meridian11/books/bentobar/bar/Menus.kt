package com.meridian11.books.bentobar.bar

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.Display
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Standing
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.hide
import com.meridian11.books.bentobar.data.notDrawn
import com.meridian11.books.bentobar.data.setWhenActive
import com.meridian11.books.bentobar.data.show
import com.meridian11.books.bentobar.data.standing
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.items.MenuHost
import com.meridian11.books.bentobar.items.Ticker
import com.meridian11.books.bentobar.ui.BentoBarTheme
import com.meridian11.books.bentobar.ui.GlassLook
import com.meridian11.books.bentobar.ui.LocalMenuGlass
import com.meridian11.books.bentobar.ui.MenuGlassState
import com.meridian11.books.bentobar.ui.MenuMotion
import com.meridian11.books.bentobar.ui.MenuRoom
import com.meridian11.books.bentobar.ui.ShownGlass
import com.meridian11.books.bentobar.ui.menuGlass
import com.meridian11.books.bentobar.ui.menuShadow
import com.meridian11.books.bentobar.ui.ChoiceLabel
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuSegment
import com.meridian11.books.bentobar.ui.SectionLabel
import com.meridian11.books.bentobar.ui.Undo
import com.meridian11.books.bentobar.util.Sym

/**
 * The card every BentoBar popup sits on (docs/design/1.3): glass over a blur where the platform blurs
 * behind windows, a solid card where it doesn't, with a shadow drawn below it. [glass] is the popup's
 * state at this frame, from its window's clock: the card unfolds down from its top edge and its blocks
 * drop into place ([DropColumn]); null draws it at rest (a preview).
 */
@Composable
fun MenuSurface(width: Dp, maxHeight: Dp, glass: MenuGlassState?, content: @Composable () -> Unit) {
    BentoBarTheme {
        val g = glass ?: remember { MenuGlassState().apply { presence = 1f; shadow = 1f; clockMs = Float.POSITIVE_INFINITY; heightDp = Float.MAX_VALUE } }
        val dark = isSystemInDarkTheme()
        val scheme = MaterialTheme.colorScheme
        // "Tint with system colors" (visual.md §6): the veil, or the solid card, takes half of the palette's secondary
        // container (onSecondary in dark theme): a tint, not a wash, and 0.04 more of it so text stays as readable.
        val tint = Store.config.collectAsState().value.systemTint
        val hue = if (dark) scheme.onSecondary else scheme.secondaryContainer
        val look = GlassLook(
            veil = when {
                g.blur && tint -> lerp(scheme.surfaceContainerLowest, hue, 0.5f).copy(alpha = if (dark) 0.66f else 0.60f)
                g.blur -> scheme.surfaceContainerLowest.copy(alpha = if (dark) 0.62f else 0.56f)
                tint -> lerp(scheme.surfaceContainerHigh, hue, 0.5f)
                else -> scheme.surfaceContainerHigh
            },
            rim = Color.White.copy(alpha = if (dark) 0.44f else 0.80f),
            hairline = Color.Black.copy(alpha = if (dark) 0.28f else 0.20f),
            dark = dark,
        )
        val density = LocalDensity.current.density
        // The window is the card: its shadow is drawn in a window of its own (MenuShadow).
        Box {
            Box(
                Modifier.width(width)
                    .onSizeChanged {
                        g.fullHeightPx = it.height
                        // Open and at rest: the glass follows its contents when they change (a city added, a flight found).
                        if (g.clockMs.isInfinite() && glass != null) g.heightDp = it.height / density
                    }
                    .graphicsLayer {
                        alpha = g.alpha
                        clip = true
                        shape = ShownGlass(g.heightDp * density, MenuMotion.RADIUS_DP * density)
                    }
                    .menuGlass(g, look),
            ) {
                // On glass, text and fills are made for what shows through (visual.md §5): grey text halfway to the main
                // text's colour, the main text in dark theme halfway to white, tracks and tiles see-through tints of it.
                // The solid card keeps the scheme as it is.
                val inks = if (!g.blur) scheme else scheme.copy(
                    onSurface = if (dark) lerp(scheme.onSurface, Color.White, 0.5f) else scheme.onSurface,
                    onSurfaceVariant = lerp(scheme.onSurfaceVariant, if (dark) lerp(scheme.onSurface, Color.White, 0.5f) else scheme.onSurface, 0.5f),
                    surfaceContainerHigh = scheme.onSurface.copy(alpha = if (dark) 0.10f else 0.08f),
                    surfaceContainerHighest = scheme.onSurface.copy(alpha = if (dark) 0.14f else 0.12f),
                )
                // Text with no colour of its own takes the glass's (Material's Surface did this; the glass is drawn here).
                MaterialTheme(colorScheme = inks) {
                CompositionLocalProvider(LocalMenuGlass provides glass, LocalContentColor provides inks.onSurface) {
                    Column(
                        Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())
                            .graphicsLayer { alpha = g.contents },
                    ) { content() }
                }
                }
            }
        }
    }
}

/**
 * A popup's shadow, in a window of its own just below the popup's (`MenuWindow`): the popup's window is
 * framed to its glass for the blur, which clips whatever it draws outside the glass. Laid out exactly as
 * [MenuSurface], so the shadow lies under the card, and drawn from the same [glass] every frame.
 */
@Composable
fun MenuShadow(width: Dp, glass: MenuGlassState) {
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current.density
    Box(Modifier.padding(start = MenuRoom.side, end = MenuRoom.side, top = MenuRoom.top, bottom = MenuRoom.bottom)) {
        Box(Modifier.width(width).height((glass.fullHeightPx / density).dp).menuShadow(glass, dark))
    }
}

/**
 * An item's own menu, drawn for the item as the layout has it now: a menu that changes its item (a
 * city added, a flight tracked) is drawn again with the new options. An item that is removed while
 * its menu is open closes it.
 */
@Composable
fun ItemMenu(itemId: String, host: MenuHost) {
    val cfg by Store.config.collectAsState()
    val item = cfg.items.firstOrNull { it.id == itemId }
    val menu = item?.let { Items.of(it.type)?.menu }
    if (item == null || menu == null) { LaunchedEffect(itemId) { host.close() }; return }
    menu(item, host)
}

/**
 * Right-click on an item in the bar. It says what Visibility in settings says, in the same words, and does it through the
 * same commands: an item is always in the bar, in it only when active (the default mode) or behind ‹; nothing here says
 * Off or Hidden. Remove from the bar deletes the item, as the settings' Remove does, and the menu has no Undo: the item is
 * gone with what its type kept for it ([Undo.removeForGood]), and the library's + makes a new one.
 */
@Composable
fun ItemContextMenu(itemId: String, host: MenuHost, openMenu: () -> Unit) {
    val cfg by Store.config.collectAsState()
    val item = cfg.items.firstOrNull { it.id == itemId } ?: return
    val type = Items.of(item.type) ?: return
    val standing = cfg.standing(item) ?: return
    val behind = stringResource(R.string.visibility_behind)
    val behindSpoken = behind.replace("‹", stringResource(R.string.chevron_spoken))
    MenuCard(type.icon, type.title, when (standing) {
        Standing.ALWAYS -> stringResource(R.string.context_always_in_bar)
        Standing.WHEN_ACTIVE -> stringResource(R.string.visibility_when_active)
        Standing.BEHIND -> behind
    }, plain = true) {
        // Bare lines under a hairline: the head has the item's symbol, and a line's words say what it does.
        MenuDivider()
        if (type.menu != null) MenuEntry(null, stringResource(R.string.common_open)) { openMenu() }
        if (cfg.hiddenMode == HiddenMode.SHOW_ALL) {
            // One tick. A type without a rule is always in the bar here, so it has no such entry.
            val waits = standing == Standing.WHEN_ACTIVE
            if (type.canBeActive) MenuEntry(null, stringResource(R.string.visibility_when_active), checked = waits) {
                Store.update { if (waits) it.show(item.id) else it.setWhenActive(item.id, true) }
            }
        } else if (standing == Standing.BEHIND) {
            MenuEntry(null, stringResource(R.string.visibility_always)) { Store.update { it.show(item.id) } }
            if (type.canBeActive) MenuEntry(null, stringResource(R.string.context_pops_out), checked = item.whenActive) {
                Store.update { it.setWhenActive(item.id, !item.whenActive) }
            }
        } else MenuEntry(null, behind, modifier = Modifier.semantics { contentDescription = behindSpoken }) { Store.update { it.hide(item.id) } }
        // Not for a type that has only one way to show: the settings don't offer it either.
        if (!type.iconOnly) {
            ChoiceLabel(stringResource(R.string.display_show_as))
            val displays = listOf(Display.ICON_AND_TEXT to stringResource(R.string.display_icon_and_text), Display.TEXT to stringResource(R.string.display_text),
                Display.ICON to stringResource(R.string.display_icon))
            MenuSegment(displays.map { it.second }, selected = displays.indexOfFirst { it.first == item.display }) { i ->
                Store.updateItem(item.id) { it.copy(display = displays[i].first) }
                Ticker.refresh()
            }
        }
        MenuDivider()
        MenuEntry(null, stringResource(R.string.context_item_settings)) { host.openItemSettings(item.id) }
        MenuEntry(null, stringResource(R.string.detail_remove)) { host.close(); Undo.removeForGood(item.id) }
    }
}

/**
 * Right-click on ‹, or the list view of hidden items. From the keyboard ([everything], the "BentoBar
 * menu" shortcut) it lists every item in the bar, focused on the first, so arrows and Enter reach
 * any item's menu.
 */
@Composable
fun BentoBarMenu(host: MenuHost, openItem: (ItemConfig) -> Unit, hideBar: () -> Unit, everything: Boolean = false) {
    val states by Ticker.states.collectAsState()
    // Worked out here, not when the menu opened, so an item popping out (or in) updates the list.
    val cfg by Store.config.collectAsState()
    val overflow by com.meridian11.books.bentobar.bar.BarOverflow.ids.collectAsState()
    val hidden = if (everything) cfg.items.filter { Items.of(it.type) != null }
    else cfg.notDrawn { states[it.id]?.active == true }.let { waiting -> cfg.items.filter { it in waiting || it.id in overflow } }
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    if (everything) androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    MenuCard(Sym.WYSIWYG, stringResource(R.string.app_name),
        when {
            everything -> null // it lists every item, not just hidden ones
            hidden.isEmpty() -> stringResource(R.string.barmenu_no_hidden)
            else -> pluralStringResource(R.plurals.barmenu_hidden_count, hidden.size, hidden.size)
        },
        iconRes = com.meridian11.books.bentobar.R.drawable.ic_bentobar) {
        if (hidden.isNotEmpty()) {
            SectionLabel(stringResource(if (everything) R.string.barmenu_all_items else R.string.barmenu_hidden_items))
            hidden.forEachIndexed { i, item ->
                val type = Items.of(item.type) ?: return@forEachIndexed
                val s = states[item.id]
                val label = s?.label ?: s?.text
                MenuEntry(s?.icon?.takeIf { it.isNotEmpty() } ?: type.icon, label ?: type.title, detail = type.title.takeIf { label != null && label != it },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { openItem(item) }
            }
            MenuDivider()
        }
        if (cfg.presenting) MenuEntry(Sym.DESKTOP_WINDOWS, stringResource(R.string.barmenu_presenting_stop)) {
            host.close(); Store.update { it.copy(presenting = false) }
        } else MenuEntry(Sym.DESKTOP_WINDOWS, stringResource(R.string.barmenu_presenting), detail = stringResource(R.string.barmenu_presenting_detail)) {
            host.close(); Store.update { it.copy(presenting = true) }
        }
        MenuEntry(Sym.EDIT, stringResource(R.string.barmenu_edit)) { host.openItemSettings("") }
        MenuEntry(Sym.VISIBILITY_OFF, stringResource(R.string.barmenu_hide), detail = stringResource(R.string.barmenu_hide_detail)) { host.close(); hideBar() }
        Text(stringResource(R.string.barmenu_bring_back), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 4.dp))
    }
}
