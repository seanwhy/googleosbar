package com.meridian11.books.bentobar.data

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
 * - the cities of 0.9 to 1.2, and 1.6 to 1.7.1's look and popup switches (1.3 added them) with the types 1.6 added, none of which changed the version.
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
    )

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
        val before3 = setOf("schema1-0.1-to-0.2.json", "schema2-0.3-to-0.5.json")
        for (name in fixtures.keys)
            assertEquals(name, name in before3, (readLayout(file(name).readText()) as LayoutRead.Ok).changed)
    }

    @Test fun schema1TimedHidingBecomesOptInAndHoverRevealIsKept() {
        val c = read("schema1-0.1-to-0.2.json")
        assertEquals(0, c.autoCollapseSec) // 8 s was only the old default
        assertEquals(HiddenMode.HOVER, c.hiddenMode) // it had reveal on hover on, the old default
        assertEquals(10, c.spacing)
        assertEquals(listOf("memory", "clock", "battery", "timer", "tools"), c.items.map { it.type })
        assertEquals(Section.HIDDEN, c.items[2].section)
        assertEquals("true", c.items[1].options["seconds"])
    }

    @Test fun schema2WithTheChevronOffMovesARuleLessHiddenItemToOffAndKeepsItsLook() {
        val c = read("schema2-0.3-to-0.5.json")
        assertEquals(HiddenMode.SHOW_ALL, c.hiddenMode)
        assertEquals(Section.OFF, c.items.single { it.type == "memory" }.section) // without ‹ it never showed
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

    @Test fun schema3WithTheLookSwitchesKeepsThemAndAnOldOptionStaysIgnoredInTheLayout() {
        val c = read("schema3-look-1.6-to-1.7.1.json")
        assertTrue(c.hoverSwitchesPopups); assertTrue(c.noAnimations); assertTrue(c.systemTint)
        assertFalse(c.enabled)
        assertEquals("true", c.items.single { it.type == "clipboard" }.options["pasteOnClick"]) // read by nothing, harmless
        assertEquals(6, c.items.size)
    }
}
