package com.meridian11.books.bentobar.items

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meridian11.books.bentobar.items.LinkRules.Link

/**
 * The active network's link (addresses, gateway, DNS, Private DNS) for the Network menu's Addresses section, read
 * from ConnectivityManager. Read once when a Network item goes live, then kept by a callback on the default
 * network: nothing polls, and no callback exists while no such item is live. A test can stage a sample (debug
 * builds: `./bento debug network stage wifi`), shown instead of Android's until `network off`.
 */
object LinkWatch {
    /** Android's link as last reported; null: no network, or no Network item is live. Written on the main thread only. */
    @Volatile private var link: Link? = null
    /** What the test hook staged, shown instead of Android's until `network off`; null: nothing staged. */
    @Volatile private var staged: LinkSamples.Sample? = null
    /** The registered callback; null while nothing is followed. Main thread only. */
    private var callback: ConnectivityManager.NetworkCallback? = null
    /** A Network item is live (between [live] and [idle]). Main thread only. */
    private var following = false

    /**
     * The link as the menu shows it: the staged sample's, else Android's as last reported. Where the callback could not
     * be registered, Android's is read each time instead, so a menu never shows a network that has gone.
     */
    fun current(): Link? {
        val sample = staged
        if (sample != null) return sample.link
        return if (following && callback == null) read() else link
    }

    /** Android's link now; null for none, or where Android can't say. */
    private fun read(): Link? = runCatching {
        val cm = Env.app.getSystemService(ConnectivityManager::class.java)
        cm?.activeNetwork?.let(cm::getLinkProperties)?.let(::of)
    }.getOrNull()

    /**
     * A Network item is live: read the link now, then follow the default network. Read now because the callback's
     * first report comes a moment after it is registered, and a menu that opens before it would have no section.
     * Main thread.
     */
    fun live() {
        if (callback != null) return
        following = true
        val cm = Env.app.getSystemService(ConnectivityManager::class.java) ?: return
        link = read()
        val cb = object : ConnectivityManager.NetworkCallback() {
            // Android calls this on the main thread, where a throw would end the service: it must never throw.
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                link = runCatching { of(properties) }.getOrNull()
            }
            // Told only when no network takes the lost one's place: a change of networks comes as the new one's link.
            override fun onLost(network: Network) { link = null }
        }
        // On the main thread, as live() and idle() are: Android's own callback thread could write after idle() forgot the link.
        runCatching { cm.registerDefaultNetworkCallback(cb, Handler(Looper.getMainLooper())) }
            .onSuccess { callback = cb }
            .onFailure { Log.w(Env.TAG, "link: can't follow the network: ${it.javaClass.simpleName}") }
    }

    /** No Network item is live any more: the link is forgotten, and the callback goes. Main thread. */
    fun idle() {
        // Before the return below: what live() read is forgotten even where the registration failed.
        following = false
        link = null
        val cb = callback ?: return
        callback = null
        runCatching { Env.app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    /**
     * Android's link as the rules take it, every address as its text. Only a default route's gateway counts: the
     * other routes are the link's own subnets. A tunnel's default route has none, and `hasGateway` leaves out the
     * "any" address that stands for that. The Private DNS host is null in the automatic mode.
     */
    private fun of(properties: LinkProperties): Link = Link(
        addresses = properties.linkAddresses.mapNotNull { it.address.hostAddress },
        gateways = properties.routes.filter { it.isDefaultRoute && it.hasGateway() }.mapNotNull { it.gateway?.hostAddress },
        dns = properties.dnsServers.mapNotNull { it.hostAddress },
        privateDns = properties.isPrivateDnsActive,
        privateDnsHost = properties.privateDnsServerName,
    )

    /**
     * The test hook (debug builds): `stage wifi|ethernet|vpn|ipv6|offline` shows a sample's rows, `off` ends it.
     * Without a word: which rows the menu has now, and never an address.
     */
    fun debug(args: List<String>): String? {
        when (args.firstOrNull()) {
            null -> return line()
            "stage" -> staged = args.getOrNull(1)?.let(LinkSamples::of) ?: return null
            "off" -> staged = null
            else -> return null
        }
        Ticker.refresh()
        return line()
    }

    private fun line(): String {
        val sample = staged
        return (if (sample != null) "${sample.name} (staged): " else "android: ") + LinkRules.present(current())
    }
}
