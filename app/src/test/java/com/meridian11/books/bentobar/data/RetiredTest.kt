package com.meridian11.books.bentobar.data

import com.meridian11.books.bentobar.items.Items
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A layout that holds an item of a retired type is read without it: no row in Settings, nothing in the bar, no error. */
class RetiredTest {
    private fun read(text: String): LayoutRead.Ok = readLayout(text) as LayoutRead.Ok

    @After fun gone() = Store.open(MapPrefs()) { }

    @Test fun noRetiredTypeIsRegistered() {
        for (type in Retired.types) assertNull(type, Items.of(type))
    }

    @Test fun aLayoutWithAClipboardItemAndItsSnippetsLoadsWithoutItAndIsSavedAgain() {
        // As 2.0 wrote it: a Clipboard item with pinned snippets, one off with snippets too, and two other items.
        val pins = """["me@example.com","git status -sb"]"""
        val text = layoutJson.encodeToString(BarConfig.serializer(), BarConfig(items = listOf(
            ItemConfig("a1", "clock"),
            ItemConfig("c1", "clipboard", options = mapOf("pins" to pins, "bar" to "count", "pasteOnClick" to "true")),
            ItemConfig("b2", "cpu", Section.HIDDEN, whenActive = true),
            ItemConfig("c2", "clipboard", Section.OFF, options = mapOf("pins" to pins)),
        )))
        val read = read(text)
        assertEquals(listOf("a1", "b2"), read.config.items.map { it.id })
        assertTrue("so the stored layout is written again without it", read.changed)
        val saved = layoutJson.encodeToString(BarConfig.serializer(), read.config)
        for (gone in listOf("clipboard", "pins", "me@example.com", "pasteOnClick")) assertFalse(gone, gone in saved)
        assertEquals(LayoutRead.Ok(read.config, changed = false), readLayout(saved))
    }

    @Test fun aStoredLayoutWithAClipboardItemIsWrittenAgainWithoutIt() {
        val prefs = MapPrefs(mapOf("bar" to """{"version":4,"items":[{"id":"c1","type":"clipboard","options":{"pins":"[\"a snippet\"]"}},{"id":"a1","type":"clock"}]}"""))
        Store.open(prefs) { }
        assertEquals(listOf("a1"), Store.config.value.items.map { it.id })
        val stored = prefs.string("bar").orEmpty()
        assertFalse(stored, "clipboard" in stored || "a snippet" in stored)
        // Nothing was set aside for Backup: the layout was read, only without the retired item.
        assertNull(Store.setAside.value)
    }

    @Test fun aLayoutWithACryptoItemLoadsWithoutItAndIsSavedAgain() {
        // As a 2.0 test build wrote it: a Crypto item between two others, with its coins and currency.
        val text = """{"version":4,"items":[
            {"id":"a1","type":"clock","area":"RIGHT"},
            {"id":"k1","type":"crypto","section":"SHOWN","area":"LEFT","options":{"coins":"BTC ETH","quote":"EUR"}},
            {"id":"b2","type":"cpu","section":"HIDDEN","area":"RIGHT"}]}"""
        val read = read(text)
        assertEquals(listOf("a1", "b2"), read.config.items.map { it.id })
        assertTrue("so the stored layout is written again without it", read.changed)
        val again = readLayout(layoutJson.encodeToString(BarConfig.serializer(), read.config))
        assertEquals(LayoutRead.Ok(read.config, changed = false), again)
    }

    @Test fun aLayoutWithAShelfAndAValueFromAUrlLoadsWithoutThemAndIsSavedAgain() {
        // As 2.1 wrote it: a Shelf and a Value from a URL with its options among others, in two areas.
        val text = """{"version":4,"items":[
            {"id":"a1","type":"clock","area":"RIGHT"},
            {"id":"s1","type":"shelf","section":"SHOWN","area":"LEFT","display":"ICON"},
            {"id":"w1","type":"webvalue","section":"HIDDEN","area":"RIGHT","options":{"label":"Build","unit":"%","every":"5","path":"status.value"}},
            {"id":"b2","type":"cpu","section":"HIDDEN","area":"RIGHT"}]}"""
        val read = read(text)
        assertEquals(listOf("a1", "b2"), read.config.items.map { it.id })
        assertTrue("so the stored layout is written again without them", read.changed)
        val saved = layoutJson.encodeToString(BarConfig.serializer(), read.config)
        for (gone in listOf("shelf", "webvalue", "status.value", "Build")) assertFalse(gone, gone in saved)
        assertEquals(LayoutRead.Ok(read.config, changed = false), readLayout(saved))
    }

