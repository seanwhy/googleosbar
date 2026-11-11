package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Waiting for a load that may never say it is done: told once, when it has, or at the cap. */
class WaitersTest {
    private var told = 0
    private val pending = ArrayList<Pair<Long, Runnable>>()
    private val waiters = Waiters(5_000, { ms, r -> pending += ms to r }, { r -> pending.removeAll { it.second === r } })

    @Test fun whoWaitsIsToldWhenTheLoadComesBackAndItsCapIsTakenBack() {
        waiters.add { told++ }
        assertEquals(0, told)
        waiters.arrived()
        assertEquals(1, told)
        assertTrue(pending.isEmpty())
    }

    @Test fun whoWaitsIsToldAtTheCapIfTheLoadNeverSaysSo() {
        waiters.add { told++ }
        assertEquals(5_000L, pending.single().first)
        pending.removeAt(0).second.run()
        assertEquals(1, told)
    }

    @Test fun eachIsToldOnceWhicheverComesFirst() {
        waiters.add { told++ }
        val cap = pending.single().second
        waiters.arrived()
        cap.run() // a cap that had already been taken off the queue when the load came back
        waiters.arrived()
        assertEquals(1, told)
    }

    @Test fun everyoneWaitingIsToldAndALaterLoadTellsNobodyAgain() {
        waiters.add { told++ }
        waiters.add { told++ }
        waiters.arrived()
        assertEquals(2, told)
        waiters.arrived()
        assertEquals(2, told)
    }
}
