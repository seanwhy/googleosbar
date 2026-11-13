package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirRulesTest {
    private val place = Place("47.37", "8.55")
    private val sampleJson = """
        {
          "current": {
            "us_aqi": 42,
            "european_aqi": 28,
            "pm2_5": 8.4,
            "pm10": 15.2,
            "ozone": 64.0,
            "uv_index": 6.3
          },
          "hourly": {
            "uv_index": [1.2, 4.5, 7.8, 6.1]
          }
        }
    """.trimIndent()

    @Test fun parsesOpenMeteoAirQualityReply() {
        val reading = AirRules.read(sampleJson, place, now = 10_000L)
        assertNotNull(reading)
        reading!!
        assertEquals(42, reading.usAqi)
        assertEquals(28, reading.euAqi)
        assertEquals(8.4, reading.pm25!!, 0.01)
        assertEquals(15.2, reading.pm10!!, 0.01)
        assertEquals(64.0, reading.ozone!!, 0.01)
        assertEquals(6.3, reading.uv!!, 0.01)
        assertEquals(7.8, reading.uvMax!!, 0.01)
    }

    @Test fun rejectsMalformedOrEmptyReply() {
        assertNull(AirRules.read("not json", place, 10_000L))
        assertNull(AirRules.read("""{"current":{}}""", place, 10_000L))
    }

    @Test fun classifiesUsAndEuAqiBandsAndUvLevels() {
        assertEquals(AirRules.Band.GOOD, AirRules.usBand(35))
        assertEquals(AirRules.Band.MODERATE, AirRules.usBand(75))
        assertEquals(AirRules.Band.SENSITIVE, AirRules.usBand(120))
        assertEquals(AirRules.Band.UNHEALTHY, AirRules.usBand(175))
        assertEquals(AirRules.Band.VERY_UNHEALTHY, AirRules.usBand(250))
        assertEquals(AirRules.Band.HAZARDOUS, AirRules.usBand(350))

        assertEquals(AirRules.Band.GOOD, AirRules.euBand(15))
        assertEquals(AirRules.Band.MODERATE, AirRules.euBand(35))
        assertEquals(AirRules.Band.HAZARDOUS, AirRules.euBand(120))

        assertEquals(AirRules.UvBand.LOW, AirRules.uvBand(1.5))
        assertEquals(AirRules.UvBand.MODERATE, AirRules.uvBand(4.0))
        assertEquals(AirRules.UvBand.HIGH, AirRules.uvBand(6.5))
        assertEquals(AirRules.UvBand.VERY_HIGH, AirRules.uvBand(9.0))
        assertEquals(AirRules.UvBand.EXTREME, AirRules.uvBand(11.5))
    }

    @Test fun evaluatesToneAndThresholdAlert() {
        val calm = AirRules.sample("good", 100_000L)!!
        assertEquals(Tone.NORMAL, AirRules.toneOf(AirRules.usBand(calm.usAqi!!), calm.uv))
        assertFalse(AirRules.active(calm, thresholdAqi = 100, eu = false))

        val smoke = AirRules.sample("smoke", 100_000L)!!
        assertEquals(Tone.ALERT, AirRules.toneOf(AirRules.usBand(smoke.usAqi!!), smoke.uv))
        assertTrue(AirRules.active(smoke, thresholdAqi = 100, eu = false))
    }

    // ---- scale, ageing, status --------------------------------------------------------------------

    private val hour = 3_600_000L

    @Test fun aMissingEuropeanIndexReadsOnTheUsScaleAndNeverAsEuropean() {
        val usOnly = AirRules.AirReading("p", usAqi = 75, euAqi = null, fetchedAt = 1L)
        val aqi = AirRules.aqiOf(usOnly, eu = true)!!
        assertEquals(75, aqi.value)
        assertFalse(aqi.eu)
        // 75 on the European scale would be Unhealthy; on the US scale it is Moderate.
        assertEquals(AirRules.Band.MODERATE, AirRules.bandOf(aqi))
        assertFalse("a US 75 is below the US threshold of 100", AirRules.active(usOnly, thresholdAqi = 100, eu = true))
    }

    @Test fun aMissingUsIndexReadsOnTheEuropeanScale() {
        val euOnly = AirRules.AirReading("p", euAqi = 88, fetchedAt = 1L)
        val aqi = AirRules.aqiOf(euOnly, eu = false)!!
        assertTrue(aqi.eu)
        assertEquals(AirRules.Band.VERY_UNHEALTHY, AirRules.bandOf(aqi))
        assertTrue("a European 88 is over 60% of the threshold", AirRules.active(euOnly, thresholdAqi = 100, eu = false))
    }

    @Test fun noIndexAtAllMeansNoIndexAndNotZero() {
        val uvOnly = AirRules.AirReading("p", uv = 4.0, fetchedAt = 1L)
        assertNull(AirRules.aqiOf(uvOnly, eu = true))
        assertFalse(AirRules.active(uvOnly, thresholdAqi = 100, eu = false))
    }

    @Test fun anEmptyPartIsLeftOutOfWhatTheBarShows() {
        assertEquals(AirRules.Parts(aqi = false, uv = true), AirRules.parts("aqi", hasAqi = false, hasUv = true))
        assertEquals(AirRules.Parts(aqi = true, uv = false), AirRules.parts("uv", hasAqi = true, hasUv = false))
        assertEquals(AirRules.Parts(aqi = true, uv = false), AirRules.parts("both", hasAqi = true, hasUv = false))
        assertEquals(AirRules.Parts(aqi = false, uv = true), AirRules.parts("both", hasAqi = false, hasUv = true))
        assertEquals(AirRules.Parts(aqi = true, uv = true), AirRules.parts("both", hasAqi = true, hasUv = true))
        assertEquals(AirRules.Parts(aqi = true, uv = false), AirRules.parts("aqi", hasAqi = true, hasUv = true))
        assertEquals(AirRules.Parts(aqi = false, uv = true), AirRules.parts("uv", hasAqi = true, hasUv = true))
    }

    @Test fun aReadingAgesOutAfterThreeHoursByEitherClock() {
        val r = AirRules.AirReading("p", usAqi = 40, fetchedAt = 10 * hour, fetchedUp = 2 * hour)
        assertFalse(AirRules.old(r, 10 * hour, 2 * hour))
        assertFalse(AirRules.old(r, 13 * hour - 1, 5 * hour - 1))
        assertTrue("by the wall clock", AirRules.old(r, 13 * hour, 5 * hour - 1))
        assertTrue("by the time since boot", AirRules.old(r, 13 * hour - 1, 5 * hour))
        assertTrue("never read", AirRules.old(AirRules.AirReading("p"), 10 * hour))
    }

    @Test fun anAgedOutReadingNeverPassesForACurrentOne() {
        val noon = 12 * hour
        val yesterday = AirRules.AirReading("p", usAqi = 180, fetchedAt = noon - 9 * hour, fetchedUp = hour, failure = Failure.OFFLINE)
        // After a night offline: not "Unhealthy now", but no reading.
        assertEquals(Status.Missing(Failure.OFFLINE), AirRules.status(true, true, true, yesterday, noon, 10 * hour))
        val fresh = yesterday.copy(fetchedAt = noon - hour, fetchedUp = 9 * hour, failure = null)
        assertEquals(Status.Live(fresh), AirRules.status(true, true, true, fresh, noon, 10 * hour))
        assertEquals("old and nothing wrong: asked again", Status.Loading, AirRules.status(true, true, true, fresh.copy(fetchedAt = 0), noon))
    }

    @Test fun myLocationThatIsNotKnownSaysWhy() {
        assertEquals(Status.NoLocation(Locate.NOT_ALLOWED), AirRules.status(false, true, true, null, 0L, locating = Locate.NOT_ALLOWED))
        assertEquals(Status.NoLocation(Locate.OFF), AirRules.status(false, true, true, null, 0L, locating = Locate.OFF))
        assertEquals(Status.Loading, AirRules.status(false, true, true, null, 0L, locating = Locate.FINDING))
        assertEquals(Status.NotSetUp, AirRules.status(false, true, true, null, 0L))
        assertEquals(Status.Off(everOn = true), AirRules.status(true, false, true, null, 0L))
    }

    @Test fun nothingEverReadAndAFailureIsMissingNotLoading() {
        val failed = AirRules.AirReading("p", failure = Failure.NO_ANSWER)
        assertEquals(Status.Missing(Failure.NO_ANSWER), AirRules.status(true, true, true, failed, 100_000L))
    }

    @Test fun aReplyIsReadWithItsTimesAndWithoutAnIndexItDidNotHave() {
        val reading = AirRules.read("""{"current":{"us_aqi":42,"uv_index":6.3}}""", place, now = 10_000L, up = 77L)!!
        assertEquals(10_000L, reading.fetchedAt)
        assertEquals(77L, reading.fetchedUp)
        assertNull(reading.euAqi)
    }
}
