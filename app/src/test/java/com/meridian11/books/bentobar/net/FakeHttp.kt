package com.meridian11.books.bentobar.net

import com.meridian11.books.bentobar.data.Online

/**
 * A service that isn't there, for the tests of anything that asks one: it answers by host and path
 * with what a test put in, and remembers what it was asked. Nothing leaves the machine.
 *
 *     FakeHttp.use { net ->
 *         net.reply(Host.OPEN_METEO, "/v1/forecast", json)
 *         val reading = WeatherLoad.load(place, null)
 *         assertEquals("47.37", net.query("latitude"))
 *     }
 */
class FakeHttp : Transport {
    /** Every request that came, in order. */
    val asked = ArrayList<Request>()
    private val replies = HashMap<String, ArrayDeque<Reply>>()

    /** The next request to [host] and [path] is answered with [text]. Several answers are given in order; the last one repeats. */
    fun reply(host: Host, path: String, text: String) = answer(host, path, Reply.Ok(text))

    /** The next request to [host] and [path] fails: no connection, a status, a timeout. */
    fun fail(host: Host, path: String, why: Why, status: Int = 0, retryAfterSec: Long? = null) =
        answer(host, path, Reply.Failed(why, status, retryAfterSec))

    private fun answer(host: Host, path: String, reply: Reply) { replies.getOrPut(host.domain + path) { ArrayDeque() }.addLast(reply) }

    /**
     * A request nobody prepared an answer for is "not found", as from a service that doesn't know the path. One the
     * real transport would refuse fails here for the same reason ([HttpTransport.address], the same code), so that a
     * test can't pass on a request that could never leave the device.
     */
    override fun get(request: Request): Reply {
        asked += request
        if (HttpTransport.address(request) == null) return Reply.Failed(Why.REFUSED, sent = false)
        val queue = replies[request.host.domain + request.path] ?: return Reply.Failed(Why.STATUS, 404)
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    /** The paths asked, in order: "/api/v9/flight", "/api/v9/routes". */
    val paths: List<String> get() = asked.map { it.path }

    /** The value of [name] in the last request's query, or null. */
    fun query(name: String): String? = asked.lastOrNull()?.query?.firstOrNull { it.first == name }?.second

    companion object {
        /**
         * Runs [test] with a fake in the network's place and the gate open (as if the service were
         * switched on, its item in the bar and the bar on screen), then closes everything again.
         */
        fun <T> use(test: (FakeHttp) -> T): T {
            val fake = FakeHttp()
            Http.clock = { System.nanoTime() / 1_000_000 }
            Http.transport = fake
            // As the app wires it: the switch and key are `Online`'s, and no item's code ever holds a key.
            Http.key = { host -> Online.key(Online.serviceOf(host)).ifEmpty { null } }
            Http.allowed = { true }
            Http.connected = { true }
            Http.onMain = { false }
            Http.log = {}
            Http.resetCounts()
            try {
                return test(fake)
            } finally {
                Http.clock = { System.nanoTime() / 1_000_000 }
                Http.transport = HttpTransport()
                Http.key = { null }
                Http.allowed = { false }
                Http.connected = { true }
                Http.resetCounts()
            }
        }
    }
}
