package com.meridian11.books.bentobar.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ImportTest {
    private val json = Json { encodeDefaults = true }
    private val clock = ItemConfig("c", "clock", Section.SHOWN)
    private val timer = ItemConfig("t", "timer", Section.SHOWN)

    @Test fun anExportComesBack() {
        val c = BarConfig(items = listOf(clock, timer), position = Position.LEFT)
        assertEquals(c, Store.parseLayout(json.encodeToString(BarConfig.serializer(), c)))
    }

    /** A value this version doesn't know inside a layout of its own version: a position, a section and a display a later build may add without a new version number. */
    private val laterValues = """{"version":$LAYOUT_VERSION,"position":"TOP","items":[
        {"id":"c","type":"clock","section":"SOMEWHERE","display":"FANCY","options":{"seconds":"true"}},
        {"id":"t","type":"timer"}],"spacing":7}"""

    @Test fun aValueThisVersionDoesntKnowFallsBackToItsDefault() {
        // Unreadable, the whole layout was replaced by the defaults, and the next edit made that for good.
        val c = Store.parseLayout(laterValues)!!
        assertEquals(Position.RIGHT, c.position)
        assertEquals(listOf("c", "t"), c.items.map { it.id })
        assertEquals(Section.SHOWN, c.items[0].section)
        assertEquals(Display.ICON_AND_TEXT, c.items[0].display)
        assertEquals("true", c.items[0].options["seconds"])
        assertEquals(7, c.spacing)
        assertNull(Store.parseLayout("not a layout"))
    }

    @Test fun aLayoutFromANewerVersionIsRefusedAndNotClamped() {
        val newer = """{"version":${LAYOUT_VERSION + 1},"items":[{"id":"c","type":"clock"}]}"""
        assertEquals(LayoutRead.Newer(LAYOUT_VERSION + 1), readLayout(newer))
        assertNull(Store.parseLayout(newer))
    }

    @Test fun aNewerVersionIsToldApartEvenWhenTheRestCantBeRead() {
        // Its fields may have changed shape: it is still a newer layout, not a damaged one.
        val shapeChanged = """{"version":${LAYOUT_VERSION + 2},"position":{"edge":"top"},"items":[]}"""
        assertEquals(LayoutRead.Newer(LAYOUT_VERSION + 2), readLayout(shapeChanged))
    }

    @Test fun aNewerVersionIsToldApartEvenWhenItsItemsChangedShape() {
        assertEquals(LayoutRead.Newer(LAYOUT_VERSION + 1), readLayout("""{"version":${LAYOUT_VERSION + 1},"items":{"shown":[]}}"""))
        assertEquals(LayoutRead.NotALayout, readLayout("""{"version":${LAYOUT_VERSION + 1},"theme":"dark"}"""))
        assertEquals(LayoutRead.NotALayout, readLayout("""{"version":$LAYOUT_VERSION,"items":{}}"""))
    }

    @Test fun somethingElseOnTheClipboardIsNotALayout() {
        // Each of these used to replace the bar with an empty one.
        assertNull(Store.parseLayout("{}"))
        assertNull(Store.parseLayout("""{"theme":"dark"}"""))
        assertNull(Store.parseLayout("""{"items":"none"}"""))
        assertNull(Store.parseLayout("hello"))
        assertNull(Store.parseLayout("[1,2,3]"))
    }

    @Test fun aRepeatedIdKeepsItsFirst() {
        val c = BarConfig(items = listOf(clock, timer, clock.copy(section = Section.HIDDEN)))
        assertEquals(listOf(clock, timer), Store.parseLayout(json.encodeToString(BarConfig.serializer(), c))!!.items)
    }

    @Test fun aPastedLayoutKeepsThisInstallsSwitches() {
        // Calendar was switched off here; the pasted layout comes from an install where it was on.
        val here = BarConfig(items = listOf(clock), turnedOff = setOf(Uses.CALENDAR), enabled = false, onboarded = true)
        val pasted = BarConfig(items = listOf(timer), position = Position.LEFT, presenting = true)
        val c = pasted.keepingLocal(here)
        assertEquals(listOf(timer), c.items)
        assertEquals(Position.LEFT, c.position)
        assertEquals(setOf(Uses.CALENDAR), c.turnedOff)
        assertEquals(false, c.enabled)
        assertEquals(false, c.presenting)
        assertEquals(true, c.onboarded)
    }

    @Test fun worldClockCitiesTravelWithTheLayout() {
        val c = BarConfig(items = listOf(clock), cities = listOf(WorldCity("Asia/Tokyo"), WorldCity("Europe/Berlin", "Munich")))
        assertEquals(c, Store.parseLayout(json.encodeToString(BarConfig.serializer(), c)))
        // A layout from before there were cities has none.
        assertEquals(emptyList<WorldCity>(), Store.parseLayout("""{"version":3,"items":[]}""")!!.cities)
    }

    @Test fun aLayoutHasNoPlaceForAKeyOrAnOnlineSwitch() {
        // What Copy settings puts on the clipboard, and what a backup carries: these fields and no others. A key and
        // the three online switches are kept elsewhere (Online); a new field here should be a decision, so it is listed.
        val fields = (0 until BarConfig.serializer().descriptor.elementsCount).map { BarConfig.serializer().descriptor.getElementName(it) }.toSet()
        assertEquals(setOf("version", "enabled", "items", "position", "hiddenMode", "chevron", "revealOnHover", "autoCollapseSec", "pinnedOpen",
            "presenting", "turnedOff", "textSize", "pill", "color", "spacing", "hoverSwitchesPopups", "noAnimations", "systemTint", "chipMode", "onboarded", "cities"), fields)
        val item = (0 until ItemConfig.serializer().descriptor.elementsCount).map { ItemConfig.serializer().descriptor.getElementName(it) }.toSet()
        assertEquals(setOf("id", "type", "section", "whenActive", "display", "options"), item)
    }

    @Test fun everyFieldOfALayoutIsEitherLocalOrCopied() {
        // One list says what belongs to one install (LOCAL_SETTINGS). A new field is placed on one side here, or this fails:
        // forgotten, it would ride Copy settings to another install.
        val fields = (0 until BarConfig.serializer().descriptor.elementsCount).map { BarConfig.serializer().descriptor.getElementName(it) }.toSet()
        assertEquals(setOf("enabled", "presenting", "pinnedOpen", "turnedOff", "onboarded"), LOCAL_SETTINGS)
        val copied = setOf("version", "items", "position", "hiddenMode", "chevron", "revealOnHover", "autoCollapseSec", "textSize", "pill", "color",
            "spacing", "hoverSwitchesPopups", "noAnimations", "systemTint", "chipMode", "cities")
        assertEquals(fields, LOCAL_SETTINGS + copied)
        assertEquals(emptySet<String>(), LOCAL_SETTINGS intersect copied)
    }

    @Test fun whatCopySettingsHandsOnHasNoInstallLocalSetting() {
        val here = BarConfig(items = listOf(clock), position = Position.LEFT, enabled = false, presenting = true, pinnedOpen = true,
            turnedOff = setOf(Uses.CALENDAR), onboarded = true)
        val handed = here.withoutLocal()
        assertEquals(BarConfig(items = listOf(clock), position = Position.LEFT), handed)
        // And pasted somewhere else, the other install keeps its own.
        val there = BarConfig(items = listOf(timer), enabled = true, turnedOff = setOf(Uses.NOTIFICATIONS), onboarded = true)
        assertEquals(here.copy(enabled = true, presenting = false, pinnedOpen = false, turnedOff = setOf(Uses.NOTIFICATIONS), onboarded = true),
            handed.keepingLocal(there))
    }

    @Test fun anOlderLayoutIsMigrated() {
        val old = """{"version":2,"chevron":false,"items":[{"id":"m","type":"memory","section":"HIDDEN"}]}"""
        val c = Store.parseLayout(old)
        assertNotNull(c)
        assertEquals(3, c!!.version)
        assertEquals(Section.OFF, c.items.single().section)
    }
}
