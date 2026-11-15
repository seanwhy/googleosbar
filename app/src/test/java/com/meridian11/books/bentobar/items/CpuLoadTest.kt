package com.meridian11.books.bentobar.items

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** The CPU load from idle-state counters that move only when a core wakes (#53: a quiet device read several times its load). */
class CpuLoadTest {
    private val second = 1_000_000L

    /** A core as the kernel counts it: each sleep (start to end, µs) is added to the counter when it ends. */
    private class Core(val sleeps: List<LongRange>) {
        fun counted(at: Long) = sleeps.filter { it.last <= at }.sumOf { it.last - it.first }
        fun busy(from: Long, to: Long) = (to - from) - sleeps.sumOf { s -> (minOf(s.last, to) - maxOf(s.first, from)).coerceAtLeast(0) }
    }

    /** Reads [cores] at [times] and returns each reading's cores (null where [CpuLoad.add] made none). */
    private fun read(cores: List<Core>, times: List<Long>): List<DoubleArray?> {
        val load = CpuLoad(cores.size)
        return times.map { t -> if (load.add(t, LongArray(cores.size) { cores[it].counted(t) })) load.perCore.copyOf() else null }
    }

    /** Sleeps of [nap] µs with [work] µs of work between them, from 0 to [until]. */
    private fun napping(nap: Long, work: Long, until: Long, from: Long = 0) =
        generateSequence(from) { it + nap + work }.takeWhile { it < until }.map { it..(it + nap) }.toList()

    @Test
    fun aQuietCoreReadsItsLoadAndNotItsUnfinishedSleeps() {
        // Sleeps of 2.5 s with 10 ms of work between them: about 0.4% busy, read once a second for five minutes.
        val core = Core(napping(nap = 2_500_000, work = 10_000, until = 301 * second))
        val times = (0..300).map { it * second }
        val readings = read(listOf(core), times).drop(1).map { it!![0] }
        val truth = core.busy(0, 300 * second).toDouble() / (300 * second)
        assertEquals(truth, readings.average(), 0.01)
        // The scenario is the one #53 met: each reading clamped on its own reads most of these seconds as busy.
        val clamped = (1..300).map { i ->
            (1 - (core.counted(times[i]) - core.counted(times[i - 1])).toDouble() / second).coerceIn(0.0, 1.0)
        }
        assertTrue("clamped reads ${clamped.average()}", clamped.average() > 0.5)
    }

    @Test
    fun aSpinningCoreReadsBusyFromItsFirstReading() {
        val readings = read(listOf(Core(emptyList())), (0..10).map { it * second })
        readings.drop(1).forEach { assertEquals(1.0, it!![0], 0.0) }
    }

    @Test
    fun aCoreThatWakesIntoWorkReadsBusyOnceItsSleepIsShown() {
        // Asleep from 0 to 3.5 s, then working without a pause. The readings during the sleep could only say busy;
        // the reading it wakes in and the next ones give that back, and from then on it reads busy.
        val core = Core(listOf(0L..3_500_000L))
        val times = (0..12).map { it * second }
        val readings = read(listOf(core), times).drop(1).map { it!![0] }
        assertEquals(listOf(1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.5, 1.0, 1.0, 1.0, 1.0, 1.0), readings)
        // What was read adds up to the work the core did.
        assertEquals(core.busy(0, 12 * second).toDouble() / second, readings.sum(), 1e-9)
    }

    @Test
    fun overAnyStretchTheReadingsAddUpToTheCounters() {
        val random = Random(53)
        val cores = List(12) {
            var t = random.nextLong(0, 2 * second)
            val sleeps = mutableListOf<LongRange>()
            while (t < 120 * second) {
                val nap = random.nextLong(1_000, 4 * second)
                sleeps += t..(t + nap)
                t += nap + random.nextLong(100, 300_000)
            }
            Core(sleeps)
        }
        // Readings about a second apart, as the ticker makes them.
        val times = generateSequence(0L) { it + second + random.nextLong(-50_000, 50_000) }.takeWhile { it < 120 * second }.toList()
        val readings = read(cores, times)
        for (c in cores.indices) {
            val read = (1 until times.size).sumOf { i -> readings[i]!![c] * (times[i] - times[i - 1]) }
            val truth = cores[c].busy(times.first(), times.last()).toDouble()
            // Only what the last sleep still owes may be missing: never more than the longest sleep.
            assertTrue("core $c read $read µs, worked $truth µs", abs(read - truth) <= 4 * second)
        }
        val shown = readings.drop(1).map { it!!.average() }.average()
        val worked = cores.sumOf { it.busy(times.first(), times.last()) }.toDouble() / cores.size / (times.last() - times.first())
        assertEquals(worked, shown, 0.03)
    }

    @Test
    fun theTotalIsTheAverageOfTheCores() {
        val load = CpuLoad(2)
        load.add(0, longArrayOf(0, 0))
        assertTrue(load.add(second, longArrayOf(second, 0)))
        assertArrayEquals(doubleArrayOf(0.0, 1.0), load.perCore, 0.0)
        assertEquals(0.5, load.total, 0.0)
    }

    @Test
    fun theFirstReadingAndOneAfterAGapMakeNone() {
        val load = CpuLoad(1)
        assertFalse(load.add(0, longArrayOf(0)))
        assertFalse(load.add(100_000, longArrayOf(0)))
        assertTrue(load.add(1_100_000, longArrayOf(0)))
        assertFalse(load.add(60 * second, longArrayOf(0)))
    }

    @Test
    fun aGapLetsGoOfWhatASleepOwed() {
        // A sleep that ended with more than its reading could show; then the bar was hidden for half a minute. The first
        // reading after that is the core's own (busy), not the old sleep's.
        val load = CpuLoad(1)
        load.add(0, longArrayOf(0))
        load.add(second, longArrayOf(3 * second))
        assertEquals(0.0, load.perCore[0], 0.0)
        assertFalse(load.add(31 * second, longArrayOf(3 * second)))
        assertTrue(load.add(32 * second, longArrayOf(3 * second)))
        assertEquals(1.0, load.perCore[0], 0.0)
    }

    @Test
    fun aLongSleepOwesAtMostFiveSeconds() {
        // Parked for a minute while read every second, then working: after the reading it wakes in, at most five more
        // give the sleep back.
        val core = Core(listOf(0L..60 * second))
        val readings = read(listOf(core), (0..70).map { it * second }).drop(1).map { it!![0] }
        assertEquals(List(5) { 0.0 }, readings.subList(60, 65))
        assertTrue(readings.subList(65, 70).all { it == 1.0 })
    }

    @Test
    fun aCounterThatGoesBackKeepsTheCoresLastReading() {
        val load = CpuLoad(1)
        load.add(0, longArrayOf(10 * second))
        load.add(second, longArrayOf(10 * second + 750_000))
        assertEquals(0.25, load.perCore[0], 1e-9)
        assertTrue(load.add(2 * second, longArrayOf(0)))
        assertEquals(0.25, load.perCore[0], 1e-9)
        load.add(3 * second, longArrayOf(500_000))
        assertEquals(0.5, load.perCore[0], 1e-9)
    }
}
