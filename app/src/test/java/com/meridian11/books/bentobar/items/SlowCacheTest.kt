package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What is slow to make (an app's icon, its label) is made outside the lock the main thread reads under. */
class SlowCacheTest {
    @Test fun aSlowLoadDoesNotHoldUpAReaderOfTheCache() {
        // F78: the settings' rows waited on each icon being drawn while the app picker loaded 40 of them.
        val cache = SlowCache<String, String>(8)
        val inside = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val loading = thread { cache.get("a") { inside.countDown(); letGo.await(); "A" } }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        var seen = "not yet"
        val reader = thread { seen = cache.peek("b") ?: "no such" }
        reader.join(5_000)
        assertFalse("a reader waited for the load", reader.isAlive)
        assertEquals("no such", seen)
        letGo.countDown()
        loading.join(5_000)
        assertEquals("A", cache.peek("a"))
    }

    @Test fun whatWasMadeOnceIsNotMadeAgain() {
        val cache = SlowCache<String, String>(8)
        var made = 0
        repeat(3) { assertEquals("A", cache.get("a") { made++; "A" }) }
        assertEquals(1, made)
    }

    @Test fun whatFailedIsNotRemembered() {
        val cache = SlowCache<String, String>(8)
        var tries = 0
        assertNull(cache.get("a") { tries++; null })
        assertEquals("A", cache.get("a") { tries++; "A" })
        assertEquals(2, tries)
    }

    @Test fun theLeastRecentlyUsedGoesFirst() {
        val cache = SlowCache<String, String>(2)
        cache.get("a") { "A" }; cache.get("b") { "B" }
        cache.peek("a") // used, so "b" is the oldest now
        cache.get("c") { "C" }
        assertEquals("A", cache.peek("a"))
        assertNull(cache.peek("b"))
        assertEquals("C", cache.peek("c"))
    }

    @Test fun forgetTakesOnlyWhatIsAsked() {
        val cache = SlowCache<String, String>(8)
        cache.get("com.one/.Main") { "1" }; cache.get("com.two/.Main") { "2" }
        cache.forget { it.startsWith("com.one/") }
        assertNull(cache.peek("com.one/.Main"))
        assertEquals("2", cache.peek("com.two/.Main"))
    }

    @Test fun whatWasReadBeforeItsPackageChangedIsNotKept() {
        // F81: an icon loaded just before an update must not outlive the update.
        val cache = SlowCache<String, String>(8)
        val result = cache.get("a") { cache.forget { true }; "old" }
        assertEquals("the caller still gets something to draw", "old", result)
        assertNull(cache.peek("a"))
        assertEquals("new", cache.get("a") { "new" })
    }

    @Test fun aValueThatIsSimplyPutThereIsThere() {
        val cache = SlowCache<String, String>(8)
        cache.put("a", "A")
        assertEquals("A", cache.get("a") { error("no need to make it") })
    }

    @Test fun appsAreReadAgainAndListenedToOnlyWhileAnItemOfThemIsLive() {
        val code = SourceScan.code(SourceScan.sources.single { it.name == "ToolItems.kt" })
        assertFalse("every = { _, _ -> null }" in code)
        assertFalse("nothing is drawn under a lock the main thread reads", "synchronized(" in code)
        assertEquals("the App and the Folder item", 2, Regex("""override fun onLive\(\) = Apps\.watch\(type\)""").findAll(code).count())
        assertEquals(2, Regex("""override fun onIdle\(\) = Apps\.unwatch\(type\)""").findAll(code).count())
        assertTrue("registerReceiver(" in code && "unregisterReceiver(" in code)
    }
}
