package com.meridian11.books.bentobar

import java.io.File

/**
 * What the source scans share: the app's Kotlin files, where each one is, and its code without the comments. A rule
 * about what the code may use is read from here, so a comment that names the thing, or a URL in a string, never
 * changes what a rule sees.
 */
object SourceScan {
    val main = File("src/main/java")
    val sources: List<File> = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** "items/WeatherItem.kt": the path under the app's package. */
    fun File.place() = relativeTo(main).invariantSeparatorsPath.substringAfter("bentobar/")

    fun code(f: File) = code(f.readText())

    /**
     * [text] with its comments taken out: `//` to the end of the line and block comments (which nest in Kotlin), but only
     * where they are not inside a string, a raw string or a character, so `"https://host"` stays whole and a `Log.` after
     * it on the same line is still seen. A line break stays where it was, so line numbers still count.
     */
    fun code(text: String): String {
        val out = StringBuilder()
        var i = 0
        fun at(s: String) = text.startsWith(s, i)
        while (i < text.length) {
            when {
                at("//") -> while (i < text.length && text[i] != '\n') i++
                at("/*") -> {
                    var depth = 0
                    do {
                        when {
                            at("/*") -> { depth++; i += 2 }
                            at("*/") -> { depth--; i += 2 }
                            else -> { if (text[i] == '\n') out.append('\n'); i++ }
                        }
                    } while (depth > 0 && i < text.length)
                }
                at("\"\"\"") -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    out.append(text, i, end); i = end
                }
                text[i] == '"' || text[i] == '\'' -> {
                    val quote = text[i]
                    out.append(quote); i++
                    while (i < text.length && text[i] != quote && text[i] != '\n') {
                        if (text[i] == '\\' && i + 1 < text.length) { out.append(text[i]); i++ }
                        out.append(text[i]); i++
                    }
                    if (i < text.length && text[i] == quote) { out.append(quote); i++ }
                }
                else -> { out.append(text[i]); i++ }
            }
        }
        return out.toString()
    }
}
