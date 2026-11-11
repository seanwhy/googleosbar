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
    private val main = SourceScan.main
    private val sources = SourceScan.sources
    private fun File.place() = with(SourceScan) { place() }
    private fun code(f: File) = SourceScan.code(f)

    /** The files of the items that go online, by their names; with [keyed], of the two that ask with the user's own key. */
    private fun online(place: String, keyed: Boolean = false): Boolean {
        val name = place.removePrefix("items/")
        if (!place.startsWith("items/")) return false
        return name.startsWith("Flight") || name.startsWith("AirLabs") || name.startsWith("Stocks") || name.startsWith("Finnhub") ||
            (!keyed && (name.startsWith("Weather") || name.startsWith("Air") || name.startsWith("Fx") || name == "Places.kt" || name == "OnlineBase.kt"))
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
            if (f.place() == "net/HttpTransport.kt" || f.place() == "items/AiUsageData.kt" || f.place() == "items/CloudClipboardProtocol.kt" || f.place() == "items/RemoteKeyboardItem.kt") continue
            val text = code(f)
            for (way in ways) assertTrue("${f.place()} mentions $way: connections are opened in net/HttpTransport.kt only", way !in text)
        }
    }

    /** The only files that ask a service: each loader of the five online items, by name, not by a prefix a new file could match. */
    private val loaders = setOf("items/WeatherLoad.kt", "items/AirLoad.kt", "items/FxLoad.kt", "items/FlightLoad.kt", "items/StocksLoad.kt")

    @Test fun onlyTheFiveLoadersCallHttpGet() {
        // No road from what the accessibility service, the media players or a sampler read to a request: a request is made by a
        // loader, from what its own item holds. A new loader is added to the list above on purpose. Http.get( and Http::get both count.
        val call = Regex("""\bHttp\s*(\.|::)\s*get\b""")
        val callers = sources.filter { !it.place().startsWith("net/") && call.containsMatchIn(code(it)) }.map { it.place() }.toSet()
        assertEquals("the files that call Http.get", loaders, callers)
        for (f in sources) {
            val place = f.place()
            if (place.startsWith("net/")) continue
            // Nor is there a way around the gate in Http.get: the transport is net's own, to ask and to make.
            for (way in listOf("Http.transport", "HttpTransport(")) assertTrue("$place reaches for the transport: a request goes through Http.get", way !in code(f))
        }
        val declared = sources.filter { Regex("""override\s+val\s+online\b""").containsMatchIn(code(it)) }.map { it.place() }.sorted()
        assertEquals("the item types that name an online service", listOf("items/AirItem.kt", "items/FlightItem.kt", "items/FxItem.kt", "items/StocksItem.kt", "items/WeatherItem.kt"), declared)
    }

    @Test fun theFilesThatGoOnlineNameNothingThatReadsTheScreenTheMediaOrTheClipboard() {
        // An online file may use the language, time, JSON, Compose, the app's own packages, location (My location) and a few
        // plain Android types: everything it names is on this list, so a media title or a window's text has no way in.
        val allowed = listOf("java.", "kotlin.", "kotlinx.", "androidx.compose.", "androidx.annotation.", "android.os.", "android.text.", "android.net.Uri",
            "android.location.", "android.content.Intent", "android.content.pm.PackageManager", "android.provider.Settings", "android.Manifest",
            "android.annotation.", "com.meridian11.books.bentobar.R", "com.meridian11.books.bentobar.items.", "com.meridian11.books.bentobar.data.",
            "com.meridian11.books.bentobar.net.", "com.meridian11.books.bentobar.util.", "com.meridian11.books.bentobar.ui.")
        val named = Regex("""\b(?:android|androidx|java|javax|kotlin|kotlinx|com\.meridian11)\.[A-Za-z0-9_.]+""")
        for (f in sources) {
            val place = f.place()
            if (!online(place)) continue
            for (m in named.findAll(code(f).replace(Regex("""(?m)^package .*$"""), ""))) {
                val name = m.value
                assertTrue("$place names $name: the files that go online may name only what is on the list in OneWayOutTest", allowed.any { name.startsWith(it) })
            }
        }
    }

    @Test fun theSharedCommentStripperKeepsWhatIsInsideQuotes() {
        // A "//" inside a string is a URL, not a comment: cutting it hid whatever came after it on the line.
        val line = "val u = \"https://example.com\"; Log.i(TAG, u) // and a comment about Http.get("
        val code = SourceScan.code(line)
        assertTrue(code, "Log.i(TAG, u)" in code && "https://example.com" in code && "Http.get" !in code)
        assertEquals("a\nb", SourceScan.code("a /* one /* nested */ still */\nb").replace(" ", ""))
        assertTrue("'\"' is a character, not the start of a string", "Http.get" in SourceScan.code("val q = '\"'; Http.get(r)"))
    }

    @Test fun aKeyIsReadInOnePlaceAndNeverByAnItem() {
        // The transport adds the key: the item's code never holds one, so a future item can't put it in a message or send it to another service.
        for (f in sources) {
            val place = f.place()
            if (place == "data/Online.kt") continue
            if ("Online.key(" in code(f)) assertEquals("$place reads a key: only Http.key's wiring in items/Env.kt may", "items/Env.kt", place)
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
