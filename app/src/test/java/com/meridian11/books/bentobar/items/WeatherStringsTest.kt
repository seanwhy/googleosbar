package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link between the Weather rules' words and the app's resources: the rules are tested with the text files, the
 * app reads resources by number, and this is what holds the two together. (The wording itself is not pinned here: a
 * copy edit must not fail a test that has no behaviour to protect.)
 */
class WeatherStringsTest {
    private val files = WeatherFileWords()
    private val nbsp = "\u00A0"

    @Test fun everyStringOfTheWeatherFileIsShownSomewhere() {
        // A text nobody asks for is a leftover: each is named in the app's code, as a string or as a plural.
        val code = java.io.File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (name in StringFiles.names("src/main/res/values/strings_weather.xml")) {
            val kind = if (name in files.plurals) "plurals" else "string"
            assertTrue("R.$kind.$name is in strings_weather.xml and nowhere in the code", Regex("R\\.$kind\\.$name\\b").containsMatchIn(code))
        }
    }

    @Test fun britishEnglishDiffersInOneWord() {
        val british = WeatherFileWords("values-en-rGB")
        assertEquals("Wind %1\$d kilometre per hour.", british.plurals.getValue("weather_wind_kmh_desc")["one"])
        assertEquals("Wind %1\$d kilometres per hour.", british.plurals.getValue("weather_wind_kmh_desc")["other"])
        assertEquals(listOf("weather_wind_kmh_desc"), StringFiles.names("src/main/res/values-en-rGB/strings_weather.xml"))
        // Everything else is the default's.
        for ((name, text) in files.strings) if (name in StringFiles.names("src/main/res/values/strings_weather.xml")) assertEquals(name, text, british.strings[name])
    }

    @Test fun theSpecialCharactersAreTheOnesTheDeckNames() {
        assertTrue(files.strings.getValue("weather_bar_soon").contains('·'))            // the middle dot
        assertTrue(files.strings.getValue("weather_bar_high_low").contains('↑'))        // up
        assertTrue(files.strings.getValue("weather_bar_high_low").contains('↓'))        // down
        assertTrue(files.strings.getValue("weather_searching").endsWith("…"))           // one character, not three dots
        assertEquals("9${nbsp}mph", files.say(W.WIND_MPH, 9))                                 // the unit doesn't part from its number
        assertEquals("15${nbsp}km/h", files.say(W.WIND_KMH, 15))
        assertEquals("20%", files.say(W.CHANCE, 20))
        assertFalse(files.strings.getValue("weather_two_letters").contains("..."))
    }

    @Test fun everyWordOfTheRulesIsTheResourceOfItsName() {
        // The app asks for a resource by number, the tests for a text by name: for each word both must mean the same one.
        for (word in W.entries) {
            val kind = if (word.plural) "plurals" else "string"
            val id = Class.forName("com.meridian11.books.bentobar.R\$$kind").getField(word.res).getInt(null)
            assertEquals("$word should be R.$kind.${word.res}", id, weatherRes(word))
            if (word.plural) assertTrue("${word.res} is no plural in the files", word.res in files.plurals)
            else assertTrue("${word.res} is no string in the files", word.res in files.strings)
        }
        assertEquals("no resource twice", W.entries.size, W.entries.map { weatherRes(it) }.toSet().size)
    }

    @Test fun theWordsOfTheConditionsAndOfWhatFallsAreTheirOwn() {
        assertEquals(Sky.entries.map { it.name }, Sky.entries.map { it.word.name })
        assertEquals(listOf(W.BAR_RAIN, W.BAR_SNOW, W.BAR_STORM), Falls.entries.map { it.word })
    }
}
