package com.meridian11.books.bentobar.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Every layout schema a shipped version ever wrote, read by the one reader. The files in `src/test/resources/layouts` are
 * those layouts as such a version's Copy settings or its stored JSON had them (every field, in its order, from the
 * `BarConfig` of those tags and the migrations in `data/`):
 *
 * - schema 1: BarBook 0.1 and DiscoBar builds up to 0.2 (`version` 1, timed hiding at 8 s, hover reveal on);
 * - schema 2: DiscoBar 0.3, 0.4, BentoBar 0.5 (`version` 2);
 * - schema 3: BentoBar 0.6 to 0.8, with Show everything, presenting and the Setup switches;
 * - the cities of 0.9 to 1.2, and 1.6 to 1.7.1's look and popup switches (1.3 added them) with the types 1.6 added, none of which changed the version;
 * - schema 4: an area for each item (Left, Middle or Right) where the whole strip had one Position.
 *
 * They are write-once: a fixture is what a version that shipped wrote. A new schema gets a new file and an entry here.
 */
class LayoutFixturesTest {
    private fun file(name: String) = File("src/test/resources/layouts/$name")
    private fun read(name: String): BarConfig = (readLayout(file(name).readText()) as LayoutRead.Ok).config

    private val fixtures = mapOf(
        "schema1-0.1-to-0.2.json" to "90c4c2b8a13aed62d951d7146320d8db46da130c5d361c29c39a3d2b4c307c0f",
        "schema2-0.3-to-0.5.json" to "335b8135beb2242ba3cd69f33021517bbcda8e6fb664f53e68efae7e8756411e",
        "schema3-0.6-to-0.8.json" to "c612944556201d62425cbe2dd0c7a8b97ca19f88d407a927065d5c4bb983c519",
        "schema3-cities-0.9-to-1.2.json" to "51d74d2c27b4b29b4208342cb70692b6d422f3d448345fcf91cb01902e853b96",
        "schema3-look-1.6-to-1.7.1.json" to "8e018c1390a557cfcb9d8dc244308282c09b4d2da225c4dcb58e1ef6b61eaa22",
        "schema4-areas-1.8.json" to "da4e091860945db5f175dcb8cd302ae266e2a7b4076073d2e0ce48d028a5c5fa",
    )

    /** The fixtures of this version's own schema: not migrated, as every other one is. */
    private val current = setOf("schema4-areas-1.8.json")
    private fun decoded(name: String): BarConfig = layoutJson.decodeFromString(BarConfig.serializer(), file(name).readText())

