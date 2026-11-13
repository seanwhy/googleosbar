package com.meridian11.books.bentobar.items

/**
 * What is slow to make, kept by key (an app's icon, its label). The lock is held only for the map: [make] runs outside it, so
 * the main thread reading while composing never waits for a load. Least recently used goes first past [max]. Pure.
 */
internal class SlowCache<K : Any, V : Any>(private val max: Int) {
    private val entries = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }
    /** Bumped by [forget]: what was read before it may be what it forgot, so it isn't kept. */
    private var generation = 0L

    fun peek(key: K): V? = synchronized(entries) { entries[key] }

    fun put(key: K, value: V) { synchronized(entries) { entries[key] = value } }

    /** The value for [key], made with [make] if it isn't here; null if it can't be made. Two threads asking at once may both make it, and the first stays. */
    fun get(key: K, make: () -> V?): V? {
        val asOf = synchronized(entries) {
            entries[key]?.let { return it }
            generation
        }
        val made = make() ?: return null
        return synchronized(entries) {
            if (generation != asOf) made else entries.getOrPut(key) { made }
        }
    }

    /** Drops every value [matching] says, and keeps out what is being made while this ran. */
    fun forget(matching: (K) -> Boolean) {
        synchronized(entries) { generation++; entries.keys.removeAll(matching) }
    }
}
