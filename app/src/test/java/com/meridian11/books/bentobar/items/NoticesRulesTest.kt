package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** What Notification icons makes of other apps' notifications: which count, which the bar shows, the popup's order, the live one. */
class NoticesRulesTest {
    private val own = "com.meridian11.books.bentobar"

    private fun raw(key: String, pkg: String = "com.example.$key", postedAt: Long = 0, ongoing: Boolean = false, summary: Boolean = false,
                    progress: Int = 0, max: Int = 0, indeterminate: Boolean = false) =
        NoticesRules.Raw(key, pkg, "Chat", "Lunch with Sam", "at the usual place", postedAt, ongoing, clearable = !ongoing,
            groupSummary = summary, progress = progress, progressMax = max, indeterminate = indeterminate)

    private fun notice(key: String, pkg: String, postedAt: Long, ongoing: Boolean = false, progress: Float? = null) =
        Notices.Notice(key, pkg, pkg, "Title of $key", "Text of $key", postedAt, ongoing, clearable = !ongoing, progress = progress,
            icon = null, open = null)

    private fun List<Notices.Notice>.keys() = map { it.key }

    @Test fun ourOwnAndGroupSummariesAreLeftOut() {
        // BentoBar's own (a timer, the Live Update chip) are its own business; a group's summary repeats its children.
        val raws = listOf(raw("a"), raw("timer", pkg = own), raw("summary", summary = true), raw("b"))
        assertEquals(listOf("a", "b"), NoticesRules.kept(raws, own).map { it.key })
    }

    @Test fun oneIconPerAppItsNewest() {
        val list = listOf(notice("chat-1", "chat", 10), notice("mail-1", "mail", 20), notice("chat-2", "chat", 30), notice("cal-1", "cal", 5))
        assertEquals("one per app, the app's newest, newest first", listOf("chat-2", "mail-1", "cal-1"), NoticesRules.bar(list, ongoing = false).keys())
    }

    @Test fun ongoingOnlyWhenAsked() {
        val list = listOf(notice("download", "files", 50, ongoing = true), notice("chat", "chat", 10), notice("call", "chat", 60, ongoing = true))
        assertEquals("left out by default: it would hold the item in the bar for as long as it runs", listOf("chat"), NoticesRules.bar(list, ongoing = false).keys())
        assertEquals(listOf("call", "download"), NoticesRules.bar(list, ongoing = true).keys())
        // What the bar counts follows the same rule.
        assertEquals(1, NoticesRules.counted(list, ongoing = false).size)
        assertEquals(3, NoticesRules.counted(list, ongoing = true).size)
    }

    @Test fun moreSaysHowManyAppsAreNotShown() {
        assertEquals("+2", NoticesRules.more(shown = 4, apps = 6))
        assertNull("all shown", NoticesRules.more(shown = 4, apps = 4))
        assertNull("fewer apps than room", NoticesRules.more(shown = 2, apps = 2))
    }

    @Test fun progressIsAFractionOnlyWhenItCanBe() {
        assertEquals(0.25f, NoticesRules.progress(raw("a", progress = 50, max = 200))!!, 1e-6f)
        assertNull("no maximum: no progress", NoticesRules.progress(raw("a", progress = 50, max = 0)))
        assertNull("indeterminate: no fraction to show", NoticesRules.progress(raw("a", progress = 50, max = 200, indeterminate = true)))
        assertEquals("an app that overshoots is full, not more", 1f, NoticesRules.progress(raw("a", progress = 300, max = 200))!!, 1e-6f)
        assertEquals("nor less than nothing", 0f, NoticesRules.progress(raw("a", progress = -5, max = 200))!!, 1e-6f)
    }

    @Test fun theLiveOneIsTheNewestWithProgress() {
        val list = listOf(
            notice("old-download", "files", 10, ongoing = true, progress = 0.5f),
            notice("new-download", "browser", 20, ongoing = true, progress = 0.62f),
            notice("music", "player", 40, ongoing = true),
            notice("finished", "files", 50, ongoing = false, progress = 1f),
        )
        assertEquals("ongoing and with a progress, the newest of those", "new-download", NoticesRules.live(list)?.key)
        assertNull("nothing under way", NoticesRules.live(list.filter { it.progress == null || !it.ongoing }))
        assertNull(NoticesRules.live(emptyList()))
    }

    @Test fun thePopupListsEveryOneNewestFirstOngoingIncluded() {
        val list = listOf(notice("chat-1", "chat", 10), notice("download", "files", 50, ongoing = true), notice("chat-2", "chat", 30))
        assertEquals(listOf("download", "chat-2", "chat-1"), NoticesRules.rows(list).keys())
    }

    @Test fun aRawPrintsNoWords() {
        val printed = raw("a").toString()
        assertFalse(printed, "Lunch" in printed || "usual" in printed || "Chat" in printed)
    }
}
