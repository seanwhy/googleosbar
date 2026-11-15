package com.meridian11.books.bentobar.items

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Network item's own text file: every name is the feature's, and every string in it is used. */
class NetworkStringsTest {
    @Test fun everyNameIsTheFeaturesAndEveryStringIsUsed() {
        val code = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (name in StringFiles.names("src/main/res/values/strings_network.xml")) {
            assertTrue("$name: a Network name starts with network_", name.startsWith("network_"))
            assertTrue("R.string.$name is in strings_network.xml and nowhere in the code", Regex("R\\.string\\.$name\\b").containsMatchIn(code))
        }
    }
}
