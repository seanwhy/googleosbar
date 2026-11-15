package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.util.Sym

/**
 * Pure rules for the Connection item: what Android says of the network the device uses by default, in one word, its
 * colour and its glyph, and when that is worth showing. The Android side is in [ConnectionItem]; nothing here or there
 * asks anything of the network itself.
 */
object ConnectionRules {
    enum class Link { NONE, WIFI, ETHERNET, MOBILE, OTHER }

    /**
     * The default network as Android describes it. [validated]: Android reached the internet through it. [portal]: it
     * wants a sign-in page first. [vpn]: a VPN carries it. [metered]: Android counts it as paid for by use.
     */
    data class Net(val link: Link, val validated: Boolean, val portal: Boolean, val vpn: Boolean, val metered: Boolean)

    enum class Says { OFFLINE, SIGN_IN, NO_INTERNET, VPN, METERED, FINE }

    /** The first that holds: no network, a sign-in page, no internet, a VPN, metered; else fine. */
    fun says(net: Net?): Says = when {
        net == null || net.link == Link.NONE -> Says.OFFLINE
        net.portal -> Says.SIGN_IN
        !net.validated -> Says.NO_INTERNET
        net.vpn -> Says.VPN
        net.metered -> Says.METERED
        else -> Says.FINE
    }

    fun tone(says: Says): Tone = when (says) {
        Says.OFFLINE, Says.NO_INTERNET -> Tone.ALERT
        Says.SIGN_IN -> Tone.WARN
        Says.VPN -> Tone.ACCENT
        Says.METERED, Says.FINE -> Tone.NORMAL
    }

    /** What is wrong, where a glyph says it; else the link's own. No internet is the crossed Wi-Fi on Wi-Fi only. */
    fun icon(net: Net?, says: Says): String = when {
        says == Says.OFFLINE -> Sym.WIFI_OFF
        says == Says.SIGN_IN -> Sym.CAPTIVE_PORTAL
        says == Says.NO_INTERNET && net?.link == Link.WIFI -> Sym.SIGNAL_WIFI_BAD
        says == Says.VPN -> Sym.VPN_LOCK
        else -> when (net?.link) {
            Link.WIFI -> Sym.WIFI
            Link.ETHERNET -> Sym.SETTINGS_ETHERNET
            Link.MOBILE -> Sym.SIGNAL_CELLULAR_ALT
            else -> Sym.LAN
        }
    }

    /** Worth showing: anything but fine or metered; a VPN only while it [vpnCounts] (the item's option). */
    fun active(says: Says, vpnCounts: Boolean): Boolean = when (says) {
        Says.FINE, Says.METERED -> false
        Says.VPN -> vpnCounts
        else -> true
    }

    /** The link by its transports, a cable first: a VPN's network carries the transports of the one under it. */
    fun link(wifi: Boolean, ethernet: Boolean, mobile: Boolean): Link = when {
        ethernet -> Link.ETHERNET
        wifi -> Link.WIFI
        mobile -> Link.MOBILE
        else -> Link.OTHER
    }

    /** What the debug hook stages (`connection stage NAME`); null for a name it doesn't know. */
    fun staged(name: String?): Net? = when (name) {
        "signin" -> Net(Link.WIFI, validated = false, portal = true, vpn = false, metered = false)
        "nointernet" -> Net(Link.WIFI, validated = false, portal = false, vpn = false, metered = false)
        "vpn" -> Net(Link.WIFI, validated = true, portal = false, vpn = true, metered = false)
        "metered" -> Net(Link.MOBILE, validated = true, portal = false, vpn = false, metered = true)
        "fine" -> Net(Link.ETHERNET, validated = true, portal = false, vpn = false, metered = false)
        "offline" -> Net(Link.NONE, validated = false, portal = false, vpn = false, metered = false)
        else -> null
    }
}
