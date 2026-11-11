package com.meridian11.books.bentobar.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/** The real transport against a web server on this machine: what it sends, and what it makes of an answer. */
class HttpTransportTest {
    private val forecast = Request(Host.OPEN_METEO, "/v1/forecast", listOf("latitude" to "47.37", "longitude" to "8.55"))

    @Test fun aGoodAnswerComesBackAsText() {
        TinyServer { TinyServer.Answer("""{"temperature":"15.7 °C"}""") }.use { server ->
            val reply = server.transport().get(forecast)
            assertEquals("""{"temperature":"15.7 °C"}""", (reply as Reply.Ok).text)
            assertEquals("/v1/forecast?latitude=47.37&longitude=8.55", server.seen.single().path)
        }
    }

    @Test fun itSaysItIsBentoBarAndSendsNoCookieAndNothingElseAboutTheDevice() {
        TinyServer { TinyServer.Answer("{}") }.use { server ->
            server.transport().get(forecast)
            val headers = server.seen.single().headers
            assertEquals("BentoBar", headers["user-agent"])
            assertEquals("application/json", headers["accept"])
            assertEquals("gzip", headers["accept-encoding"])
            assertFalse(headers.containsKey("cookie"))
            assertFalse(headers.containsKey("authorization"))
            assertFalse(headers.containsKey("referer"))
            // Nothing but what a GET needs: no header that could name the device or the user.
            assertEquals(emptySet<String>(), headers.keys - setOf("user-agent", "accept", "accept-encoding", "host", "connection", "cache-control", "pragma"))
        }
    }

    @Test fun aPackedAnswerIsUnpacked() {
        val text = """{"hourly":[${(1..500).joinToString(",")}]}"""
        TinyServer { TinyServer.Answer(body = TinyServer.gzip(text), headers = mapOf("Content-Type" to "application/json", "Content-Encoding" to "gzip")) }.use { server ->
            assertEquals(text, (server.transport().get(forecast) as Reply.Ok).text)
        }
    }

    @Test fun aRedirectIsAnAnswerNotAWayToAnotherPlace() {
        TinyServer { seen ->
            if (seen.path.startsWith("/v1/forecast")) TinyServer.Answer(status = 302, headers = mapOf("Location" to "/elsewhere"))
            else TinyServer.Answer("""{"from":"elsewhere"}""")
        }.use { server ->
            val reply = server.transport().get(forecast)
            assertTrue(reply is Reply.Failed && reply.why == Why.STATUS && reply.status == 302)
            assertEquals(1, server.seen.size)
        }
    }

    @Test fun aStatusOutsideTheTwoHundredsIsAFailureWithItsNumber() {
        for (status in listOf(400, 401, 404, 500, 503)) TinyServer { TinyServer.Answer(status = status, body = """{"error":true}""".toByteArray()) }.use { server ->
            val reply = server.transport().get(forecast)
            assertTrue("$status", reply is Reply.Failed && reply.why == Why.STATUS && reply.status == status)
        }
    }

    @Test fun toldToSlowDownItSaysForHowLong() {
        TinyServer { TinyServer.Answer(status = 429, headers = mapOf("Retry-After" to "120")) }.use { server ->
            val reply = server.transport().get(forecast) as Reply.Failed
            assertEquals(Why.STATUS, reply.why)
            assertEquals(429, reply.status)
            assertEquals(120L, reply.retryAfterSec)
        }
        // A date instead of seconds, or nonsense, is no number: the caller's own back-off applies.
        TinyServer { TinyServer.Answer(status = 429, headers = mapOf("Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT")) }.use { server ->
            assertEquals(null, (server.transport().get(forecast) as Reply.Failed).retryAfterSec)
        }
    }

    @Test fun anAnswerThatIsTooLargeIsRefused() {
        TinyServer { TinyServer.Answer(body = ByteArray(5_000) { 'x'.code.toByte() }) }.use { server ->
            val reply = server.transport(maxBytes = 1_000).get(forecast)
            assertTrue(reply is Reply.Failed && reply.why == Why.TOO_LARGE)
            assertTrue(server.transport(maxBytes = 5_000).get(forecast) is Reply.Ok)
        }
    }

    @Test fun aSmallPackedAnswerThatUnpacksLargeIsRefusedToo() {
        val packed = TinyServer.gzip("0".repeat(200_000))
        assertTrue(packed.size < 1_000)
        TinyServer { TinyServer.Answer(body = packed, headers = mapOf("Content-Encoding" to "gzip")) }.use { server ->
            val reply = server.transport(maxBytes = 10_000).get(forecast)
            assertTrue(reply is Reply.Failed && reply.why == Why.TOO_LARGE)
        }
    }

