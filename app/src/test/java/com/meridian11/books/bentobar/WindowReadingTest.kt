package com.meridian11.books.bentobar

import com.meridian11.books.bentobar.SourceScan.code
import com.meridian11.books.bentobar.SourceScan.place
import com.meridian11.books.bentobar.SourceScan.sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The disclosure says BentoBar reads where windows are, never what is in them. What only reading the code can show is
 * that nothing else does: the accessibility API is named in two files, and the one title read is the one the
 * disclosure's reading explains. A change that reads another app's node or window text fails here, in the build.
 */
class WindowReadingTest {
    private val scanFiles = setOf("bar/BarService.kt", "bar/StatusBarScan.kt")

    @Test fun theAccessibilityNodeAndWindowApisAreNamedOnlyInBarServiceAndStatusBarScan() {
        // The service, its windows and their node trees. Nothing that holds a node or a window leaves bar/'s two scanning files.
        val apis = listOf(Regex("""\bAccessibilityService\b(?!\.GLOBAL_ACTION_)"""), Regex("""\bAccessibilityNodeInfo\b"""), Regex("""\bAccessibilityWindowInfo\b"""),
            Regex("""\bAccessibilityEvent\b"""), Regex("""\bfindFocus\b"""), Regex("""\brootInActiveWindow\b"""), Regex("""\bperformAction\b"""),
            Regex("""\bfindAccessibilityNodeInfo\w*"""), Regex("""\bgetRoot\b"""), Regex("""\.root\b"""), Regex("""\.windows\b"""), Regex("""\bwindowsOnAllDisplays\b"""),
            Regex("""\bgetRootInActiveWindow\b"""), Regex("""\bgetTitle\b"""), Regex("""\btakeScreenshot\w*"""),
            Regex("""\bgetWindows\b"""), Regex("""\bgetChild\b"""), Regex("""\bAccessibilityNodeInfoCompat\b"""))
        for (f in sources) {
            val place = f.place()
            if (place in scanFiles) continue
            // The constants of the global actions (Back, Home, Recents...) are names, not access; so is the import that brings them.
            val text = code(f).replace(Regex("(?m)^import android.accessibilityservice.AccessibilityService$"), "")
            for (api in apis) assertTrue("$place uses ${api.pattern}: the accessibility API stays in ${scanFiles.joinToString()}", !api.containsMatchIn(text))
        }
    }

    @Test fun theStatusBarWindowIsTheOnlyTreeWalkedAndItsOwnPictureIsTheOnlyOneTaken() {
        // The disclosure says the layout of the status bar is read, and the colour of its clock from a picture of its own window.
        // The two files may use the API (the test above); these pin which window each call is given.
        fun where(needle: String) = sources.filter { needle in code(it) }.flatMap { f -> List(Regex(Regex.escape(needle)).findAll(code(f)).count()) { f.place() } }
        assertEquals("the one tree walk is in StatusBarScan.scan", listOf("bar/StatusBarScan.kt"), where("getRoot("))
        assertEquals("the scan has one caller, BarService.check", listOf("bar/BarService.kt"), where("StatusBarScan.scan("))
        assertTrue("check hands the scan the status bar's own window",
            "StatusBarScan.scan(windows[listed.indexOf(barFact)])" in code(File("src/main/java/com/meridian11/books/bentobar/bar/BarService.kt")))
        assertEquals("the one picture is of one window, by its id", listOf("bar/StatusBarScan.kt"), where("takeScreenshotOfWindow(s.windowId"))
        assertEquals("no picture of the whole screen", emptyList<String>(), where("takeScreenshot("))
    }

    @Test fun theGlobalActionsAreOneCallInBarService() {
        // Tools and Shortcut press Back, Home, Recents and the like through Env.global, which asks BarService.global.
        val users = sources.filter { Regex("""\bperformGlobalAction\b""").containsMatchIn(code(it)) }.map { it.place() }
        assertEquals(listOf("bar/BarService.kt"), users)
    }

