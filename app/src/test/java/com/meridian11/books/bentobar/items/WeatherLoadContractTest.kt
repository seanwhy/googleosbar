package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.net.Host
import org.junit.After
import org.junit.Before
import java.io.File

/** Weather under the contract every online service keeps; what is Weather's own is in [WeatherLoadTest]. */
class WeatherLoadContractTest : OnlineServiceContract<Reading>() {
    private val dir = File(System.getProperty("java.io.tmpdir"), "bentobar-weather-contract-${System.nanoTime()}")
    private val zurich = Place("47.37", "8.55")
    private val now = 1791192325_000L

    @Before fun fresh() { Online.init(dir); Online.turnOn(Online.Service.OPEN_METEO) }
    @After fun gone() { dir.deleteRecursively(); Online.init(File(dir, "empty")) }

    override val host = Host.OPEN_METEO
    override val path = "/v1/forecast"
    override val goodReply = File("src/test/resources/openmeteo/forecast_zurich.json").readText()
    override fun load(last: Reading?) = WeatherLoad.load(zurich, last, now) { setOf(zurich) }
    override fun failure(v: Reading) = v.failure?.name
    override fun misses(v: Reading) = v.misses
    override fun waitSec(v: Reading) = v.retryAfterSec
    override fun numbers(v: Reading) = v.current
    override fun every(v: Reading) = WeatherRules.every(v)
    override val freshMs: Long? = 30 * 60_000L
    override val retryMs = WeatherRules.RETRY_MS
    override val slowMs = WeatherRules.SLOW_MS
}
