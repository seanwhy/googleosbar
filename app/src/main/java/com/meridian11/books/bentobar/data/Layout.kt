package com.meridian11.books.bentobar.data

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
 * The one reader of a layout, for what is stored and what is pasted: decode, migrate, drop a repeated item id. Pure, and
 * every shipped schema has a fixture in `src/test/resources/layouts` that goes through it (`LayoutFixturesTest`), so a
 * version 4 has one place to go and one place to be tested.
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
    val read = decoded.migrateToV2().migrateToV3().let { it.copy(items = it.items.distinctBy { item -> item.id }) }
    return LayoutRead.Ok(read, changed = read != decoded)
}

/** v1 → v2: timed hiding of revealed items became opt-in; 8 s was only the old default. */
private fun BarConfig.migrateToV2(): BarConfig =
    if (version >= 2) this else copy(version = 2, autoCollapseSec = if (autoCollapseSec == 8) 0 else autoCollapseSec)
