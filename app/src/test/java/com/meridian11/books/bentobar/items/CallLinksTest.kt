package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Join shows only for real video-call links. */
class CallLinksTest {
    @Test fun todoistTaskGetsNoJoin() {
        assertNull(CallLinks.find("https://app.todoist.com/app/task/trim-trees-6Xg2Wq8"))
        assertNull(CallLinks.find("Trim trees\n\nhttps://todoist.com/showTask?id=8123456789"))
    }

    @Test fun meetAndZoomGetJoin() {
        assertEquals("https://meet.google.com/abc-defg-hij", CallLinks.find("Join: https://meet.google.com/abc-defg-hij."))
        assertEquals("https://us02web.zoom.us/j/81234567890?pwd=abc", CallLinks.find("https://us02web.zoom.us/j/81234567890?pwd=abc"))
        assertTrue(CallLinks.isCall("https://zoom.us/j/81234567890"))
        assertTrue(CallLinks.isCall("https://zoom.us/my/jane.doe"))
    }

    @Test fun callLinkWinsOverAnEarlierWebLink() {
        assertEquals("https://meet.google.com/abc-defg-hij",
            CallLinks.find("Notes: https://todoist.com/showTask?id=1\nhttps://meet.google.com/abc-defg-hij"))
    }

    @Test fun lookalikesDontCount() {
        assertNull(CallLinks.find("https://example.com/?u=zoom.us"))
        assertNull(CallLinks.find("https://zoom.us/pricing"))
        assertNull(CallLinks.find("https://www.zoom.us/download"))
        assertNull(CallLinks.find("https://notzoom.us/j/1"))
        assertNull(CallLinks.find("https://teams.microsoft.com/v2/"))
        assertNull(CallLinks.find("https://webex.com/"))
        // A browser ends the host at the backslash: this goes to evil.example, however the text after it reads.
        assertNull(CallLinks.find("https://evil.example\\@meet.google.com/x"))
        assertFalse(CallLinks.isCall("https://evil.example\\@meet.google.com/x"))
        assertFalse(CallLinks.isTask("https://evil.example\\@todoist.com/app/task/1"))
    }

    @Test fun otherServices() {
        listOf(
            "https://teams.microsoft.com/l/meetup-join/19%3ameeting_abc%40thread.v2/0",
            "https://teams.live.com/meet/9312345678901",
            "https://acme.webex.com/acme/j.php?MTID=m123",
            "https://whereby.com/team-standup",
            "https://meet.jit.si/SomeRoom",
            "https://app.chime.aws/meetings/1234567890",
            "https://gotomeet.me/janedoe",
            "https://meet.goto.com/123456789",
        ).forEach { assertTrue(it, CallLinks.isCall(it)) }
    }

    @Test fun locationUrls() {
        assertTrue(CallLinks.hasUrl("https://meet.google.com/abc-defg-hij"))
        assertFalse(CallLinks.hasUrl("1600 Amphitheatre Pkwy, Mountain View"))
    }

    @Test fun todoistTasksAreTasks() {
        assertTrue(CallLinks.isTask("https://app.todoist.com/app/task/6cvRxj4r8hGf4hr6"))
        assertTrue(CallLinks.isTask("Notes\nhttps://todoist.com/showTask?id=123"))
        assertFalse(CallLinks.isTask("https://meet.google.com/abc-defg-hij"))
        assertFalse(CallLinks.isTask("https://todoist.com/pricing"))
        assertFalse(CallLinks.isTask("Dentist, 123 Main St"))
    }
}
