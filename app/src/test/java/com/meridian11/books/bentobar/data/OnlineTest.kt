package com.meridian11.books.bentobar.data

import com.meridian11.books.bentobar.data.Online.Service.AIRLABS
import com.meridian11.books.bentobar.data.Online.Service.OPEN_METEO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The switches for the three online services and the user's keys: off until set up here, and never anywhere but in their own file. */
class OnlineTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "bentobar-online-${System.nanoTime()}")
    private val told = ArrayList<Online.Service>()
    private val key = "a-key-nobody-may-see-123"

    @Before fun fresh() {
        dir.mkdirs()
        Online.onOff = { told += it }
        Online.init(dir)
    }

    @After fun gone() {
        Online.onOff = {}
        dir.deleteRecursively()
        // Leave nothing switched on for whoever runs next.
        Online.init(File(dir, "empty"))
    }

    private fun restart() = Online.init(dir)

    @Test fun nothingIsOnUntilTheUserSetsItUp() {
        for (s in Online.Service.entries) {
            assertFalse(Online.on(s)); assertFalse(Online.setUp(s)); assertFalse(Online.hasKey(s))
        }
        assertEquals(Online.State(), Online.state.value)
    }

    @Test fun settingUpTurnsItOnAndARestartRemembers() {
        assertTrue(Online.turnOn(OPEN_METEO))
        assertTrue(Online.on(OPEN_METEO)); assertTrue(Online.setUp(OPEN_METEO))
        assertFalse(Online.on(AIRLABS))
        restart()
        assertTrue(Online.on(OPEN_METEO)); assertTrue(Online.setUp(OPEN_METEO))
        assertFalse(Online.on(AIRLABS))
    }

    @Test fun aServiceThatNeedsAKeyStaysOffWithoutOne() {
        assertFalse(Online.turnOn(AIRLABS))
        assertFalse(Online.on(AIRLABS))
        assertEquals("", Online.key(AIRLABS))
    }

    @Test fun savingAKeyTurnsTheServiceOnAndARestartRemembers() {
        assertTrue(Online.saveKey(AIRLABS, "  $key\n"))
        assertEquals(key, Online.key(AIRLABS)) // trimmed
        assertTrue(Online.on(AIRLABS)); assertTrue(Online.setUp(AIRLABS)); assertTrue(Online.hasKey(AIRLABS))
        restart()
        assertEquals(key, Online.key(AIRLABS))
        assertTrue(Online.on(AIRLABS))
    }

    @Test fun anEmptyKeyIsNoKey() {
        assertFalse(Online.saveKey(AIRLABS, "   "))
        assertFalse(Online.hasKey(AIRLABS)); assertFalse(Online.on(AIRLABS))
    }

    @Test fun offDeletesWhatWasFetchedAndTellsTheItemsButCanBeTurnedOnAgain() {
        Online.turnOn(OPEN_METEO)
        Kept.fetched(OPEN_METEO).write("47.37,8.55", """{"temperature":15.7}""", 1_000)
        Kept.own("flight").write("item1", "LH455", 1_000)
        Online.turnOff(OPEN_METEO)
        assertFalse(Online.on(OPEN_METEO))
        assertEquals(emptySet<String>(), Kept.fetched(OPEN_METEO).names())
        assertEquals(listOf(OPEN_METEO), told)
        // What a feature keeps for itself is the feature's to delete.
        assertEquals(setOf("item1"), Kept.own("flight").names())
        // It was set up here, so Setup's switch may turn it back on; a restart keeps both facts.
        assertTrue(Online.setUp(OPEN_METEO))
        restart()
        assertFalse(Online.on(OPEN_METEO)); assertTrue(Online.setUp(OPEN_METEO))
        assertTrue(Online.turnOn(OPEN_METEO))
    }

    @Test fun switchingOffTwiceTellsOnce() {
        Online.turnOn(OPEN_METEO)
        Online.turnOff(OPEN_METEO); Online.turnOff(OPEN_METEO)
        assertEquals(1, told.size)
    }

    @Test fun offKeepsTheKeyAndRemovingTheKeyForgetsEverything() {
        Online.saveKey(AIRLABS, key)
        Online.turnOff(AIRLABS)
        assertFalse(Online.on(AIRLABS)); assertTrue(Online.hasKey(AIRLABS)); assertTrue(Online.setUp(AIRLABS))
        assertTrue(Online.turnOn(AIRLABS))
        Kept.fetched(AIRLABS).write("item1", "an answer", 1_000)
        Online.removeKey(AIRLABS)
        assertEquals("", Online.key(AIRLABS))
        assertFalse(Online.on(AIRLABS)); assertFalse(Online.setUp(AIRLABS)); assertFalse(Online.hasKey(AIRLABS))
        assertEquals(emptySet<String>(), Kept.fetched(AIRLABS).names())
        assertEquals(listOf(AIRLABS, AIRLABS), told)
        restart()
        assertEquals("", Online.key(AIRLABS)); assertFalse(Online.on(AIRLABS))
        assertFalse(File(dir, "online.json").readText().contains(key))
    }

    @Test fun theKeyIsInItsOwnFileAndInNothingAScreenObserves() {
        Online.saveKey(AIRLABS, key)
        assertFalse(Online.state.value.toString().contains(key))
        assertTrue(File(dir, "online.json").readText().contains(key))
        // No other file under the directory holds it.
        assertEquals(listOf("online.json"), dir.walkTopDown().filter { it.isFile && it.readText().contains(key) }.map { it.name }.toList())
    }

    @Test fun whatCannotBeSavedIsRemovedNotLeftAsItWas() {
        Online.saveKey(AIRLABS, key)
        assertTrue(File(dir, "online.json").readText().contains(key))
        // The disk refuses the next write (here: something is in the way of the file that is written first).
        File(dir, "online.json.part").mkdirs()
        Online.removeKey(AIRLABS)
        assertFalse(Online.hasKey(AIRLABS))
        // Better nothing on disk than the key that was just removed: the next start finds nothing set up.
        assertFalse(File(dir, "online.json").exists())
        assertFalse(File(dir, "online.json.part").exists())
        restart()
        assertEquals("", Online.key(AIRLABS))
        assertFalse(Online.on(AIRLABS))
    }

    @Test fun aFailedSaveAfterTurningOnKeepsEverySwitchAndKeyThatWereThere() {
        Online.saveKey(AIRLABS, key)
        File(dir, "online.json.part").mkdirs()
        // The disk refuses the write of turning Weather on: the file as it was stays, and nothing else is lost.
        assertTrue(Online.turnOn(OPEN_METEO))
        assertTrue(Online.on(OPEN_METEO))
        assertTrue(File(dir, "online.json").readText().contains(key))
        assertFalse(File(dir, "online.json.part").exists())
        restart()
        assertEquals(key, Online.key(AIRLABS))
        assertTrue(Online.hasKey(AIRLABS))
    }

    @Test fun aFailedSaveAfterSavingAKeyKeepsTheOtherKeys() {
        Online.saveKey(AIRLABS, key)
        File(dir, "online.json.part").mkdirs()
        Online.saveKey(com.meridian11.books.bentobar.data.Online.Service.FINNHUB, "another-key-98765")
        assertTrue(File(dir, "online.json").readText().contains(key))
    }

    @Test fun aFailedSaveAfterTurningOffStillForgetsTheFile() {
        Online.saveKey(AIRLABS, key)
        Online.turnOn(OPEN_METEO)
        File(dir, "online.json.part").mkdirs()
        Online.turnOff(AIRLABS)
        assertFalse(File(dir, "online.json").exists())
    }

    @Test fun aFileNobodyCanReadCountsAsNothingSetUp() {
        File(dir, "online.json").writeText("not json {")
        restart()
        assertEquals(Online.State(), Online.state.value)
        assertTrue(Online.turnOn(OPEN_METEO)) // and it works again from there
    }

    @Test fun aSwitchSavedWithoutItsKeyIsOff() {
        File(dir, "online.json").writeText("""{"on":["airLabs","openMeteo","somethingElse"],"setUp":["airLabs"],"keys":{}}""")
        restart()
        assertFalse(Online.on(AIRLABS)); assertFalse(Online.setUp(AIRLABS))
        assertTrue(Online.on(OPEN_METEO))
    }

    @Test fun theServicesAreTheseAndNoOthers() {
        assertEquals(listOf("openMeteo", "airLabs", "finnhub", "frankfurter"), Online.Service.entries.map { it.id })
        assertEquals(setOf(Online.Service.AIRLABS, Online.Service.FINNHUB), Online.Service.entries.filter { it.needsKey }.toSet())
        assertEquals(setOf(com.meridian11.books.bentobar.net.Host.FRANKFURTER), Online.Service.FRANKFURTER.hosts)
        // Every service stands for a host by name: none takes an address.
        for (service in Online.Service.entries) assertTrue("$service", service.hosts.isNotEmpty())
    }

    @Test fun aFileWithTheRetiredKrakenSwitchLoadsWithoutItAndIsWrittenAgain() {
        // What a 2.0 test build with Crypto on wrote: Kraken on and set up, beside a service that stays.
        File(dir, "online.json").writeText("""{"on":["kraken","frankfurter"],"setUp":["kraken","frankfurter"],"keys":{}}""")
        File(dir, "fetched/kraken").mkdirs()
        File(dir, "fetched/kraken/prices.txt").writeText("0\n{}")
        restart()
        assertEquals(setOf(Online.Service.FRANKFURTER), Online.state.value.on)
        assertEquals(setOf(Online.Service.FRANKFURTER), Online.state.value.setUp)
        val text = File(dir, "online.json").readText()
        assertFalse(text, "kraken" in text)
        assertTrue(text, "frankfurter" in text)
        assertFalse(File(dir, "fetched/kraken").exists())
        restart()
        assertTrue(Online.on(Online.Service.FRANKFURTER))
    }

    @Test fun aFileWithTheRetiredOwnAddressesSwitchLoadsWithoutItAndIsWrittenAgain() {
        // What 2.1 with a Value from a URL item wrote: "Your own addresses" on and set up, beside a service that stays.
        File(dir, "online.json").writeText("""{"on":["own","openMeteo"],"setUp":["own","openMeteo"],"keys":{}}""")
        restart()
        assertEquals(setOf(OPEN_METEO), Online.state.value.on)
        assertEquals(setOf(OPEN_METEO), Online.state.value.setUp)
        val text = File(dir, "online.json").readText()
        assertFalse(text, "own" in text)
        assertTrue(text, "openMeteo" in text)
        restart()
        assertTrue(Online.on(OPEN_METEO))
    }

    @Test fun whatARetiredTypeKeptOnThisInstallIsDeletedAtTheStart() {
        // What 2.1 left: a Shelf's texts and the address of a Value from a URL, a file per item, beside a Flight's and a Prompter's.
        for ((name, text) in listOf("shelf" to "a note dropped on it", "webvalue" to "https://example.com/value", "flight" to "{}", "prompter" to "my notes")) {
            File(dir, "kept/$name").mkdirs()
            File(dir, "kept/$name/a1.txt").writeText("0\n$text")
        }
        File(dir, "kept/shelf/b2.txt.part").writeText("half written")
        restart()
        for (name in Retired.kept) assertFalse(name, File(dir, "kept/$name").exists())
        assertEquals(setOf("flight", "prompter"), File(dir, "kept").list()?.toSet())
        assertEquals("my notes", Kept.own("prompter").read("a1")?.text)
        // And with nothing there to delete, a start is a start.
        restart()
        assertEquals(setOf("flight", "prompter"), File(dir, "kept").list()?.toSet())
    }

    @Test fun aServiceIsAskedOnlyWhileItIsOnSomethingIsOnScreenAndTheLayoutHasItsItem() {
        val asks: (String) -> Online.Service? = { mapOf("weather" to OPEN_METEO, "fx" to Online.Service.FRANKFURTER)[it] }
        val both = listOf(ItemConfig("w", "weather"), ItemConfig("x", "fx"))
        for (s in listOf(OPEN_METEO, Online.Service.FRANKFURTER)) {
            assertFalse("$s off", Online.mayAsk(s, true, both, asks))
            Online.turnOn(s)
            assertTrue("$s on", Online.mayAsk(s, true, both, asks))
            assertFalse("$s with nothing on screen", Online.mayAsk(s, false, both, asks))
            assertFalse("$s with its item removed", Online.mayAsk(s, true, both.filterNot { asks(it.type) == s }, asks))
            // A Hidden item may still have something to say.
            assertTrue("$s with its item hidden", Online.mayAsk(s, true, both.map { it.copy(section = Section.HIDDEN) }, asks))
        }
        // One service's item opens no other: a Weather item is not a reason to ask Frankfurter, nor the other way round.
        assertFalse(Online.mayAsk(OPEN_METEO, true, listOf(ItemConfig("x", "fx")), asks))
        assertFalse(Online.mayAsk(Online.Service.FRANKFURTER, true, listOf(ItemConfig("w", "weather")), asks))
    }

    @Test fun eachHostBelongsToOneService() {
        for (host in com.meridian11.books.bentobar.net.Host.entries) assertTrue(host in Online.serviceOf(host).hosts)
        assertEquals(com.meridian11.books.bentobar.net.Host.entries.size, Online.Service.entries.sumOf { it.hosts.size })
    }
}
