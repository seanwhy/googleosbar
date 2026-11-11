package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardRulesTest {
    @Test fun pinsAreCleanedDedupedAndMoved() {
        var item = ItemConfig("c1", "clipboard")
        item = ClipboardRules.addPin(item, "  First line  ")
        item = ClipboardRules.addPin(item, "Second line")
        item = ClipboardRules.addPin(item, "First line")
        item = ClipboardRules.addPin(item, "   ")
        item = ClipboardRules.addPin(item, "Third line")
        assertEquals(listOf("First line", "Second line", "Third line"), ClipboardRules.pins(item))

        item = ClipboardRules.movePin(item, "Third line", -1)
        assertEquals(listOf("First line", "Third line", "Second line"), ClipboardRules.pins(item))

        item = ClipboardRules.removePin(item, "Third line")
        assertEquals(listOf("First line", "Second line"), ClipboardRules.pins(item))
    }

    @Test fun previewCollapsesWhitespaceAndTruncates() {
        assertEquals("hello world", ClipboardRules.preview("  hello   \n\t  world  ", 40))
        assertEquals("12345…", ClipboardRules.preview("123456789", 6))
        assertNull(ClipboardRules.clean("   \n  "))
    }

    @Test fun aClipItsAppMarkedSensitiveIsNeverKept() {
        // A password manager's copy: not in the menu, and not its first 20 characters in the status bar.
        assertNull(ClipboardRules.recentText("hunter2-correct-horse", sensitive = true))
        assertEquals("hunter2-correct-horse", ClipboardRules.recentText("hunter2-correct-horse", sensitive = false))
    }

    @Test fun aClipWithoutTextIsNotKept() {
        // A copied file or link: its text is null, and nothing opens it to find out what it holds.
        assertNull(ClipboardRules.recentText(null, sensitive = false))
    }

    @Test fun aSettingsExportIsNotASnippet() {
        assertNull(ClipboardRules.recentText("""{"version":3,"items":[]}""", sensitive = false))
        assertNull(ClipboardRules.recentText("""  {"version":3,"items":[]}""", sensitive = false))
    }

    @Test fun aLongClipIsKeptWholeAndOnlyItsPreviewIsCut() {
        val long = "x".repeat(2_000)
        val recent = ClipboardRules.recordRecent(emptyList(), long)
        assertEquals(listOf(long), recent)
        assertEquals(ClipboardRules.MENU_CHARS, ClipboardRules.preview(recent.single(), ClipboardRules.MENU_CHARS).length)
    }

    @Test fun aRecentClipComesBackAsItWasCopied() {
        val spaced = "  indented first line\nsecond  \n"
        assertEquals(listOf(spaced), ClipboardRules.recordRecent(emptyList(), spaced))
    }

    @Test fun aClipOverTheMemoryLimitIsNotKeptRatherThanKeptCut() {
        val tooLong = "y".repeat(ClipboardRules.RECENT_CHARS + 1)
        assertEquals(listOf("earlier"), ClipboardRules.recordRecent(listOf("earlier"), tooLong))
        val fits = "y".repeat(ClipboardRules.RECENT_CHARS)
        assertEquals(listOf(fits), ClipboardRules.recordRecent(emptyList(), fits))
    }

    @Test fun onlyWhatFitsWholeCanBePinned() {
        // A pin is cut to MAX_CHARS in the layout, so a longer recent copy offers no pin: it would silently lose its end.
        assertTrue(ClipboardRules.pinsWhole("  short  "))
        assertTrue(ClipboardRules.pinsWhole("z".repeat(ClipboardRules.MAX_CHARS)))
        assertFalse(ClipboardRules.pinsWhole("z".repeat(ClipboardRules.MAX_CHARS + 1)))
    }

    @Test fun nothingOpensWhatAClipPointsTo() {
        // coerceToText opens a content:// clip's provider and reads the stream, on the main thread.
        val readers = java.io.File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" && "coerceToText" in it.readText() }.map { it.path }.toList()
        assertEquals(emptyList<String>(), readers)
    }

    @Test fun aSavedPasteOptionFromBeforeItWasRemovedChangesNothing() {
        val plain = ItemConfig("c1", "clipboard")
        val old = plain.copy(options = mapOf("pasteOnClick" to "true"))
        assertEquals(ClipboardRules.pins(plain), ClipboardRules.pins(old))
        val bar = ClipboardRules.bar(old, emptyList(), listOf("latest"), "Empty") { "$it snippets" }
        assertEquals(ClipboardRules.bar(plain, emptyList(), listOf("latest"), "Empty") { "$it snippets" }.text, bar.text)
        // A layout that still holds the key loads, keeps it unread, and nothing breaks.
        val text = """{"version":3,"items":[{"id":"c1","type":"clipboard","options":{"pasteOnClick":"true"}}]}"""
        val loaded = Store.parseLayout(text)!!.items.single()
        assertEquals("clipboard", loaded.type)
        assertEquals("true", loaded.options["pasteOnClick"])
        assertEquals(ClipboardRules.pins(plain), ClipboardRules.pins(loaded))
    }

    @Test fun recordRecentMovesEntryToFrontAndSkipsPinnedInUnpinnedView() {
        var recent = emptyList<String>()
        recent = ClipboardRules.recordRecent(recent, "first")
        recent = ClipboardRules.recordRecent(recent, "second")
        assertEquals(listOf("second", "first"), recent)

        recent = ClipboardRules.recordRecent(recent, "first")
        assertEquals(listOf("first", "second"), recent)

        assertEquals(recent, ClipboardRules.recordRecent(recent, "   "))
        assertEquals(listOf("second"), ClipboardRules.unpinnedRecent(recent, listOf("first")))

        // A copy with a line end is kept as copied, but its pin is cleaned: the two are one snippet.
        val withEnd = ClipboardRules.recordRecent(emptyList(), "hello\n")
        assertEquals(emptyList<String>(), ClipboardRules.unpinnedRecent(withEnd, listOf("hello")))
    }

    @Test fun barFollowsBarModeAndCountsUnpinnedPlusPinned() {
        val previewItem = ItemConfig("c1", "clipboard")
        val countItem = ItemConfig("c2", "clipboard", options = mapOf("bar" to "count"))

        val emptyBar = ClipboardRules.bar(previewItem, emptyList(), emptyList(), "Empty") { "$it snippets" }
        assertNull(emptyBar.text)
        assertFalse(emptyBar.active)

        val previewBar = ClipboardRules.bar(previewItem, listOf("pinned"), listOf("latest clip"), "Empty") { "$it snippets" }
        assertEquals("latest clip", previewBar.text)
        assertTrue(previewBar.active)

        val countBar = ClipboardRules.bar(countItem, listOf("same", "other"), listOf("same"), "Empty") { "$it snippets" }
        assertEquals("2", countBar.text)
        assertEquals("2 snippets", countBar.desc)
    }
}
