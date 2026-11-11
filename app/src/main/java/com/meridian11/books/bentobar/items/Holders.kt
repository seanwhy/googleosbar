package com.meridian11.books.bentobar.items

/**
 * Who needs something that only runs while someone does: it [start]s with the first holder and [stop]s after the last. With
 * [graceMs], it stops only once nobody has come back for that long, for what is costly to set up again. The delay and its
 * cancelling come in, so the tests run it by hand. Main thread; pure.
 */
internal class Holders(
    private val graceMs: Long,
    private val later: (delayMs: Long, run: Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val start: () -> Unit,
    private val stop: () -> Unit,
) {
    private val holders = HashSet<String>()

    /** True from the first holder until the last one has been gone for [graceMs]. */
    var active = false
        private set

    private val letGo = Runnable {
        if (holders.isEmpty() && active) { active = false; stop() }
    }

    fun hold(who: String) {
        holders += who
        cancel(letGo)
        if (!active) { active = true; start() }
    }

    fun release(who: String) {
        if (!holders.remove(who) || holders.isNotEmpty()) return
        if (graceMs > 0) later(graceMs, letGo) else letGo.run()
    }
}
