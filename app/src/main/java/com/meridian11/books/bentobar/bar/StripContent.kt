package com.meridian11.books.bentobar.bar

import com.meridian11.books.bentobar.data.Display
import com.meridian11.books.bentobar.data.Pill
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.items.BarRoute
import com.meridian11.books.bentobar.items.BarSlider
import com.meridian11.books.bentobar.items.ItemState
import com.meridian11.books.bentobar.items.Tone

/**
 * Whether the strip has anything on the screen. One rule, used by [BarController] for its window: never from how many
 * entries it has, because an entry can draw nothing (a spacer without its line, a text item shown as an icon it doesn't
 * have). An empty strip's window is made invisible rather than left up as a transparent one, which is what Play Protect's
 * threat detection flagged (AGENTS.md); the window keeps its exact width and keeps being measured. Pure, so the rule is
 * unit-tested; what an item draws here is what [ItemView] draws.
 */
internal object StripContent {
    /**
     * The parts of an item in the strip. A slider takes the text's place ("Icon and text" draws icon and slider, "Text" the slider
     * alone; shown as an icon only, the item has none). A route line takes the icon's place ("Icon and text" draws line and
     * words, "Icon" the line alone; shown as text only, the item has none). [icon] and [text] say whether ItemView lays those out,
     * [iconDrawn] and [textDrawn] whether that puts anything on the screen: a place for an icon the item doesn't have is empty, and
     * words made of nothing but spaces keep their place and show nothing.
     */
    data class Parts(val slider: BarSlider?, val route: BarRoute?, val icon: Boolean, val text: Boolean, val iconDrawn: Boolean, val textDrawn: Boolean,
                     /** The route line has words or a slider after it: a gap between the two. */
                     val gapAfterRoute: Boolean,
                     /** An icon that shows stands before the words or the slider: a gap between the two. */
                     val gapAfterIcon: Boolean,
                     /** Something stands before the words (a route line or an icon): the words keep to its side, and the spare room trails. */
                     val leadInBeforeText: Boolean)

    fun parts(state: ItemState, display: Display): Parts {
        val slider = state.slider?.takeIf { display != Display.ICON }
        val route = state.route?.takeIf { display.line }
        val icon = route == null && (display != Display.TEXT || state.text.isNullOrEmpty())
        val text = slider == null && display != Display.ICON && !state.text.isNullOrEmpty()
        // A day badge is an icon too, but it brings no gap of its own: as it was, only a picture or a symbol does.
        val iconShows = icon && (state.image != null || !state.icon.isNullOrEmpty())
        return Parts(slider, route, icon, text,
            iconDrawn = icon && (state.image != null || state.dayNumber != null || !state.icon.isNullOrEmpty()),
            textDrawn = text && !state.text.isNullOrBlank(),
            gapAfterRoute = route != null && (text || slider != null),
            gapAfterIcon = iconShows && (text || slider != null),
            leadInBeforeText = route != null || iconShows)
    }

    /** Whether a spacer draws its line. */
    fun line(state: ItemState) = state.divider

    /** Whether an item has the red capsule of an alert around it. */
    fun capsule(state: ItemState) = state.tone == Tone.ALERT

    /** Whether an item of [type], shown as [display], puts anything on the screen in this [state]. */
    fun draws(type: String, display: Display, state: ItemState): Boolean {
        if (type == com.meridian11.books.bentobar.items.SpacerItem.TYPE) return line(state)
        if (capsule(state)) return true // around whatever is in it
        val parts = parts(state, display)
        return parts.slider != null || parts.route != null || parts.iconDrawn || parts.textDrawn
    }

    /**
     * Whether the strip draws anything: an item that does, ‹, or the pill (which stays up as an empty capsule, as it always has).
     * The empty capsule says the bar is there, so it is one for the bar: [home] is the caller's word that this is the home
     * area's strip and no other strip draws. It has no default, so that no caller answers it without meaning to.
     */
    fun drawn(entries: List<StripEntry>, chevron: Boolean, pill: Pill, home: Boolean): Boolean =
        chevron || (home && pill != Pill.NONE) || entries.any { draws(it.item.type, it.item.display, it.state) }

    /**
     * The area whose strip may draw the empty capsule: the [home] area's, or, when the home area has no window ([owed]: it
     * has no room), the first strip's ([carrier], the first in room order that has one). The home area is looked for
     * Left, Middle, Right and room goes Left, Right, Middle, so a Middle at home can be owed while Right has a strip: the
     * capsule is there then, where the one-strip bar always had one.
     */
    fun capsuleArea(home: Position, carrier: Position, owed: Set<Position>): Position = if (home in owed) carrier else home

    /** The strip's window is made invisible: nothing is drawn. [width] is only for sizing; 0 means nothing was measured to show. */
    fun empty(width: Int, drawn: Boolean) = width <= 0 || !drawn

    /**
     * What Settings says about a bar that has a place. [refused]: Android gave an area with a place no window, so the bar
     * isn't all there, whatever the strips that came have to show.
     */
    fun status(refused: Boolean, empty: Boolean, noRoom: Boolean) = when {
        refused -> BarStatus.STOPPED
        empty -> BarStatus.EMPTY
        noRoom -> BarStatus.NO_ROOM
        else -> BarStatus.SHOWN
    }
}
