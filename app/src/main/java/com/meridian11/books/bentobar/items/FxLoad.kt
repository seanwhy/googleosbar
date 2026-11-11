package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.FxRules.FxTable
import com.meridian11.books.bentobar.net.Host
import com.meridian11.books.bentobar.net.Http
import com.meridian11.books.bentobar.net.Reply
import com.meridian11.books.bentobar.net.Request

// Requests and loading for the Exchange rates item (Frankfurter / ECB). No Android, and no logging.

object FxLoad {
    const val PATH = "/v2/rates"

    /** The European Central Bank's table for the last days: `providers=ECB`, for the promise the item makes ("reference rates from the European Central Bank"). */
    fun request(nowMs: Long) = Request(Host.FRANKFURTER, PATH, listOf("base" to "EUR", "from" to FxRules.startDate(nowMs), "providers" to "ECB"))

    /**
     * Asks the service; blocks. A good answer is a new table; a failure is a table too, with what went wrong and the rates
     * there were. Null: it was not asked after all (the switch went off, the bar hid).
     */
    fun load(last: FxTable?, now: Long): FxTable? {
        val reply = Http.get(request(now))
        val good = (reply as? Reply.Ok)?.let { FxRules.read(it.text, now) }
        if (good != null) return good
        val missed = Failures.missed(reply, slowAfterMs = FxRules.RETRY_MS) ?: return null
        val known = last ?: FxTable()
        return known.copy(failure = missed.failure, misses = known.misses + if (missed.went) 1 else 0, retryAfterSec = missed.waitSec)
    }
}