    @Test fun theRetiredServicesAndFoldersAreTheOnesTheRetiredTypesHad() {
        assertEquals(setOf("clipboard", "crypto", "shelf", "webvalue"), Retired.types)
        assertEquals(setOf("kraken", "own"), Retired.services)
        assertEquals(setOf("shelf", "webvalue"), Retired.kept)
        // No service of this version has a retired one's id, and no type keeps its own under a retired folder's name.
        for (service in Online.Service.entries) assertFalse(service.id, service.id in Retired.services)
        for (type in Items.all) assertFalse(type.type, type.type in Retired.kept)
    }

    @Test fun aLayoutWithNothingRetiredIsTheSameLayout() {
        val c = BarConfig(items = listOf(ItemConfig("a1", "clock"), ItemConfig("u1", "fromANewerVersion", options = mapOf("depth" to "7"))))
        assertTrue(c.withoutRetired() === c)
        // An unknown type that is not retired may be from a newer version: it stays, options and all.
        assertEquals(c.items, read(layoutJson.encodeToString(BarConfig.serializer(), c)).config.items)
    }

    @Test fun aLayoutOfOnlyRetiredItemsIsAnEmptyLayoutNotNoLayout() {
        val read = read("""{"version":4,"items":[{"id":"k1","type":"crypto"}]}""")
        assertEquals(emptyList<ItemConfig>(), read.config.items)
        assertFalse(read.config.items.any { it.type in Retired.types })
    }

    /** 2.1 stood an emptied bar in its first Off item's area and never wrote `position` for it: the read does now. */
    @Test fun aBarWhoseOnlyItemsWaitedInOffStaysWhereItStoodWhenTheReadDropsThem() {
        val read = read("""{"version":4,"position":"RIGHT","items":[{"id":"w","type":"clock","section":"OFF","area":"LEFT"}]}""")
        assertEquals(emptyList<ItemConfig>(), read.config.items)
        assertEquals("the empty bar and the next item are where the bar was", Position.LEFT, read.config.homeArea())
        assertTrue(read.changed)
        assertEquals("and it stays there when read again", LayoutRead.Ok(read.config, changed = false),
            readLayout(layoutJson.encodeToString(BarConfig.serializer(), read.config)))
    }

    @Test fun aBarWhoseOnlyItemWasOfARetiredTypeStaysWhereItStoodWhenTheReadDropsIt() {
        val read = read("""{"version":4,"position":"RIGHT","items":[{"id":"s","type":"shelf","area":"LEFT"}]}""")
        assertEquals(emptyList<ItemConfig>(), read.config.items)
        assertEquals(Position.LEFT, read.config.homeArea())
    }

    /** As 2.1 said where the bar is: an item in the bar before one in Off, Right before Middle before Left. */
    @Test fun anEmptiedBarStandsWhereTheVersionBeforeHadItNotWhereItsFirstItemWas() {
        val shelfAfterOff = read("""{"version":4,"position":"LEFT","items":[
            {"id":"w","type":"clock","section":"OFF","area":"LEFT"},{"id":"s","type":"shelf","area":"CENTER"}]}""")
        assertEquals("the Shelf was in the bar, the Clock was not", Position.CENTER, shelfAfterOff.config.homeArea())
        val two = read("""{"version":4,"position":"CENTER","items":[
            {"id":"s","type":"shelf","area":"LEFT"},{"id":"v","type":"webvalue","area":"RIGHT"}]}""")
        assertEquals("2.1's home was the first in use of Right, Middle, Left", Position.RIGHT, two.config.homeArea())
        // A layout from before areas: its items were where the strip was, and so is the emptied bar.
        val old = read("""{"version":3,"position":"CENTER","items":[{"id":"w","type":"clock","section":"OFF"}]}""")
        assertEquals(Position.CENTER, old.config.homeArea())
    }

    @Test fun aLayoutThatKeepsAnItemOrHadNoneKeepsItsPositionAsWritten() {
        val keeps = read("""{"version":4,"position":"RIGHT","items":[
            {"id":"w","type":"clock","section":"OFF","area":"LEFT"},{"id":"c","type":"clock","area":"CENTER"}]}""")
        assertEquals(Position.RIGHT, keeps.config.position)
        val empty = read("""{"version":4,"position":"CENTER","items":[]}""")
        assertEquals(Position.CENTER, empty.config.position)
        assertFalse(empty.changed)
    }
}
