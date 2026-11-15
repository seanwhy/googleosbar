package com.meridian11.books.bentobar.items

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Other apps' notifications in memory: the listener's list replaces the one before, and an empty one forgets them all. */
class NoticesTest {
    private fun notice(key: String, title: String = "Lunch with Sam", text: String = "at the usual place") =
        Notices.Notice(key, "com.example.chat", "Chat", title, text, postedAt = 1_000L, ongoing = false, clearable = true,
            progress = null, icon = null, open = null)

    @After fun forget() = Notices.publish(emptyList())

    @Test fun publishReplacesAndAnEmptyListClears() {
        Notices.publish(listOf(notice("a"), notice("b")))
        assertEquals(listOf("a", "b"), Notices.now.value.map { it.key })
        Notices.publish(listOf(notice("c")))
        assertEquals("replaced, not added to", listOf("c"), Notices.now.value.map { it.key })
        Notices.publish(emptyList())
        assertEquals(emptyList<Notices.Notice>(), Notices.now.value)
    }

    @Test fun aNoticePrintsNoWordsOfItsOwn() {
        val printed = notice("a").toString()
        assertFalse(printed, "Lunch" in printed || "usual" in printed)
    }
}
