package com.meridian11.books.bentobar.data

// What the user can ask of an item's section, as steps from one layout to the next: the one rule for each command, whichever
// surface asks (the rows, the ≡ menu, the accessibility actions and the chooser in settings, the bar's right-click menu). A
// command changes an item's section, never its place in the list: in Show everything, the default mode, Hidden items are
// drawn, so a Hide that hides nothing must not make the item jump along the strip. Only a Move, a drag or a drop changes the
// place ([moved], with an index). A screen calls `Store.update { it.hide(id) }` and writes no section or Show when rule of its
// own, so one command can't place an item one way here and another way there (`LayoutIntentsTest` holds the screens to it).
// Pure; an id that isn't in the layout changes nothing.

/**
 * Into [section], where it stands in the list; in a settings card that is its list order, not last. Already in [section]:
 * nothing changes, so the settings' chooser can ask for whichever chip was clicked.
 */
fun BarConfig.putIn(id: String, section: Section): BarConfig =
    if (items.none { it.id == id && it.section != section }) this else copy(items = items.map { if (it.id == id) it.copy(section = section) else it })

fun BarConfig.show(id: String): BarConfig = putIn(id, Section.SHOWN)

fun BarConfig.hide(id: String): BarConfig = putIn(id, Section.HIDDEN)

fun BarConfig.turnOff(id: String): BarConfig = putIn(id, Section.OFF)

/**
 * The "Show when…" rule on or off. Only hidden items pop into the bar, so turning it on brings a shown or turned-off item
 * into Hidden, and like every command it keeps the item's place in the list: it pops out where it stood in the bar. Turning
 * it off changes no section.
 */
fun BarConfig.setWhenActive(id: String, on: Boolean): BarConfig = copy(items = items.map {
    if (it.id == id) it.copy(whenActive = on, section = if (on) Section.HIDDEN else it.section) else it
})
