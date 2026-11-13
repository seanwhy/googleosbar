package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.AirRules.AirReading
import com.meridian11.books.bentobar.net.FakeHttp
import com.meridian11.books.bentobar.net.Host
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AirLoadTest : OnlineServiceContract<AirReading>() {
    private val zurich = Place("47.37", "8.55")
    private val now = 1_776_000_000_000L
    private val good = """{"current":{"us_aqi":42,"european_aqi":28,"pm2_5":8.4,"pm10":15.2,"ozone":64.0,"uv_index":3.3},"hourly":{"uv_index":[1.2,4.5,7.8]}}"""

    override val host = Host.OPEN_METEO_AIR
    override val path = "/v1/air-quality"
    override val goodReply = good
    override fun load(last: AirReading?) = AirLoad.load(zurich, last, now, up = 500L)
    override fun failure(v: AirReading) = v.failure?.name
    override fun misses(v: AirReading) = v.misses
    override fun waitSec(v: AirReading) = v.retryAfterSec
    override fun numbers(v: AirReading) = listOf(v.usAqi, v.euAqi, v.uv)
    override fun every(v: AirReading) = AirRules.every(v)
    // Published in About and the README: literals on purpose, so that changing a constant fails here.
    override val freshMs: Long? = 30 * 60_000L
    override val retryMs = 15 * 60_000L
    override val slowMs = 60 * 60_000L

    @Test fun theConstantsAreThePublishedIntervals() {
        assertEquals(freshMs, AirRules.FRESH_MS as Long?)
        assertEquals(10 * 60_000L, AirRules.MENU_MS)
        assertEquals(retryMs, AirRules.RETRY_MS)
        assertEquals(slowMs, AirRules.SLOW_MS)
    }

    @Test fun theRequestAsksForTodaysHoursSoThePeakIsTodays() {
        val r = AirLoad.request(zurich)
        assertEquals(listOf(
            "latitude" to "47.37",
            "longitude" to "8.55",
            "current" to "us_aqi,european_aqi,pm2_5,pm10,ozone,uv_index",
            "hourly" to "uv_index",
            "timezone" to "auto",
            "timeformat" to "unixtime",
            "forecast_days" to "1",
        ), r.query)
        assertNull(r.query.toMap()["forecast_hours"])
    }

    @Test fun aFailureForAnotherPlacesReadingKeepsNothingOfIt() = FakeHttp.use { net ->
        net.fail(Host.OPEN_METEO_AIR, "/v1/air-quality", com.meridian11.books.bentobar.net.Why.TIMEOUT)
        val other = AirReading("1.00,2.00", usAqi = 50, fetchedAt = now)
        assertNull(AirLoad.load(zurich, other, now, up = 500L)!!.usAqi)
    }

    @Test fun aGoodAnswerIsMarkedWithBothClocks() = FakeHttp.use { net ->
        net.reply(Host.OPEN_METEO_AIR, "/v1/air-quality", good)
        val r = AirLoad.load(zurich, null, now, up = 500L)!!
        assertEquals(now, r.fetchedAt)
        assertEquals(500L, r.fetchedUp)
        assertNotNull(r.usAqi)
    }
}
