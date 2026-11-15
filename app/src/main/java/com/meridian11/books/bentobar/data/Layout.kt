package com.meridian11.books.bentobar.data

import com.meridian11.books.bentobar.items.Items
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** What came of reading a layout, stored or pasted. */
sealed interface LayoutRead {
    /** A layout, brought up to this version. [changed]: reading it changed it, so a stored one is saved again. */
    data class Ok(val config: BarConfig, val changed: Boolean) : LayoutRead

    /** It says it was written by a newer version than this one. Refused, never clamped: this build can't know what it holds. */
    data class Newer(val version: Int) : LayoutRead

    /** Not a layout at all: any other JSON, or something that can't be read. */
    data object NotALayout : LayoutRead
}

/**
 * The one reader of a layout, for what is stored and what is pasted: decode, migrate, drop a repeated item id, the items
 * of a retired type ([withoutRetired]) and the Off items ([withoutOff]); a bar that empties stays where it stood
 * ([standingWhere]). Pure, and
 * every shipped schema has a fixture in `src/test/resources/layouts` that goes through it (`LayoutFixturesTest`), so a
 * new version has one place to go and one place to be tested.
 *
 * A layout is a JSON object with an items list: `{}` or another app's settings is not one, and would otherwise replace
 * the bar with an empty one. Every version must keep a top-level `items` key (that is how a layout is told from something
 * else); after that its version is read before anything else, so a newer layout is told from a damaged one even when
 * its items are shaped differently. An unknown value (a later version's enum constant) still falls back to
 * the field's default inside a layout of a known version, as `coerceInputValues` makes it.
 */
fun readLayout(raw: String): LayoutRead {
    val root = runCatching { layoutJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return LayoutRead.NotALayout
    if (root["items"] == null) return LayoutRead.NotALayout
    val version = (root["version"] as? JsonPrimitive)?.intOrNull
    if (version != null && version > LAYOUT_VERSION) return LayoutRead.Newer(version)
    if (root["items"] !is JsonArray) return LayoutRead.NotALayout
    val decoded = runCatching { layoutJson.decodeFromJsonElement(BarConfig.serializer(), root) }.getOrNull() ?: return LayoutRead.NotALayout
    val migrated = decoded.migrateToV2().migrateToV3().migrateToV4()
    val read = migrated.withoutRetired().withoutOff().let { it.copy(items = it.items.distinctBy { item -> item.id }) }.standingWhere(migrated)
    return LayoutRead.Ok(read, changed = read != decoded)
}

/**
 * A layout the read left no item of stands where the version that wrote it had the bar. Up to 2.1 a layout said that
 * with its items (an Off item too, and a Shelf), and wrote [BarConfig.position] only through Look, so once the read has
 * dropped them all ([withoutRetired], [withoutOff]) `position` may name a place the bar never was: the empty capsule and
 * the next item would be there. [was] is the layout before they were dropped. 2.1's rule, kept here because nothing
 * else needs it any more: the first in use of Right, Middle, Left, among its items outside Off of a type it knew, and
 * with none of those the first item's area.
 */
private fun BarConfig.standingWhere(was: BarConfig): BarConfig {
    if (items.isNotEmpty() || was.items.isEmpty()) return this
    val used = was.items.filter { it.section != Section.OFF && (Items.of(it.type) != null || it.type in Retired.types) }.mapTo(HashSet()) { it.area }
    val home = listOf(Position.RIGHT, Position.CENTER, Position.LEFT).firstOrNull { it in used } ?: was.items.first().area
    return if (home == position) this else copy(position = home)
}

/** v1 → v2: timed hiding of revealed items became opt-in; 8 s was only the old default. */
private fun BarConfig.migrateToV2(): BarConfig =
    if (version >= 2) this else copy(version = 2, autoCollapseSec = if (autoCollapseSec == 8) 0 else autoCollapseSec)
