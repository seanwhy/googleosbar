package com.meridian11.books.bentobar.items

/**
 * Who waits for a load that may never say it came back (it threw, or had nothing to say): each is told once, when it has
 * [arrived] or after [capMs], whichever is first. The delay and its cancelling come in, so the tests run it by hand. Main
 * thread; pure.
 */
internal class Waiters(
    private val capMs: Long,
    private val later: (delayMs: Long, run: Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
) {
    private val waiting = ArrayList<Wait>()

    private inner class Wait(val done: () -> Unit) : Runnable {
        override fun run() { if (waiting.remove(this)) done() }
    }

    /** [done] runs when the load has [arrived], or after the cap if it doesn't say so. */
    fun add(done: () -> Unit) {
        val wait = Wait(done)
        waiting += wait
        later(capMs, wait)
    }

    /** It is back: everyone waiting is told now, and their caps are taken back. */
    fun arrived() {
        for (wait in waiting.toList()) { cancel(wait); wait.run() }
    }
}
