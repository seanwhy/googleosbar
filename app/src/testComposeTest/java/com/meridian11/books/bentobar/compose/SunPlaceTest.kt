package com.meridian11.books.bentobar.compose

import com.meridian11.books.bentobar.items.SunItem
import com.meridian11.books.bentobar.items.SunRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Sunset item's place for the zone Android names, through Android's own ICU (here and not with the pure tests, which
 * have none). ICU's canonical name is CLDR's, which keeps old aliases (`Asia/Calcutta`), so the city must be found from
 * either name, and from the zone's other names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SunPlaceTest {
    private fun city(zone: String) = SunRules.place(zone, SunItem.canonical)?.city

    private val aliases = listOf("Asia/Kolkata" to "Kolkata", "Asia/Calcutta" to "Kolkata", "US/Pacific" to "Los Angeles",
        "Europe/Kiev" to "Kyiv", "Europe/Kyiv" to "Kyiv", "America/Buenos_Aires" to "Buenos Aires", "Asia/Saigon" to "Ho Chi Minh",
        "Europe/Belfast" to "London", "Europe/Berlin" to "Berlin")

    @Test fun aZoneFindsItsCityByWhicheverNameAndroidGivesIt() {
        for ((zone, city) in aliases) assertEquals(zone, city, city(zone))
    }

    @Test fun beforeAndroid16TheZonesOtherNamesFindIt() {
        // ICU's canonical name alone is no help there: for Asia/Kolkata it is Asia/Calcutta, which the table doesn't hold.
        assertEquals("Asia/Calcutta", android.icu.util.TimeZone.getCanonicalID("Asia/Kolkata"))
        for ((zone, city) in aliases) assertEquals(zone, city, SunRules.place(zone) { SunItem.tableName(it, iana = false) }?.city)
    }

    @Test fun aZoneWithNoCityHasNone() {
        for (zone in listOf("UTC", "Etc/UTC", "GMT", "Etc/GMT+5", "Not/A_Zone")) assertNull(zone, city(zone))
    }
}
