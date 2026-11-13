package com.meridian11.books.bentobar.items

/**
 * An item type's own code runs behind this, for input as for sampling: what it did wrong ends here. A click's exception
 * (or a method this device's Android doesn't have, which is no Exception) must not take down the process that hosts the
 * strip, its popups and keep awake. Pure, so the rule is unit-tested; [Ticker] gives it the log.
 */
internal object ItemGuard {
    /** Runs [call], or null when it failed: the failure is [what], and the log gets its kind alone when [kindOnly]. */
    inline fun <T> run(kindOnly: Boolean, what: String, noinline log: (String, Throwable?) -> Unit, call: () -> T): T? =
        try { call() } catch (e: Throwable) { failed(kindOnly, what, e, log); null }

    /**
     * What went wrong is logged, but for a type that goes online or shows the user's own things only its kind: an
     * exception's message can quote what was being read (a reply, a title). What the process cannot survive is not ours to
     * swallow.
     */
    fun failed(kindOnly: Boolean, what: String, e: Throwable, log: (String, Throwable?) -> Unit) {
        if (e is VirtualMachineError) throw e
        if (kindOnly) log("$what: ${e.javaClass.simpleName}", null) else log(what, e)
    }
}