    @Test fun everyFixtureIsWhatAShippedVersionWroteAndIsNeverEdited() {
        assertEquals(fixtures.keys, File("src/test/resources/layouts").list()!!.toSet())
        for ((name, sha) in fixtures) {
            val digest = MessageDigest.getInstance("SHA-256").digest(file(name).readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals("$name is write-once: a new schema gets a new file", sha, digest)
        }
    }

    @Test fun everyFixtureLoadsToTheCurrentVersionAndIsStableAfterwards() {
        for (name in fixtures.keys) {
            val c = read(name)
            assertEquals(name, LAYOUT_VERSION, c.version)
            assertTrue(name, c.items.isNotEmpty())
            val again = readLayout(layoutJson.encodeToString(BarConfig.serializer(), c))
            assertEquals(name, LayoutRead.Ok(c, changed = false), again)
        }
    }

    @Test fun anOlderFixtureIsMigratedAndSavedAgain() {
        for (name in fixtures.keys - current)
            assertTrue(name, (readLayout(file(name).readText()) as LayoutRead.Ok).changed)
    }

    @Test fun aFixtureOfThisSchemaIsReadAsItIsButForItsOffItemAndSavedAgainWithoutIt() {
        // 1.8's layout holds an item in Off (Memory), which no layout has any more: that is all the reader changes.
        for (name in current) {
            val stored = decoded(name)
            val read = readLayout(file(name).readText()) as LayoutRead.Ok
            assertTrue(name, stored.items.any { it.section == Section.OFF })
            assertEquals(name, stored.copy(items = stored.items.filter { it.section != Section.OFF }), read.config)
            assertTrue(name, read.changed)
        }
    }

    @Test fun anOlderFixtureOpensWithEveryItemInTheAreaOfItsPosition() {
        // Before version 4 the whole strip had one place: each item takes it as its area, so the bar looks as it did.
        val older = fixtures.keys - current
        for (name in older) {
            val c = read(name)
            assertEquals(name, c.items.map { c.position }, c.items.map { it.area })
        }
        assertEquals(Position.entries.toSet(), older.map { read(it).position }.toSet()) // each of the three is among them
    }

    @Test fun schema1TimedHidingBecomesOptInAndHoverRevealIsKept() {
        val c = read("schema1-0.1-to-0.2.json")
        assertEquals(0, c.autoCollapseSec) // 8 s was only the old default
        assertEquals(HiddenMode.HOVER, c.hiddenMode) // it had reveal on hover on, the old default
        assertEquals(10, c.spacing)
        // Its Tools item was in Off: no layout is read with an Off item.
        assertEquals(listOf("memory", "clock", "battery", "timer"), c.items.map { it.type })
        assertEquals(Section.HIDDEN, c.items[2].section)
        assertEquals("true", c.items[1].options["seconds"])
    }

    @Test fun schema2WithTheChevronOffDropsARuleLessHiddenItemAndKeepsItsLook() {
        val c = read("schema2-0.3-to-0.5.json")
        assertEquals(HiddenMode.SHOW_ALL, c.hiddenMode)
        // Without ‹ it never showed: the migration puts it in Off, and a layout is read without its Off items.
        assertEquals(listOf("network", "caffeine", "spacer"), c.items.map { it.type })
        assertEquals(Section.HIDDEN, c.items.single { it.type == "caffeine" }.section) // a rule item still comes out
        assertEquals(Position.CENTER, c.position)
        assertEquals(Pill.SUBTLE, c.pill)
        assertEquals(ChipMode.ALWAYS, c.chipMode)
    }

    @Test fun schema3LoadsAsItWas() {
        val c = read("schema3-0.6-to-0.8.json")
        assertEquals(HiddenMode.CLICK, c.hiddenMode)
        assertEquals(setOf("calendar"), c.turnedOff)
        assertTrue(c.pinnedOpen)
        assertEquals(Position.LEFT, c.position)
        assertEquals(5, c.autoCollapseSec)
        assertEquals(emptyList<WorldCity>(), c.cities)
    }

    @Test fun schema3WithCitiesKeepsThem() {
        val c = read("schema3-cities-0.9-to-1.2.json")
        assertEquals(listOf(WorldCity("Asia/Tokyo"), WorldCity("Europe/Berlin", "Munich")), c.cities)
        assertEquals("Tokyo", c.items.first().options["city"])
        assertTrue(c.presenting)
    }

    @Test fun schema3WithTheLookSwitchesKeepsThemAndLeavesTheRetiredClipboardItemOut() {
        val c = read("schema3-look-1.6-to-1.7.1.json")
        assertTrue(c.hoverSwitchesPopups); assertTrue(c.noAnimations); assertTrue(c.systemTint)
        assertFalse(c.enabled)
        // The Clipboard item (1.6 to 2.0) is retired: it goes with its options. So does the Shortcut item, which was in
        // Off. The four others stay in their order.
        assertEquals(listOf("fx", "air", "stocks", "displays"), c.items.map { it.type })
    }

    @Test fun schema4KeepsEachItemsAreaAndComesBackAsItWasWritten() {
        val text = file("schema4-areas-1.8.json").readText()
        // Decoded and written again it is the file, to the letter: nothing of the schema is lost on the way.
        assertEquals(text.trim(), layoutJson.encodeToString(BarConfig.serializer(), decoded("schema4-areas-1.8.json")))
        val c = read("schema4-areas-1.8.json")
        assertEquals(listOf(Position.LEFT, Position.LEFT, Position.CENTER, Position.RIGHT, Position.RIGHT), c.items.map { it.area })
        // The old Position stays in the layout as it was, and says nothing about a version 4 layout's items.
        assertEquals(Position.CENTER, c.position)
        assertFalse("its Off item (Memory) is not read", c.items.any { it.type == "memory" })
        // An item of a type this version doesn't know keeps its area with everything else it has.
        val unknown = c.items.single { com.meridian11.books.bentobar.items.Items.of(it.type) == null }
        assertEquals(Position.RIGHT, unknown.area)
        assertEquals("7", unknown.options["depth"])
    }

    @After fun forget() { Store.open(MapPrefs()) { }; Store.update { BarConfig() } }

    @Test fun aLayoutFrom21OpensWithoutItsOffItemsSetUpOrNotAndIsSavedAtOnce() {
        // Up to 2.1 a removed item that had been set up waited in Off, as a line of the library. The fixture has one item
        // there (Memory, as it came); beside it what else a version could leave: setups (an option, a look, a rule),
        // items as they came, and an item of a type this version doesn't know.
        val was = decoded("schema4-areas-1.8.json")
        val off = listOf(
            ItemConfig("bare", "clock", Section.OFF),
            ItemConfig("bare-left", "cpu", Section.OFF, area = Position.LEFT),
            ItemConfig("quiet", "media", Section.OFF, whenActive = true),
            ItemConfig("city", "clock", Section.OFF, options = mapOf("zone" to "Pacific/Auckland")),
            ItemConfig("look", "timer", Section.OFF, display = Display.ICON),
            ItemConfig("rule", "cpu", Section.OFF, whenActive = true),
            ItemConfig("newer", "later-version-item", Section.OFF, options = mapOf("depth" to "7")),
        )
        val stored = was.copy(items = was.items + off)
        val prefs = MapPrefs(mapOf("bar" to layoutJson.encodeToString(BarConfig.serializer(), stored)))
        Store.open(prefs) { }
        // What is in the bar is untouched, in its order, and nothing else is left.
        val inBar = was.items.filter { it.section != Section.OFF }
        assertEquals(5, inBar.size)
        assertEquals(stored.copy(items = inBar), Store.config.value)
        // The stored layout was written again without them: nothing of a setup is left on the device.
        val saved = prefs.string("bar")!!
        for (gone in listOf("OFF", "Pacific/Auckland", "bare", "quiet", "memory")) assertFalse(gone, gone in saved)
        assertEquals(LayoutRead.Ok(Store.config.value, changed = false), readLayout(saved))
        assertEquals(null, Store.setAside.value)
    }

    @Test fun noChangeLeavesAnItemInOff() {
        Store.open(MapPrefs()) { }
        Store.update { BarConfig(items = listOf(ItemConfig("a", "clock"), ItemConfig("b", "cpu", Section.HIDDEN))) }
        // Whatever a change writes (the debug hook's `set ID section=off`, `add TYPE off`): the item is gone, not kept.
        Store.update { it.putIn("b", Section.OFF) }
        assertEquals(listOf("a"), Store.config.value.items.map { it.id })
        Store.update { it.copy(items = it.items + ItemConfig("c", "timer", Section.OFF, options = mapOf("label" to "Tea"))) }
        assertEquals(listOf("a"), Store.config.value.items.map { it.id })
    }
}
