package com.meridian11.books.bentobar.data

/** [Prefs] in a map, for a test. [onPut], if set, runs inside each put: to see what happens in the middle of a save. */
class MapPrefs(initial: Map<String, Any> = emptyMap()) : Prefs {
    val map = HashMap(initial)
    var onPut: ((String) -> Unit)? = null

    override fun string(key: String): String? = map[key] as String?
    override fun long(key: String, default: Long): Long = map[key] as Long? ?: default

    override fun edit(change: Prefs.Editor.() -> Unit) {
        object : Prefs.Editor {
            override fun put(key: String, value: String?) { onPut?.invoke(key); if (value == null) map.remove(key) else map[key] = value }
            override fun put(key: String, value: Long) { onPut?.invoke(key); map[key] = value }
            override fun remove(key: String) { map.remove(key) }
        }.change()
    }
}
