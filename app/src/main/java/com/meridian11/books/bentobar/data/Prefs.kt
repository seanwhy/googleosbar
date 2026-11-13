package com.meridian11.books.bentobar.data

import android.content.SharedPreferences

/**
 * A preferences file as what keeps its state in one uses it: [Store] and the timers. Android's [SharedPreferences] on a
 * device ([SharedPrefs]), a test's map on the JVM, so the reading and the saving run without Android.
 */
interface Prefs {
    fun string(key: String): String?
    fun long(key: String, default: Long = 0): Long

    /** The writes of [change] go through together: at once in memory and, in the background, on disk. */
    fun edit(change: Editor.() -> Unit)

    interface Editor {
        /** Null removes the key. */
        fun put(key: String, value: String?)
        fun put(key: String, value: Long)
        fun remove(key: String)
    }
}

/** [Prefs] on Android's [SharedPreferences]. */
class SharedPrefs(private val prefs: SharedPreferences) : Prefs {
    override fun string(key: String): String? = prefs.getString(key, null)
    override fun long(key: String, default: Long): Long = prefs.getLong(key, default)

    override fun edit(change: Prefs.Editor.() -> Unit) {
        val editor = prefs.edit()
        object : Prefs.Editor {
            override fun put(key: String, value: String?) { editor.putString(key, value) }
            override fun put(key: String, value: Long) { editor.putLong(key, value) }
            override fun remove(key: String) { editor.remove(key) }
        }.change()
        editor.apply()
    }
}
