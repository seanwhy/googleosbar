package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Leave out titles with": what is kept of what is typed, and that a word is found only as a whole word. */
class MeetingsWordsTest {
    @Test fun wordsArePartedAtCommasAndLineBreaks() {
        assertEquals(listOf("Hold", "Lunch", "Out of office"), Meetings.words("Hold, Lunch\nOut of office"))
        assertEquals(listOf("Commute"), Meetings.words(" , \n Commute ,,"))
        assertEquals(emptyList<String>(), Meetings.words(""))
    }

    @Test fun theSameWordIsKeptOnceIgnoringCase() {
        assertEquals(listOf("Hold", "Lunch"), Meetings.words("Hold, HOLD, hold, Lunch"))
    }

    @Test fun theLimits() {
        assertEquals(Meetings.MAX_WORDS, Meetings.words((1..50).joinToString(",") { "w$it" }).size)
        assertEquals(80, Meetings.WORD_CHARS)
        assertEquals(Meetings.WORD_CHARS, Meetings.words("x".repeat(Meetings.WORD_CHARS)).single().length)
        // A phrase over the limit is dropped whole, never kept cut: cut, it would be found nowhere or leave out too much.
        assertEquals(emptyList<String>(), Meetings.words("x".repeat(Meetings.WORD_CHARS + 1)))
        assertEquals(emptyList<String>(), Meetings.words("x".repeat(500)))
        assertEquals(listOf("Hold"), Meetings.words("x".repeat(Meetings.WORD_CHARS) + " y, Hold"))
        assertEquals("spaces count once", listOf("a b"), Meetings.words("a" + " ".repeat(200) + "b"))
        // One line, and nothing that turns the text around.
        assertEquals(listOf("evil"), Meetings.words("‮evil"))
        assertEquals(listOf("one two"), Meetings.cleanWords(listOf("one\ttwo")))
    }

    @Test fun aLongPhraseIsKeptWholeAndFound() {
        // 47 characters: cut at 40 before, and then never found.
        val phrase = "Quarterly business review with the EMEA leaders"
        assertEquals(listOf(phrase), Meetings.words("$phrase, Hold").take(1))
        assertTrue(Meetings.hasWord("Prep: $phrase (draft)", Meetings.words(phrase)))
    }

    @Test fun aWordIsFoundOnlyAsAWholeWord() {
        val hold = listOf("Hold")
        assertTrue(Meetings.hasWord("HOLD: desk", hold))
        assertTrue(Meetings.hasWord("On hold", hold))
        assertTrue(Meetings.hasWord("hold", hold))
        assertTrue(Meetings.hasWord("(Hold) review", hold))
        assertFalse(Meetings.hasWord("Shareholders call", hold))
        assertFalse(Meetings.hasWord("Holder sync", hold))
        assertFalse(Meetings.hasWord("Threshold", hold))
        assertFalse(Meetings.hasWord("Hold2", hold))
    }

    @Test fun aPhrase() {
        val ooo = listOf("Out of office")
        assertTrue(Meetings.hasWord("Ana: out of office", ooo))
        assertFalse(Meetings.hasWord("Out of officers", ooo))
        assertFalse(Meetings.hasWord("Out of the office", ooo))
    }

    @Test fun caseIsIgnored() {
        assertTrue(Meetings.hasWord("LUNCH with the team", listOf("lunch")))
        assertTrue(Meetings.hasWord("lunch", listOf("LUNCH")))
    }

    @Test fun aScriptWithoutSpacesNeedsNone() {
        // Japanese "regular meeting" inside a longer title: the characters around it are no word's edge in that script.
        val teirei = "定例"
        assertTrue(Meetings.hasWord("今日の" + teirei + "会議", listOf(teirei)))
        assertTrue(Meetings.hasWord("会議Hold", listOf("Hold")))
    }

    @Test fun noWordsLeaveNothingOut() {
        assertFalse(Meetings.hasWord("Anything", emptyList()))
        assertFalse(Meetings.hasWord("", listOf("Hold")))
    }
}
