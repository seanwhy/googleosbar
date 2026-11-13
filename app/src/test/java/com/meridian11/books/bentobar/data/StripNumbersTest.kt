package com.meridian11.books.bentobar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every number from a layout that the strip measures is within the settings' own limits. A layout can be pasted
 * in and can say anything, and Compose throws during measure for a width over 262,142 px: on the main thread, with the
 * layout already saved, so the service would crash at every start.
 */
class StripNumbersTest {
    private fun spacer(width: String?) = ItemConfig("s", "spacer", options = if (width == null) emptyMap() else mapOf("width" to width))

    @Test fun aSpacerIsAsWideAsTheSliderAllows() {
        assertEquals(12, spacer(null).spacerWidthDp)
        assertEquals(33, spacer("33").spacerWidthDp)
        assertEquals(SPACER_DP.last, spacer("200000").spacerWidthDp)
        assertEquals(SPACER_DP.first, spacer("-9").spacerWidthDp)
        assertEquals(12, spacer("wide").spacerWidthDp)
    }

    @Test fun theSpacingIsAsWideAsTheSliderAllows() {
        assertEquals(12, BarConfig().spacingDp)
        assertEquals(SPACING_DP.last, BarConfig(spacing = 200_000).spacingDp)
        assertEquals(SPACING_DP.first, BarConfig(spacing = -3).spacingDp)
    }

    @Test fun aCraftedLayoutMeasuresWithinWhatComposeCanHold() {
        val pasted = Store.parseLayout("""{"version":3,"spacing":200000,"items":[{"id":"s","type":"spacer","options":{"width":"200000"}}]}""")!!
        // At the densest screen there is: 4x.
        assertTrue(pasted.items.single().spacerWidthDp * 4 <= 262_142)
        assertTrue(pasted.spacingDp * 4 <= 262_142)
    }

    @Test fun theStripReadsOnlyTheBoundedNumbers() {
        // state() and every StripLook read the bounded value; the sliders are given the same limits.
        val sources = java.io.File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val text = sources.associateWith { it.readText() }
        assertTrue("SpacerItem.state", text.entries.single { it.key.name == "ToolItems.kt" }.value.contains("gapDp = item.spacerWidthDp"))
        for ((f, t) in text) assertTrue("${f.name} measures the raw spacing", "cfg.spacing.dp" !in t)
    }
}
