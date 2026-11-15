package com.meridian11.books.bentobar.ui

import com.meridian11.books.bentobar.SourceScan
import com.meridian11.books.bentobar.SourceScan.place
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Notice.post(text, action) { … }` once ran its lambda when the message ran out, not when it was tapped: a new last parameter
 * took the trailing lambda, and an added item was removed after ten seconds. These two tests keep that from coming back.
 */
class NoticeCallersTest {
    @Test fun aTrailingLambdaOfPostIsTheActionAndNotTheGoneCallback() = runBlocking {
        val seen = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { Notice.messages.first() } }
        Notice.post("Added", "Undo") { }
        val m = seen.await()
        assertNotNull("the trailing lambda is onAction", m.onAction)
        assertNull("onGone is only ever passed by name", m.onGone)
    }

    @Test fun noCallerOfPostPassesMoreThanTheTextAndTheActionByPosition() {
        var calls = 0
        for (f in SourceScan.sources) {
            val code = SourceScan.code(f)
            var at = code.indexOf("Notice.post(")
            while (at >= 0) {
                calls++
                val args = topLevelArguments(code, at + "Notice.post(".length)
                val positional = args.takeWhile { !Regex("""^\w+\s*=[^=]""").containsMatchIn(it.trim()) }
                assertTrue("${f.place()}: Notice.post passes ${positional.size} arguments by position; onAction and onGone go by name or as the trailing lambda", positional.size <= 2)
                at = code.indexOf("Notice.post(", at + 1)
            }
        }
        // Three since the Bar page's Undo stands in the removed row's place and the library's "Added" on the type's row: neither is a snackbar.
        assertTrue("the scan saw the callers", calls >= 3)
    }

    /** The arguments of the call whose parenthesis was just opened at [from], split at the commas that are not inside brackets or strings. */
    private fun topLevelArguments(code: String, from: Int): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = from
        var i = from
        while (i < code.length) {
            when (code[i]) {
                '"' -> { i++; while (i < code.length && code[i] != '"') { if (code[i] == '\\') i++; i++ } }
                '(', '{', '[' -> depth++
                ')', '}', ']' -> { if (depth == 0) { out += code.substring(start, i); return out }; depth-- }
                ',' -> if (depth == 0) { out += code.substring(start, i); start = i + 1 }
            }
            i++
        }
        error("a call of Notice.post is never closed")
    }
}