    @Test fun itemsNeverHoldTheService() {
        // Items get Env.global and Env.running, not the service: only BarService and two small readers name the connected one.
        for (f in sources) {
            val place = f.place()
            // Not even the other files in bar/: a bar/ helper that held the service could read windows the scan files may not.
            val text = code(f)
            assertTrue("$place names the service: items ask Env.running or Env.global", !Regex("""\bBarService\.current\b""").containsMatchIn(text) ||
                place in setOf("bar/BarService.kt", "items/Env.kt", "ui/BarMenuActivity.kt"))
        }
    }

    @Test fun aWindowsTitleIsReadInOnePlaceAndOnlyForASystemWindow() {
        // `check` in BarService tells the screenshot preview from a shade by the title of a large TYPE_SYSTEM window, and
        // nothing else reads a title. The title is compared, never logged or kept. AGENTS.md names this check.
        val holders = sources.filter { "AccessibilityWindowInfo" in code(it) }
        assertEquals(scanFiles, holders.map { it.place() }.toSet())
        val titles = holders.flatMap { f -> code(f).lines().withIndex().filter { Regex("""\.(title|getTitle\(\))""").containsMatchIn(it.value) }.map { f.place() to it.value.trim() } }
            // The item type's own title (the tooltip's text) is not a window's.
            .filterNot { (_, line) -> "Items.of(" in line }
        assertEquals("the lines that read a window's title: $titles", 1, titles.size)
        val (place, line) = titles.single()
        assertEquals("bar/BarService.kt", place)
        assertTrue(line, "Screenshot" in line)
        // The same condition, a few lines up, is about TYPE_SYSTEM windows only.
        val lines = code(File("src/main/java/com/meridian11/books/bentobar/bar/BarService.kt")).lines()
        val at = lines.indexOfFirst { it.trim() == line }
        assertTrue("the title check is for a TYPE_SYSTEM window only", lines.subList(maxOf(0, at - 3), at + 1).any { "TYPE_SYSTEM" in it })
        // Not logged: no Log call names a title.
        for (f in sources) for (l in code(f).lines()) assertTrue("${f.place()} logs a title: $l", !(Regex("""\bLog\.""").containsMatchIn(l) && Regex("""\b(it|w|cover|window)\.title\b""").containsMatchIn(l)))
    }

    @Test fun theWindowListingHookIsInTheDebugBuildOnly() {
        // `./bento debug windows` lists every window's title. It is in src/debug, which no release build has.
        for (f in sources) assertTrue("${f.place()} has the windows hook: it belongs in src/debug", "\"windows\" ->" !in code(f))
        assertTrue(File("src/debug/java/com/meridian11/books/bentobar/util/DebugReceiver.kt").readText().contains("\"windows\" ->"))
    }

    @Test fun anIntentCannotNameAPermissionForTheSettingsWindowToAsk() {
        // MainActivity is exported, so any app can start it. A permission prompt is asked for in-process, never read from an extra.
        val main = code(File("src/main/java/com/meridian11/books/bentobar/ui/MainActivity.kt"))
        // The one call asks for what takeAsked() returned (set in-process), so no extra can reach it, under any constant or local.
        val calls = Regex("""requestPermissions\(""").findAll(main).count()
        assertTrue("MainActivity asks for permissions safely", calls in 1..4)
        assertTrue("the ask must take its permission from takeAsked()", Regex("""takeAsked\(\)\?\.let \{ perm ->[^\n]*requestPermissions\(arrayOf\(perm\), 1\)""").containsMatchIn(main))
        // The intent's strings it does read are the two it always read.
        for (m in Regex("""getStringExtra\(([^)]*)\)""").findAll(main)) assertEquals("MainActivity reads an extra it should not", "EXTRA_ITEM", m.groupValues[1])
        assertTrue("MainActivity reads the intent's extras as a whole", !Regex("""\b(getExtras|getParcelableExtra|getSerializableExtra|getStringArrayExtra|getBundleExtra)\b|\.extras\b""").containsMatchIn(main))
        // And no other file hands an intent's extra to a permission prompt.
        for (f in sources) for (m in Regex("""requestPermissions\([^\n]*""").findAll(code(f))) assertTrue("${f.place()}: ${m.value}", "Extra" !in m.value)
    }
}