    @Test fun aServerThatTakesTooLongIsATimeout() {
        TinyServer { TinyServer.Answer(body = "{}".toByteArray(), delayMs = 1_500) }.use { server ->
            val reply = server.transport(readMs = 200).get(forecast)
            assertTrue(reply is Reply.Failed && reply.why == Why.TIMEOUT)
        }
    }

    @Test fun anAnswerThatTricklesInIsGivenUpAfterAWhile() {
        // Every single read is quick enough, yet the whole would take its time: a request has a limit of its own,
        // so that a load can never hang on one answer.
        TinyServer { TinyServer.Answer(body = "x".repeat(1_000).toByteArray(), tricklesMs = 400) }.use { server ->
            val started = System.nanoTime()
            // Ten parts, 400 ms apart: four seconds in all, each read well within its two.
            val reply = server.transport(readMs = 2_000, totalMs = 300).get(forecast)
            assertTrue(reply is Reply.Failed && reply.why == Why.TIMEOUT)
            assertTrue("given up early, not read to the end", (System.nanoTime() - started) / 1_000_000 < 3_000)
        }
    }

    @Test fun nobodyListeningIsNoConnection() {
        // A port that was free a moment ago: nothing answers there.
        val port = ServerSocket(0).use { it.localPort }
        val transport = HttpTransport(open = { URL("http://127.0.0.1:$port/v1/forecast").openConnection() as HttpURLConnection })
        val reply = transport.get(forecast)
        assertTrue(reply is Reply.Failed && reply.why == Why.OFFLINE)
    }

    @Test fun textThatIsNotPackedThoughItSaysSoIsAFailureNotACrash() {
        TinyServer { TinyServer.Answer(body = "plain".toByteArray(), headers = mapOf("Content-Encoding" to "gzip")) }.use { server ->
            val reply = server.transport().get(forecast)
            assertTrue(reply is Reply.Failed && reply.why == Why.UNREADABLE)
        }
    }

    @Test fun theRealOpenerRefusesAnythingButHttpsToTheThreeHosts() {
        // The transport as the app makes it: its opener checks the address once more before a connection exists.
        // (Nothing is connected here: an address is only accepted or not.)
        fun opens(address: String) = runCatching { HttpTransport.openChecked(address) }.isSuccess
        assertTrue(opens("https://api.open-meteo.com/v1/forecast"))
        assertTrue(opens("https://geocoding-api.open-meteo.com/v1/search"))
        assertTrue(opens("https://airlabs.co/api/v9/flight"))
        assertFalse(opens("http://api.open-meteo.com/v1/forecast"))
        assertFalse(opens("https://example.com/v1/forecast"))
        assertFalse(opens("https://airlabs.co.example.com/api/v9/flight"))
        assertFalse(opens("https://evil.example/?x=airlabs.co"))
        assertFalse(opens("https://airlabs.co@evil.example/api"))
    }

    @Test fun anAnswerThatComesAfterTheWholeRequestsTimeIsATimeoutEvenAnEmptyOne() {
        TinyServer { TinyServer.Answer(body = ByteArray(0), delayMs = 300) }.use { server ->
            val reply = server.transport(readMs = 2_000, totalMs = 150).get(forecast)
            assertTrue(reply.toString(), reply is Reply.Failed && reply.why == Why.TIMEOUT)
        }
    }

    @Test fun aSmallerWholeThanTheReadLimitBoundsTheReadToo() {
        // The read limit can't outlast the whole request's: a stalled header wait is cut at the smaller of the two.
        TinyServer { TinyServer.Answer(body = ByteArray(0), delayMs = 3_000) }.use { server ->
            val began = System.nanoTime()
            val reply = server.transport(readMs = 5_000, totalMs = 300).get(forecast)
            assertTrue(reply.toString(), reply is Reply.Failed && reply.why == Why.TIMEOUT)
            assertTrue("waited ${(System.nanoTime() - began) / 1_000_000} ms", System.nanoTime() - began < 2_500_000_000L)
        }
    }

    @Test fun theUsersKeyGoesOutWithARequestToAHostThatTakesOneAndWithNoOther() {
        Http.key = { "a-key-nobody-may-see" }
        try {
            TinyServer { TinyServer.Answer("{}") }.use { server ->
                server.transport().get(Request(Host.FINNHUB, "/api/v1/quote", listOf("symbol" to "AAPL")))
                server.transport().get(forecast)
                assertEquals("/api/v1/quote?symbol=AAPL&token=a-key-nobody-may-see", server.seen[0].path)
                assertEquals("/v1/forecast?latitude=47.37&longitude=8.55", server.seen[1].path)
            }
        } finally { Http.key = { null } }
    }
}
