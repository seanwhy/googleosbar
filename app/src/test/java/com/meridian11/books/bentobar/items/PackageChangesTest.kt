package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** No receiver is up while the bar is away: what changed then is asked for when it comes back. */
class PackageChangesTest {
    private val asked = ArrayList<Int>()
    private var answer: Pair<Int, List<String>>? = null
    private val changes = PackageChanges { asked += it; answer }

    @Test fun theFirstLookStartsFromBootAndEachNextWhereTheLastStopped() {
        answer = 7 to listOf("a", "b")
        assertEquals(listOf("a", "b"), changes.sinceLastAsked())
        answer = 9 to listOf("c")
        assertEquals(listOf("c"), changes.sinceLastAsked())
        assertEquals(listOf(0, 7), asked)
    }

    @Test fun nothingChangedTellsOfNothingAndKeepsThePlace() {
        answer = 7 to listOf("a")
        changes.sinceLastAsked()
        answer = null
        assertEquals(emptyList<String>(), changes.sinceLastAsked())
        answer = 8 to listOf("b")
        assertEquals(listOf("b"), changes.sinceLastAsked())
        assertEquals(listOf(0, 7, 7), asked)
    }

    @Test fun aWatchStartsByAskingWhatChangedWhileNoReceiverWasUp() {
        // S2: a Folder's icon and name stayed the old ones for the life of the process.
        val code = SourceScan.code(SourceScan.sources.single { it.name == "ToolItems.kt" })
        assertTrue("getChangedPackages" in code)
        val start = code.substringAfter("private fun startWatching()").substringBefore("private fun stopWatching()")
        val registered = start.indexOf("registerReceiver")
        val caughtUp = start.indexOf("sinceLastAsked")
        assertTrue("asked after the receiver is up, or a change in between is heard by neither", registered >= 0 && caughtUp > registered)
    }
}
