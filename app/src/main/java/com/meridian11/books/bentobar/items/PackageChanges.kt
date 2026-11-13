package com.meridian11.books.bentobar.items

/**
 * What changed among the packages while nothing was listening. Android numbers its package changes from boot, and
 * [changedSince] says which came from a number on and how far they have got (null: none did). Each look picks up where the last
 * stopped; the first starts from boot, so what the settings window read before anything listened is covered too. Pure.
 */
internal class PackageChanges(private val changedSince: (sequence: Int) -> Pair<Int, List<String>>?) {
    private var seen = 0

    /** The packages changed since the last time this was asked. */
    fun sinceLastAsked(): List<String> {
        val (next, names) = changedSince(seen) ?: return emptyList()
        seen = next
        return names
    }
}
