package com.meridian11.books.bentobar.items

import java.util.Locale

/**
 * The app's US English text as the resource files have it, for the tests of Device batteries, Heat
 * and the CPU rule: a rule's words are then tested as a user will read them, to the character, and
 * not as the test would have written them.
 */
internal class HeatDevicesStrings(vararg files: String) {
    private val texts = StringFiles.read(files.toList())
    private val strings = texts.strings
    private val plurals = texts.plurals

    val names: Set<String> get() = strings.keys + plurals.keys

    /** The text as written, with its `%1$s` still in it. */
    operator fun get(name: String): String = strings[name] ?: error("no string named $name")

    /** The text with its arguments filled in, as `getString(id, args)` does. */
    fun format(name: String, vararg args: Any): String = String.format(Locale.US, get(name), *args)

    /**
     * A plural for [count], by English's rule (one for 1 and for −1, else other), as `getQuantityString`
     * does: with [args] filled in, or the count itself where there are none.
     */
    fun plural(name: String, count: Int, vararg args: Any): String {
        val forms = plurals[name] ?: error("no plural named $name")
        val form = forms[if (count == 1 || count == -1) "one" else "other"] ?: error("$name has no form for $count")
        return if (args.isEmpty()) String.format(Locale.US, form, count) else String.format(Locale.US, form, *args)
    }

    /** The two forms of a plural, as the copy deck lists them. */
    fun forms(name: String): Pair<String, String> = (plurals[name] ?: error("no plural named $name")).let { it.getValue("one") to it.getValue("other") }
}
