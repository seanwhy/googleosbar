package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Test

/** The network rate is measured over a real stretch of time; a reading too soon after the last says nothing. */
class NetSamplerTest {
    @Test fun aReadingSoonAfterTheLastIsLeftForTheNextToMeasure() {
        val n = NetSampler()
        n.reading(1_000, 0, 10_000) // the first reading has nothing to compare with
        n.reading(6_000, 0, 11_000)
        assertEquals(5_000.0, n.down, 0.001)
        // 5 KB in 10 ms is 500 KB/s, the default line for "active": a click's refresh used to read it.
        n.reading(11_000, 0, 11_010)
        assertEquals(5_000.0, n.down, 0.001)
        assertEquals(1, n.downHistory.toList().size)
        // The second reading after it measures from the last reading kept, with everything that came in since.
        n.reading(21_000, 0, 12_000)
        assertEquals(15_000.0, n.down, 0.001)
        assertEquals(2, n.downHistory.toList().size)
    }

    @Test fun twoTicksInOneSecondStillBothCount() {
        val n = NetSampler()
        n.reading(0, 0, 10_000)
        n.reading(2_000, 0, 10_600)
        assertEquals(1, n.downHistory.toList().size)
    }

    @Test fun aGapStartsAfresh() {
        val n = NetSampler()
        n.reading(0, 0, 10_000)
        n.reading(9_000_000, 0, 10_000 + 3_600_000)
        assertEquals(0.0, n.down, 0.001)
    }
}
