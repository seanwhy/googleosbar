package com.meridian11.books.bentobar.items

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The app's text files as a test reads them, so that what a test compares is the text the app shows, not a copy of it. */
object StringFiles {
    /** The strings and the plurals (each quantity's text) of some files, by resource name. */
    class Texts(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    /** The strings and plurals of [files], those in `values` first and then [folder]'s on top, as Android resolves them. */
    fun read(files: List<String>, folder: String = "values"): Texts {
        val strings = HashMap<String, String>()
        val plurals = HashMap<String, Map<String, String>>()
        for (dir in listOf("values", folder).distinct()) for (file in files) {
            val f = File("src/main/res/$dir/$file")
            if (!f.isFile) continue
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement
            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                when (e.tagName) {
                    "string" -> strings[e.getAttribute("name")] = unescape(e.textContent)
                    "plurals" -> {
                        val items = e.getElementsByTagName("item")
                        plurals[e.getAttribute("name")] = (0 until items.length).map { items.item(it) as Element }
                            .associate { it.getAttribute("quantity") to unescape(it.textContent) }
                    }
                }
            }
        }
        return Texts(strings, plurals)
    }

    /** The strings of one file, by resource name, with nothing from any other file. */
    fun file(path: String): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == "string" }
            .associate { it.getAttribute("name") to unescape(it.textContent) }
    }

    /** The names in one strings file that no code refers to as `R.string.x` or `R.plurals.x`: leftovers. */
    fun unused(path: String): List<String> {
        val code = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        return names(path).filter { !Regex("R\\.(string|plurals)\\.$it\\b").containsMatchIn(code) }
    }

    /** The names of the strings and plurals one file defines, in its order. */
    fun names(path: String): List<String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.map { it.getAttribute("name") }
    }

    /**
     * What Android's resource compiler (aapt) makes of a resource's text, the one reading every test uses: outside double
     * quotes a run of white space is one space and the ends are trimmed; a double quote is dropped and keeps the white
     * space inside it; `\'`, `\"`, `\n`, `\t` and `\uXXXX` are the character they name.
     */
    fun unescape(text: String): String {
        val raw = collapse(text)
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) { out.append(c); i++; continue }
            when (val next = raw[i + 1]) {
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'u' -> { out.append(raw.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                else -> { out.append(next); i += 2 }
            }
        }
        return out.toString()
    }

    /** White space as aapt collapses it, with the escapes left for [unescape] to read. */
    private fun collapse(raw: String): String {
        val out = StringBuilder()
        var quoted = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> { out.append(c).append(raw[i + 1]); i += 2; continue }
                c == '"' -> quoted = !quoted
                quoted -> out.append(c)
                c.isWhitespace() -> if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                else -> out.append(c)
            }
            i++
        }
        return if (quoted) out.toString() else out.toString().trimEnd(' ')
    }
}
