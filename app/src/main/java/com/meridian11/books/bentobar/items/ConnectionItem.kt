package com.meridian11.books.bentobar.items

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.items.ConnectionRules.Link
import com.meridian11.books.bentobar.items.ConnectionRules.Net
import com.meridian11.books.bentobar.items.ConnectionRules.Says
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Sym

/**
 * Connection: says in a word when the network needs a look: offline, a sign-in page, no internet, a VPN, a metered
 * network. It reads only what Android already says about the network the device uses by default (the permission to read
 * the network's state, which BentoBar has), and asks nothing of the network itself. The Wi-Fi's name is not shown: Android
 * gives it only to apps with location access.
 *
 * While an item is live, Android tells it each change of the default network; idle, nothing is listened to. The rules are
 * [ConnectionRules].
 */
object ConnectionItem : ItemType("connection", R.string.item_connection_title, Sym.LAN, R.string.item_connection_desc) {
    override val group = ItemGroup.SYSTEM
    override val canBeActive = true
    override val addsWhenActive = true

    /** A VPN counts as something to show. On is the default, stored as no key. */
    private val vpnCounts = Opt.bool("vpn", true)

    override val trigger = object : Trigger(R.string.trigger_connection, R.string.trigger_connection_short) {
        override fun sentenceRes(item: ItemConfig) = if (vpnCounts.of(item)) R.string.trigger_connection else R.string.trigger_connection_no_vpn
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** What Android last said of the default network while listening; null: there is none. */
    @Volatile private var heard: Net? = null
    /** The test hook's network, shown instead of the real one; null: none staged. */
    @Volatile private var staged: Net? = null
    private var listening = false

    /** The default network [heard] describes; a loss of any other network changes nothing. */
    @Volatile private var current: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            current = network
            heard(net(networkCapabilities))
        }
        // Android says it only for the last default network it named; a late one for an earlier network must not read
        // as offline while the new one carries traffic.
        override fun onLost(network: Network) {
            if (current != null && network != current) return
            current = null
            heard(null)
        }
    }

    /** From Android's callback, on the main thread: it must never throw. */
    private fun heard(net: Net?) {
        heard = net
        try { Ticker.refresh(type) } catch (e: Exception) { Log.w(Env.TAG, "connection: ${e.javaClass.simpleName}") }
    }

    private fun cm(): ConnectivityManager? = Env.app.getSystemService(ConnectivityManager::class.java)

    private fun net(c: NetworkCapabilities): Net = Net(
        link = ConnectionRules.link(wifi = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            ethernet = c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET), mobile = c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)),
        validated = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        portal = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        vpn = c.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        // As Android counts it for its own data warnings: a network that is free for now is not metered.
        metered = !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED),
    )

    /** The default network as Android describes it right now; null when there is none or Android won't say. */
    private fun read(): Net? = runCatching {
        val cm = cm() ?: return null
        cm.activeNetwork?.let { n -> cm.getNetworkCapabilities(n)?.let(::net) }
    }.getOrNull()

    override fun onLive() {
        current = runCatching { cm()?.activeNetwork }.getOrNull()
        heard = read()
        val cm = cm()
        // Android answers the registration with the default network as it is, if there is one, and then with each change.
        if (cm != null && !listening) listening = runCatching { cm.registerDefaultNetworkCallback(callback, main) }.isSuccess
    }

    override fun onIdle() {
        if (listening) { runCatching { cm()?.unregisterNetworkCallback(callback) }; listening = false }
        heard = null
    }

    /** What is shown: the staged network, what Android said while listening, or (nobody listening) what it says now. */
    private fun current(): Net? = staged ?: if (listening) heard else read()

    @StringRes private fun wordRes(says: Says): Int? = when (says) {
        Says.OFFLINE -> R.string.connection_offline
        Says.SIGN_IN -> R.string.connection_sign_in
        Says.NO_INTERNET -> R.string.connection_no_internet
        Says.VPN -> R.string.connection_vpn
        Says.METERED -> R.string.connection_metered
        Says.FINE -> null
    }

    @StringRes private fun sentenceRes(says: Says): Int = when (says) {
        Says.OFFLINE -> R.string.connection_says_offline
        Says.SIGN_IN -> R.string.connection_says_sign_in
        Says.NO_INTERNET -> R.string.connection_says_no_internet
        Says.VPN -> R.string.connection_says_vpn
        Says.METERED -> R.string.connection_says_metered
        Says.FINE -> R.string.connection_says_fine
    }

    @StringRes private fun linkRes(link: Link?): Int = when (link) {
        null, Link.NONE -> R.string.connection_link_none
        Link.WIFI -> R.string.connection_link_wifi
        Link.ETHERNET -> R.string.connection_link_ethernet
        Link.MOBILE -> R.string.connection_link_mobile
        Link.OTHER -> R.string.connection_link_other
    }

    override fun state(item: ItemConfig): ItemState {
        val net = current()
        val says = ConnectionRules.says(net)
        val desc = Env.str(R.string.connection_desc, Env.str(sentenceRes(says)))
        return ItemState(icon = ConnectionRules.icon(net, says), text = wordRes(says)?.let(Env::str), tone = ConnectionRules.tone(says),
            active = ConnectionRules.active(says, vpnCounts.of(item)), desc = desc, tooltip = desc)
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { _, host ->
        rememberTick()
        val net = current()
        val says = ConnectionRules.says(net)
        val alert = ConnectionRules.tone(says) == Tone.ALERT || says == Says.SIGN_IN
        val quiet = MaterialTheme.colorScheme.onSurface
        MenuCard(ConnectionRules.icon(net, says), stringResource(R.string.item_connection_title), stringResource(sentenceRes(says)),
            subtitleColor = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) {
            InfoRow(stringResource(R.string.connection_row_link), stringResource(linkRes(net?.link)))
            if (says != Says.OFFLINE && net != null) {
                InfoRow(stringResource(R.string.connection_row_internet), stringResource(when {
                    net.portal -> R.string.connection_internet_sign_in
                    net.validated -> R.string.connection_internet_works
                    else -> R.string.connection_internet_none
                }), if (net.validated && !net.portal) quiet else MaterialTheme.colorScheme.error)
                InfoRow(stringResource(R.string.connection_row_vpn), stringResource(if (net.vpn) R.string.common_on else R.string.common_off))
                InfoRow(stringResource(R.string.connection_row_metered), stringResource(if (net.metered) R.string.connection_yes else R.string.connection_no))
            }
            MenuDivider()
            MenuEntry(Sym.SETTINGS, stringResource(R.string.connection_settings)) { host.close(); Env.launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        SwitchRow(stringResource(R.string.connection_vpn_counts), vpnCounts.of(item), help = stringResource(R.string.connection_vpn_counts_help)) {
            set(item.with(vpnCounts.key, if (it) null else "false"))
        }
    }

    override val stages = listOf("stage signin", "stage nointernet", "stage vpn", "stage metered", "stage fine", "stage offline")

    override fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        null -> "staged=${staged != null} listening=$listening says=${ConnectionRules.says(current())}"
        "stage" -> ConnectionRules.staged(args.getOrNull(1))?.let { staged = it; Ticker.refresh(type); "staged ${args[1]}" }
        "off" -> { staged = null; Ticker.refresh(type); "staging off" }
        else -> null
    }
}
