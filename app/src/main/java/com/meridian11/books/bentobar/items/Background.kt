package com.meridian11.books.bentobar.items

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meridian11.books.bentobar.BuildConfig
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.util.Now
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The app's wiring for [Refresher] and [Ask]: each gets a thread of its own that exists only while
 * there is work, results arrive on the main thread, the clock is [Now], and a new snapshot makes the
 * ticker compute the items of its type again, so the bar and an open menu show it at once.
 */
object Background {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** What to forget when a service is switched off: the snapshots of the refreshers and the answers of the asks made for it. */
    private val forServices = ArrayList<Pair<Online.Service, () -> Unit>>()
    private val threads = HashMap<String, Executor>()

    /** The one thread named "BentoBar-[name]", lowest priority, started with the first task and gone after half a minute without one. */
    fun executor(name: String): Executor = synchronized(threads) {
        threads.getOrPut(name) {
            ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
                Thread(r, "BentoBar-$name").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
            }
        }
    }

    /**
     * [type]: the id of the item type the work is for ("weather"). It names the thread, and what
     * comes in recomputes that type's items (`Ticker.refresh(type)`: no sampler runs for it).
     * Nothing is loaded while nothing shows items, nor, with [service], while that service is
     * switched off: a state that is computed all the same asks for nothing.
     */
    fun wiring(type: String, service: Online.Service? = null, threadName: String = type) = Refresher.Wiring(executor(threadName), { main.post(it) }, Now::elapsed,
        changed = { Ticker.refresh(type) }, mayLoad = { Ticker.running && (service == null || Online.on(service)) },
        // Only the class of what was thrown, and only in a debug build: its message could hold an address, and with it a key.
        report = { kind -> if (BuildConfig.DEBUG) Log.w(Env.TAG, "a load for $type threw $kind") })

    /**
     * A [Refresher] for the item type with the id [type]. With [service], its snapshots are forgotten
     * the moment that service is switched off or loses its key (before the type's own
     * `forgetFetched` runs).
     */
    fun <K : Any, V : Any> refresher(
        type: String,
        service: Online.Service? = null,
        every: (key: K, value: V) -> Long?,
        restore: ((key: K) -> Refresher.Restored<V>?)? = null,
        load: (key: K, last: V?) -> V?,
    ): Refresher<K, V> = Refresher(wiring(type, service), every, restore, load).also { r ->
        if (service != null) synchronized(forServices) { forServices += service to { r.forget() } }
    }

    /**
     * An [Ask] for the item type with the id [type]. With [service], its answer is cleared, and one
     * on its way dropped, the moment that service is switched off or loses its key.
     */
    fun <Q : Any, A : Any> ask(type: String, service: Online.Service? = null, work: (Q) -> A): Ask<Q, A> = Ask(wiring(type, threadName = "$type-ask"), work).also { a ->
        if (service != null) synchronized(forServices) { forServices += service to { a.clear() } }
    }

    /** [service] was switched off: every refresher and every ask made for it forgets what it has. Main thread. */
    internal fun forget(service: Online.Service) {
        val mine = synchronized(forServices) { forServices.filter { it.first == service }.map { it.second } }
        mine.forEach { it() }
    }

    /** Runs [block] on the main thread after [ms]: for what has to wait out a moment before it is done for good. */
    fun later(ms: Long, block: () -> Unit) { main.postDelayed(block, ms) }

    /** Runs [block] on the main thread: now if this is it, else posted. */
    fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
