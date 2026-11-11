package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

/** One question at a time: what shows is the answer to the last thing asked, or nothing. */
class AskTest {
    private val waiting = ArrayDeque<Runnable>()
    private var held = false
    private var changes = 0
    private val wiring = Refresher.Wiring(Executor { r -> if (held) waiting.addLast(r) else r.run() }, { it.run() }, { 0L }, { changes++ })
    private fun run() { while (waiting.isNotEmpty()) waiting.removeFirst().run() }

    private val asked = ArrayList<String>()
    private val search = Ask<String, List<String>>(wiring) { q -> asked += q; if (q == "bug") error("a bug") else listOf("$q, Illinois", "$q, Missouri") }

    @Test fun anAnswerArrives() {
        assertEquals(Ask.State.Idle, search.state.value)
        search.ask("Springfield")
        assertEquals(Ask.State.Done("Springfield", listOf("Springfield, Illinois", "Springfield, Missouri")), search.state.value)
        assertEquals(1, changes)
    }

    @Test fun whileItIsOnItsWayTheStateSaysSo() {
        held = true
        search.ask("Springfield")
        assertEquals(Ask.State.Busy("Springfield"), search.state.value)
        run()
        assertEquals("Springfield", (search.state.value as Ask.State.Done).question)
    }

    @Test fun aNewerQuestionDropsTheAnswerToTheOlderOne() {
        held = true
        search.ask("Spring")
        search.ask("Springfield")
        run() // both answers arrive, the older one first
        assertEquals(Ask.State.Done("Springfield", listOf("Springfield, Illinois", "Springfield, Missouri")), search.state.value)
        assertEquals(1, changes)
    }

    @Test fun clearDropsAnAnswerOnItsWay() {
        held = true
        search.ask("Springfield")
        search.clear()
        assertEquals(Ask.State.Idle, search.state.value)
        run()
        assertEquals(Ask.State.Idle, search.state.value)
        assertEquals(0, changes)
    }

    @Test fun aQuestionThatWasOvertakenBeforeItsTurnIsNotAsked() {
        // Three presses while the first is still on its way: only the last one's text goes out after it.
        held = true
        search.ask("Spring"); search.ask("Springf"); search.ask("Springfield")
        run()
        assertEquals(listOf("Springfield"), asked)
        assertEquals("Springfield", (search.state.value as Ask.State.Done).question)
        // And a question that was cleared before its turn is not asked either.
        search.ask("Zurich"); search.clear()
        run()
        assertEquals(listOf("Springfield"), asked)
        assertEquals(Ask.State.Idle, search.state.value)
    }

    @Test fun workThatThrowsLeavesNothingShowing() {
        search.ask("bug")
        assertEquals(Ask.State.Idle, search.state.value)
    }

    @Test fun whereSeveralFieldsAskEachSeesOnlyItsOwnAnswer() {
        // Questions as "field:text": the menu's field and the settings' field share the one Ask.
        val fields = Ask<String, String>(wiring) { q -> q.substringAfter(':') + "!" }
        fields.ask("menu:Springfield")
        assertEquals(Ask.State.Done("menu:Springfield", "Springfield!"), fields.state.value.shownTo { it.startsWith("menu:") })
        assertEquals(Ask.State.Idle, fields.state.value.shownTo { it.startsWith("settings:") })
        assertEquals("menu:Springfield", fields.state.value.asked)
        // Typing in the other field leaves this answer; typing in its own clears it.
        fields.clearIf { it.startsWith("settings:") }
        assertEquals("menu:Springfield", fields.state.value.asked)
        fields.clearIf { it.startsWith("menu:") }
        assertEquals(Ask.State.Idle, fields.state.value)
        assertEquals(null, fields.state.value.asked)
    }


    @Test fun whatTheWorkThrowsIsReportedByItsKindOnly() {
        val reported = ArrayList<String>()
        val a = Ask<String, String>(Refresher.Wiring(Executor { it.run() }, { it.run() }, { 0L }, report = { reported += it })) { throw IllegalArgumentException("a city name") }
        a.ask("q")
        assertEquals(listOf("IllegalArgumentException"), reported)
        assertEquals(Ask.State.Idle, a.state.value)
    }
}
