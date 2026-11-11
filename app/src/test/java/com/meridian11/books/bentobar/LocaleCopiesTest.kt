package com.meridian11.books.bentobar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The text files. US English is the default (`values/`); `values-en-rGB` holds only the strings
 * whose British spelling differs, and the other Commonwealth variants are copies of it. There is
 * one file per feature beside the shared `strings.xml`, so that work on one feature never touches
 * another's text; this keeps all of them from drifting, file by file.
 */
class LocaleCopiesTest {
    private val res = File("src/main/res")
    private val copies = listOf("AU", "NZ", "IE", "IN", "ZA")
    private val name = Regex("""<(string|plurals|string-array)\s[^>]*name="([^"]+)"""")

    /** The string files of a folder (`strings.xml`, `strings_weather.xml`, …), by name. */
    private fun files(dir: String): Map<String, File> =
        File(res, dir).listFiles { f -> f.isFile && f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().associateBy { it.name }

    /** A file's entries as written, without indentation, blank lines or comments. */
    private fun entries(f: File): List<String> = f.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun names(f: File): List<String> = name.findAll(f.readText()).map { it.groupValues[2] }.toList()

    /** Names with their kind ("string/app_name", "plurals/common_hours"): a string and a plural may share a name. */
    private fun kinds(f: File): List<String> = name.findAll(f.readText()).map { it.groupValues[1] + "/" + it.groupValues[2] }.toList()

    @Test fun copiesMatchBritish() {
        val gb = files("values-en-rGB")
        assertTrue("en-GB has no strings", gb.isNotEmpty() && names(gb.getValue("strings.xml")).isNotEmpty())
        for (r in copies) {
            val copy = files("values-en-r$r")
            assertEquals("values-en-r$r doesn't have the files en-GB has", gb.keys, copy.keys)
            for ((file, british) in gb) assertEquals("values-en-r$r/$file differs from en-GB", entries(british), entries(copy.getValue(file)))
        }
    }

    @Test fun everyBritishKeyExistsInTheDefaultFileOfTheSameName() {
        val default = files("values")
        for ((file, british) in files("values-en-rGB")) {
            val us = default[file]
            assertTrue("values/$file is missing, but values-en-rGB/$file exists", us != null)
            val known = names(us!!).toSet()
            names(british).forEach { assertTrue("$it is in values-en-rGB/$file but not in values/$file", it in known) }
            assertTrue("values-en-rGB/$file is empty: delete it and its copies", names(british).isNotEmpty())
        }
    }

    @Test fun aBritishStringDiffersFromTheDefault() {
        // A British entry that says the same as the default is noise, and hides a later change of the default.
        val default = files("values")
        for ((file, british) in files("values-en-rGB")) {
            val us = entries(default.getValue(file)).toSet()
            entries(british).filter { it.startsWith("<string ") }.forEach { assertTrue("$it in values-en-rGB/$file is the default's own text", it !in us) }
        }
    }

    /** What British English changes: the rest of a text is the default's, word for word. */
    private fun american(british: String): String = listOf(
        "colour" to "color", "Colour" to "Color", "favourite" to "favorite", "behaviour" to "behavior", "centre" to "center", "Centre" to "Center",
        "organise" to "organize", "kilometre" to "kilometer", "greyed" to "grayed", "grey" to "gray", "cancelled" to "canceled", "Cancelled" to "Canceled",
    ).fold(british) { text, (gb, us) -> text.replace(gb, us) }

    private val textOf = Regex("""<string name="([^"]+)"[^>]*>(.*)</string>""")

    /**
     * Each British text is the default's after the spelling map. Editing a line in `values/` (the privacy text, say) must not pass every
     * test while British-English users keep the old wording in six places.
     */
    @Test fun aBritishTextIsTheDefaultInBritishSpelling() {
        val default = files("values")
        for ((file, british) in files("values-en-rGB")) {
            val us = textOf.findAll(default.getValue(file).readText()).associate { it.groupValues[1] to it.groupValues[2] }
            for (m in textOf.findAll(british.readText())) {
                val (_, name, text) = m.groupValues
                assertEquals("values-en-rGB/$file $name: the British text and the default's differ by more than spelling", us[name], american(text))
            }
        }
    }

    @Test fun aFeaturesFileHoldsOnlyThatFeaturesNames() {
        // strings_weather.xml: weather_…, item_weather_… and trigger_weather…; so two features can never pick the same name.
        for ((file, f) in files("values")) {
            if (file == "strings.xml") continue
            val feature = file.removePrefix("strings_").removeSuffix(".xml")
            assertTrue("$file: a feature's name is lower-case letters", feature.matches(Regex("[a-z]+")))
            names(f).forEach {
                assertTrue("$it in $file should start with ${feature}_, item_${feature}_ or trigger_$feature",
                    it.startsWith("${feature}_") || it.startsWith("item_${feature}_") || it.startsWith("trigger_$feature"))
            }
        }
    }

    @Test fun noNameIsDefinedTwice() {
        val seen = HashMap<String, String>()
        for ((file, f) in files("values")) kinds(f).forEach { n ->
            val other = seen.put(n, file)
            assertTrue("$n is in $other and in $file", other == null)
        }
    }

    @Test fun everyItemTypeHasItsTitleAndItsLineForTheCatalog() {
        // The item types that bring their own file; the names follow the old ones' pattern.
        for (type in listOf("media", "devices", "heat", "weather", "flight", "stocks", "clipboard", "displays", "air", "fx")) {
            val f = files("values")["strings_$type.xml"]
            assertTrue("values/strings_$type.xml is missing", f != null)
            val known = names(f!!)
            for (n in listOf("item_${type}_title", "item_${type}_desc")) assertTrue("$n is missing from strings_$type.xml", n in known)
        }
    }
}
