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

    @Test fun eachHostBelongsToOneService() {
        for (host in com.meridian11.books.bentobar.net.Host.entries) assertTrue(host in Online.serviceOf(host).hosts)
        assertEquals(com.meridian11.books.bentobar.net.Host.entries.size, Online.Service.entries.sumOf { it.hosts.size })
    }
}
