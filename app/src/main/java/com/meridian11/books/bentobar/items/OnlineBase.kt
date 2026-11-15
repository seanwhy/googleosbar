package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.net.Reply
import com.meridian11.books.bentobar.net.Why

// What every online item does alike and no service should copy: what a failed reply comes to, how soon it is
// asked again, when numbers are too old to show, the states an item can be in, its staged samples and its menu
// opening. Each service brings only its own numbers. No Android, and nothing here is logged.

/**
 * What a try that brought nothing comes to: [failure]; [went], whether a request left the device, so that it
 * counts as one more miss in a row ([Reply.sent]); [waitSec], how long the service asked to be left alone.
 */
class Missed(val failure: Failure, val went: Boolean, val waitSec: Long)

/** The one mapping from a reply to what went wrong. */
object Failures {
    /**
     * What [reply] stands for, for a reader whose words are [Failure]: null when it was not asked after all
     * (the switch went off, the bar hid, [Why.OFF]); [Failure.NO_ANSWER] for a good status that held no reading.
     * [slowAfterMs]: an answer asking for more patience than this counts as "slow down" too, so that words and wait agree.
     */
    fun missed(reply: Reply, slowAfterMs: Long = Long.MAX_VALUE): Missed? = when (reply) {
        is Reply.Ok -> Missed(Failure.NO_ANSWER, went = true, waitSec = 0)
        is Reply.Failed -> kind(reply, Failure.OFFLINE, Failure.SLOW_DOWN, Failure.NO_ANSWER, slowAfterMs)?.let { Missed(it, reply.sent, reply.retryAfterSec ?: 0) }
    }

    /**
     * The same mapping for a service with words of its own ([Finnhub.Failure]). Null: [Why.OFF]. [byStatus] reads what its
     * service alone means by a status, before the shared meanings. [Why.REFUSED] is BentoBar's own mistake and reads as no answer.
     */
    fun <F> kind(reply: Reply.Failed, offline: F, slowDown: F, noAnswer: F, slowAfterMs: Long = Long.MAX_VALUE, byStatus: (Int) -> F? = { null }): F? = when {
        reply.why == Why.OFF -> null
        reply.why == Why.OFFLINE -> offline
        else -> byStatus(reply.status) ?: if (reply.status == 429 || (reply.retryAfterSec ?: 0) * 1000 > slowAfterMs) slowDown else noAnswer
    }
}

/** How soon a service is asked again, and when what it said is too old: one shape, each service's own numbers. */
object Pace {
    const val MIN_MS = 60_000L

    /**
     * After a try that found no connection. Without a network nothing goes out, so that check is made every minute; a
     * network that doesn't reach the service (a sign-in page, a name that can't be found) does cost a try each
     * time, so it is tried less and less often, to [mostMs] at the longest.
     */
    fun offline(misses: Int, mostMs: Long): Long = if (misses <= 1) MIN_MS else minOf(mostMs, MIN_MS shl (misses - 1).coerceAtMost(4))

    /** After [failure]: [retryMs] for no answer, [slowMs] after being told to slow down (or as long as the service said), and [offline]'s pace without a connection. */
    fun after(failure: Failure, misses: Int, retryAfterSec: Long, retryMs: Long, slowMs: Long): Long = when (failure) {
        Failure.NO_ANSWER -> retryMs
        Failure.SLOW_DOWN -> maxOf(slowMs, retryAfterSec * 1000)
        Failure.OFFLINE -> offline(misses, retryMs)
    }

    /**
     * No numbers worth showing: there are none ([fetchedAt] 0), or they were read [limitMs] ago or more, by either of two
     * clocks; one saying so is enough. The wall clock ([now]) holds across a restart of the device but can be set; the time
     * since boot ([up]) can't be set but begins again with every boot, so it counts only where the reading has its mark
     * ([fetchedUp]), and after a new boot it can only have run past that if that long has really passed.
     */
    fun old(fetchedAt: Long, fetchedUp: Long, now: Long, up: Long, limitMs: Long): Boolean =
        fetchedAt == 0L || now - fetchedAt >= limitMs || (fetchedUp > 0 && up - fetchedUp >= limitMs)
}

/**
 * A debug build's staged sample of a type: shown by the type's first item, in place of what the
 * service said, so that what a failure looks like can be seen on a device without one.
 */
class Staged<V : Any>(private val type: String) {
    @Volatile var value: V? = null

    private fun first(): ItemConfig? = Store.config.value.items.firstOrNull { it.type == type }

    /** The sample, if [item] is the one showing it. */
    fun of(item: ItemConfig): V? = value?.takeIf { first()?.id == item.id }
}

/**
 * The item's menu opened, as every online item has it: nothing loaded yet is loaded; a value whose last try went well
 * is asked again if older than [floorMs]; one whose last try failed keeps to its failure's pace. Main thread.
 */
fun <K : Any, V : Any> Refresher<K, V>.menuOpened(key: K, floorMs: Long, failed: (V) -> Boolean) {
    val v = peek(key)
    if (v == null) want(key) else if (!failed(v)) refresh(key, floorMs = floorMs)
}

/**
 * A reading too old to show by the wall clock, which can be set, while the loader counts by the time since boot: asked for
 * now rather than left saying "Loading…" while nothing loads. One whose last try failed keeps to its pace. Main thread.
 */
fun <K : Any, V : Any> Refresher<K, V>.askIfOld(key: K, floorMs: Long, old: (V) -> Boolean, failed: (V) -> Boolean) {
    val v = peek(key) ?: return
    if (!failed(v) && old(v) && !loading(key)) refresh(key, floorMs = floorMs)
}
