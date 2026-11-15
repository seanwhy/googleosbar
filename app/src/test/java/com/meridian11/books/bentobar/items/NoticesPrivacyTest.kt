package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.SourceScan
import com.meridian11.books.bentobar.SourceScan.place
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Other apps' notifications are the user's own: shown, never kept past memory, never logged, never in a debug line; and
 * the listener that reads them exists only while a Notification icons item is live, and only with the access given.
 * The item runs here on a fake of what it asks of Android ([NoticesItem.Outside]).
 */
class NoticesPrivacyTest {
    private class Fake(var access: Boolean) : NoticesItem.Outside {
        var bound = 0
        var released = 0
        var listening = false
        override fun granted() = access
        override fun bind() { bound++; listening = true }
        override fun release() { released++; listening = false }
        override fun redraw() {}
        override fun listening() = listening
    }

    private val fake = Fake(access = true)
    private lateinit var before: NoticesItem.Outside

    @Before fun fake() { before = NoticesItem.outside; NoticesItem.outside = fake }

    @After fun back() {
        NoticesItem.onIdle()
        NoticesItem.debug(listOf("off"))
        NoticesItem.outside = before
        Notices.publish(emptyList())
    }

    private fun notice(key: String, title: String = "Lunch with Sam", text: String = "at the usual place") =
        Notices.Notice(key, "com.example.chat", "Chat", title, text, postedAt = 1_000L, ongoing = false, clearable = true,
            progress = null, icon = null, open = null)

    /** The files that handle a notification: every one named Notice… or Notices…, wherever it is. */
    private val noticeFiles: List<File> = SourceScan.sources.filter { it.name.startsWith("Notice") }

    @Test fun noNoticeFileWritesALogLine() {
        val names = noticeFiles.map { it.place() }.toSet()
        for (f in listOf("items/NoticeAccess.kt", "items/Notices.kt", "items/NoticesItem.kt", "items/NoticesRules.kt", "items/NoticesMenu.kt", "items/NoticeIcons.kt"))
            assertTrue("$f is not among the files scanned: $names", f in names)
        for (f in noticeFiles) for (w in listOf("Log.", "println(", "printStackTrace", "System.out", "System.err"))
            assertFalse("${f.place()} has $w: a notification's words could reach the log", w in SourceScan.code(f))
    }

    @Test fun noNoticeFileKeepsAnything() {
        // In memory only: not on the disk, not in the layout (the item's own options are written by the settings' `set`).
        for (f in noticeFiles) for (w in listOf("Kept.", "Store.", "SharedPreferences", "openFileOutput", "FileOutputStream", "writeText", "ClipData"))
            assertFalse("${f.place()} has $w: a notification is shown, never kept", w in SourceScan.code(f))
    }

    @Test fun aDebugLinePrintsCountsNotWords() {
        val staged = NoticesItem.debug(listOf("stage", "three"))
        assertNotNull(staged)
        val line = NoticesItem.debug(emptyList())!!
        assertTrue(line, "notices=3" in line)
        val words = NoticesItem.shown().flatMap { listOfNotNull(it.title, it.text) }
        assertEquals("the stage has words to leak", 6, words.size)
        for (said in listOf(staged!!, line)) for (w in words) assertFalse("\"$said\" says \"$w\"", w in said)
    }

    @Test fun goingIdleForgetsEverything() {
        NoticesItem.onLive()
        Notices.publish(listOf(notice("a"), notice("b")))
        assertEquals(2, NoticesItem.shown().size)
        NoticesItem.onIdle()
        assertEquals("nothing of another app's is held while nothing shows", emptyList<Notices.Notice>(), Notices.now.value)
        assertEquals(emptyList<Notices.Notice>(), NoticesItem.shown())
        assertFalse("and nothing listens", fake.listening)
    }

    @Test fun theListenerIsAskedForOnlyWhileLiveAndOnlyWithAccess() {
        assertEquals("no item live: no listener", 0, fake.bound)
        fake.access = false
        NoticesItem.onLive()
        NoticesItem.sample(0)
        assertEquals("live, but the access is not given: no listener", 0, fake.bound)
        assertFalse(NoticesItem.wantsListener())
        // Given while the item shows: within two seconds.
        fake.access = true
        NoticesItem.sample(1_000)
        assertEquals("not asked again within the two seconds", 0, fake.bound)
        NoticesItem.sample(2_000)
        assertEquals(1, fake.bound)
        assertTrue(NoticesItem.wantsListener())
        NoticesItem.sample(4_000)
        assertEquals("asked for once", 1, fake.bound)
        NoticesItem.onIdle()
        assertFalse("idle (the bar hidden, the item gone): let go", fake.listening)
        assertFalse(NoticesItem.wantsListener())
    }

    @Test fun accessTakenAwayWhileLiveLetsTheListenerGoAndForgets() {
        NoticesItem.onLive()
        NoticesItem.sample(0)
        assertTrue(fake.listening)
        Notices.publish(listOf(notice("a")))
        fake.access = false
        NoticesItem.sample(2_000)
        assertFalse(fake.listening)
        assertEquals(emptyList<Notices.Notice>(), Notices.now.value)
        assertFalse(NoticesItem.wantsListener())
    }

    @Test fun aListenerBoundUnaskedLeavesBeforeItReadsAnything() {
        // Android may bind the listener though nobody asked (after the access is turned on, or the process died while it was
        // bound): it then leaves before it reads a single notification.
        val code = SourceScan.code(File(SourceScan.main, "com/meridian11/books/bentobar/items/NoticeAccess.kt"))
        val connected = code.substringAfter("fun onListenerConnected(").substringBefore("override fun ")
        val asks = connected.indexOf("wantsListener")
        assertTrue(connected, asks >= 0 && connected.indexOf("requestUnbind") > asks)
        assertTrue("it asks before it reads: $connected", connected.indexOf("activeNotifications").let { it < 0 || it > asks })
        for (callback in listOf("fun onNotificationPosted(", "fun onNotificationRemoved(")) {
            val body = code.substringAfter(callback).substringBefore("override fun ")
            assertTrue("$callback reads only for the listener that was asked for: $body", "instance !== this" in body)
        }
    }
}
