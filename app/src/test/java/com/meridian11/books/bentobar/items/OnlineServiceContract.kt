package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.net.FakeHttp
import com.meridian11.books.bentobar.net.Host
import com.meridian11.books.bentobar.net.Http
import com.meridian11.books.bentobar.net.HttpTransport
import com.meridian11.books.bentobar.net.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What every online service owes, in one place: a service extends this with its request, a good reply and its load,
 * and inherits the failure and pace tests instead of copying them. The network is a [FakeHttp], which also fails
 * whatever the real transport would refuse.
 */
abstract class OnlineServiceContract<V : Any> {
    protected abstract val host: Host
    protected abstract val path: String
    protected abstract val goodReply: String
    /** The service's load; it asks through `Http.get`. */
    protected abstract fun load(last: V?): V?
    /** What the value says went wrong, by its name ("OFFLINE"), or null. */
    protected abstract fun failure(v: V): String?
    protected abstract fun misses(v: V): Int
    protected abstract fun waitSec(v: V): Long
    /** The numbers a value holds, compared to see that a failure keeps them. */
    protected abstract fun numbers(v: V): Any?
    /** How long the value stays before it is asked again, milliseconds. */
    protected abstract fun every(v: V): Long?

    /** This service's numbers for the pace: after no answer, and after being told to slow down with no time named. */
    protected abstract val retryMs: Long
    protected abstract val slowMs: Long
    /** How long a good reading stays before it is asked again. Each service gives it as a literal, so that a changed constant fails a test. */
    /** Null where the interval depends on more than the reading (Stocks: the market's hours, tested in its own rules). */
    protected open val freshMs: Long? = null

    private fun good(net: FakeHttp): V {
        net.reply(host, path, goodReply)
        return load(null)!!
    }

    @Test fun aGoodAnswerIsAReadingWithNothingWrong() = FakeHttp.use { net ->
        val v = good(net)
        assertNull(failure(v))
        assertNotNull(numbers(v))
    }

    @Test fun whatIsAskedIsARequestTheTransportWillSend() = FakeHttp.use { net ->
        good(net)
        assertNotNull(HttpTransport.address(net.asked.single()))
    }

    @Test fun whileTheGateIsShutNothingIsAskedAndNothingIsSaid() = FakeHttp.use { net ->
        Http.allowed = { false }
        assertNull(load(null))
        assertEquals(0, net.asked.size)
        assertEquals(0, Http.sent(host))
    }

    @Test fun withoutANetworkNothingLeavesAndNoMissIsCounted() = FakeHttp.use { net ->
        Http.connected = { false }
        val v = load(null)!!
        assertEquals("OFFLINE", failure(v))
        assertEquals(0, misses(v))
        assertEquals(0, net.asked.size)
    }

    @Test fun aTryThatReachedNobodyCountsAMissInARow() = FakeHttp.use { net ->
        net.fail(host, path, Why.OFFLINE)
        val once = load(null)!!
        assertEquals("OFFLINE", failure(once))
        assertEquals(1, misses(once))
        assertEquals(2, misses(load(once)!!))
    }

    @Test fun anErrorStatusIsNoAnswerAndKeepsWhatWasKnown() = FakeHttp.use { net ->
        net.reply(host, path, goodReply)
        net.fail(host, path, Why.STATUS, 500)
        val before = load(null)!!
        val after = load(before)!!
        assertEquals("NO_ANSWER", failure(after))
        assertEquals(numbers(before), numbers(after))
    }

    @Test fun aTimeoutIsNoAnswer() = FakeHttp.use { net ->
        net.fail(host, path, Why.TIMEOUT)
        assertEquals("NO_ANSWER", failure(load(null)!!))
    }

    @Test fun toldToSlowDownItKeepsTheWaitTheServiceNamed() = FakeHttp.use { net ->
        net.fail(host, path, Why.STATUS, 429, retryAfterSec = 90)
        val v = load(null)!!
        assertEquals("SLOW_DOWN", failure(v))
        assertEquals(90L, waitSec(v))
    }

    @Test fun aRequestHeldByAnotherStatusesWaitIsNotToldToSlowDown() = FakeHttp.use { net ->
        net.fail(host, path, Why.STATUS, 503, retryAfterSec = 60)
        val first = load(null)!!
        assertEquals("NO_ANSWER", failure(first))
        // The service is held for the minute it named; a second ask inside it never leaves, and says what started the hold.
        val held = load(first)!!
        assertEquals("NO_ANSWER", failure(held))
        assertEquals(retryMs, every(held))
        assertEquals(misses(first), misses(held))
        assertEquals(1, net.asked.size)
    }

    @Test fun aRequestHeldByATooManyRequestsIsToldToSlowDown() = FakeHttp.use { net ->
        net.fail(host, path, Why.STATUS, 429, retryAfterSec = 60)
        val first = load(null)!!
        val held = load(first)!!
        assertEquals("SLOW_DOWN", failure(held))
        assertEquals(slowMs, every(held))
        assertEquals(misses(first), misses(held))
        assertEquals(1, net.asked.size)
    }

    @Test fun aGoodStatusThatHoldsNoReadingIsNoAnswerAndCountsAMiss() = FakeHttp.use { net ->
        net.reply(host, path, "<html>sign in here</html>")
        val v = load(null)!!
        assertEquals("NO_ANSWER", failure(v))
        assertEquals(1, misses(v))
    }

    @Test fun aGoodReadingIsAskedAgainAtThePublishedInterval() = FakeHttp.use { net ->
        val fresh = freshMs ?: return@use
        assertEquals(fresh, every(good(net)))
    }

    @Test fun thePaceAfterAFailureIsTheServicesOwn() = FakeHttp.use { net ->
        net.fail(host, path, Why.STATUS, 500)
        assertEquals(retryMs, every(load(null)!!))
    }

    @Test fun toldToSlowDownItWaitsAtLeastItsOwnTimeOrAsLongAsTheServiceSaid() = FakeHttp.use { net ->
        val long = slowMs / 1000 + 600
        net.fail(host, path, Why.STATUS, 429)
        net.fail(host, path, Why.STATUS, 429, retryAfterSec = long)
        assertEquals(slowMs, every(load(null)!!))
        Http.resetCounts() // the service's wait from the first 429 ends: the second one is asked for real
        assertEquals(long * 1000, every(load(null)!!))
    }

    @Test fun offlineIsCheckedEveryMinuteAtFirst() = FakeHttp.use { net ->
        Http.connected = { false }
        assertEquals(Pace.MIN_MS, every(load(null)!!))
    }
}
