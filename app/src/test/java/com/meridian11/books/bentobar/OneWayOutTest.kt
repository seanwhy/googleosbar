package com.meridian11.books.bentobar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What only reading the code can show: there is one way out of the device, and three item types use
 * it. These rules hold for every file of the app, so a change that opens a second way, or lets
 * another item ask a service, fails here and not in a review.
 */
class OneWayOutTest {
    private val main = File("src/main/java")
    private val sources = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** "items/WeatherItem.kt": the path under the app's package. */
    private fun File.place() = relativeTo(main).invariantSeparatorsPath.substringAfter("bentobar/")

    /** The code of a file without its comments: a comment may name what the code must not use. */
    private fun code(f: File) = f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines()
        .joinToString("\n") { it.substringBefore("//") }

    /** The files of the items that go online, by their names; with [keyed], of the two that ask with the user's own key. */
    private fun online(place: String, keyed: Boolean = false): Boolean {
        val name = place.removePrefix("items/")
        if (!place.startsWith("items/")) return false
        return name.startsWith("Flight") || name.startsWith("AirLabs") || name.startsWith("Stocks") || name.startsWith("Finnhub") ||
            (!keyed && (name.startsWith("Weather") || name.startsWith("Air") || name.startsWith("Fx")))
    }

    @Test fun theSourcesAreThere() {
        assertTrue("run from the app module: ${main.absolutePath}", sources.size > 40)
        assertTrue(sources.any { it.place() == "net/HttpTransport.kt" })
    }

    @Test fun onlyTheTransportOpensAConnection() {
        // Whatever could carry something off the device, by any road: named in net/HttpTransport.kt and nowhere else.
        val ways = listOf("HttpURLConnection", "HttpsURLConnection", "URLConnection", "openConnection", "openStream", "java.net.",
            "Socket", "OkHttp", "okhttp", "WebView", "CookieHandler", "CookieManager", "DownloadManager", "HttpClient", "ktor", "URL(")
        for (f in sources) {
            if (f.place() == "net/HttpTransport.kt" || f.place() == "items/AiUsageData.kt") continue
            val text = code(f)
            for (way in ways) assertTrue("${f.place()} mentions $way: connections are opened in net/HttpTransport.kt only", way !in text)
        }
    }

    @Test fun onlyWeatherFlightAndStocksAskAService() {
        // No road from what the accessibility service, the media players or a sampler read to a request.
        for (f in sources) {
            val place = f.place()
            if (place.startsWith("net/")) continue
            if ("Http.get(" in code(f)) assertTrue("$place calls Http.get: only items/Weather*.kt, items/Air*.kt, items/Flight*.kt, items/AirLabs*.kt, items/Stocks*.kt, items/Finnhub*.kt and items/Fx*.kt may", online(place))
            // Nor is there a way around the gate in Http.get: the transport is net's own, to ask and to make.
            for (way in listOf("Http.transport", "HttpTransport(")) assertTrue("$place reaches for the transport: a request goes through Http.get", way !in code(f))
        }
        val declared = sources.filter { Regex("""override\s+val\s+online\b""").containsMatchIn(code(it)) }.map { it.place() }.sorted()
        assertEquals("the item types that name an online service", listOf("items/AirItem.kt", "items/FlightItem.kt", "items/FxItem.kt", "items/StocksItem.kt", "items/WeatherItem.kt"), declared)
    }

    @Test fun onlyFlightAndStocksReadTheirKeys() {
        for (f in sources) {
            val place = f.place()
            if (place == "data/Online.kt") continue
            if ("Online.key(" in code(f)) assertTrue("$place reads a key: only items/Flight*.kt, items/AirLabs*.kt, items/Stocks*.kt and items/Finnhub*.kt may", online(place, keyed = true))
        }
    }

    @Test fun theCodeThatGoesOnlineWritesNoLogLines() {
        // A city, a flight number, the stocks someone follows and a key pass through these files. What a request came to is logged in one place
        // (Http.log: host and path), so they need no log line of their own, and can then leak none.
        val writes = listOf("Log.", "println(", "printStackTrace", "System.out", "System.err")
        for (f in sources) {
            val place = f.place()
            if (!online(place)) continue
            val text = code(f)
            for (w in writes) assertTrue("$place has $w: the files that go online write no log lines", w !in text)
        }
    }

    @Test fun onlyADebugBuildCanMoveTheClock() {
        // The staged clock is for tests on a device: nothing in the app itself sets it.
        for (f in sources) assertTrue("${f.place()} sets Now.ahead", !Regex("""Now\.ahead\s*[-+]?=[^=]""").containsMatchIn(code(f)))
    }
}
