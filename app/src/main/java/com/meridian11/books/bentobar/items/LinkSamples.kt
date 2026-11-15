package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.LinkRules.Link

/**
 * Made-up links for testing on a device (debug builds: `./bento debug network stage wifi`), one for each look of the
 * Addresses section. Their addresses are the documentation ranges', private ones and public resolvers'; none is any
 * device's. Pure, so a unit test checks each one's rows.
 */
object LinkSamples {
    val names = listOf("wifi", "ethernet", "vpn", "ipv6", "offline")

    /** [link]: what is shown instead of the real one; null is no network. */
    class Sample(val name: String, val link: Link?)

    /** The sample called [name]; null for a name that is none. */
    fun of(name: String): Sample? = when (name) {
        // A home network with both address families, Private DNS not in use, and the widest pair of resolvers the row has room for.
        "wifi" -> Sample(name, Link(
            addresses = listOf("192.168.1.42", "fe80::1c2d:3e4f:5a6b:7c8d%wlan0", "2001:db8:85a3:0:8a2e:370:7334:7f3e"),
            gateways = listOf("192.168.1.1", "fe80::1%wlan0"),
            dns = listOf("192.168.1.1", "2001:4860:4860::8888"),
        ))
        // IPv4 alone, two resolvers, Private DNS in the automatic mode.
        "ethernet" -> Sample(name, Link(
            addresses = listOf("10.0.0.23"),
            gateways = listOf("10.0.0.1"),
            dns = listOf("10.0.0.1", "1.1.1.1"),
            privateDns = true,
        ))
        // A tunnel: no gateway on its default route, the VPN's resolver, Private DNS set to a host too long for its row.
        "vpn" -> Sample(name, Link(
            addresses = listOf("10.8.0.2"),
            dns = listOf("10.8.0.1"),
            privateDns = true,
            privateDnsHost = "1dot1dot1dot1.cloudflare-dns.com",
        ))
        // IPv6 alone: the widest values there are, a long gateway and two long resolvers, of which the row has room for one.
        "ipv6" -> Sample(name, Link(
            addresses = listOf("fe80::1c2d:3e4f:5a6b:7c8d%wlan0", "2001:db8:85a3:0:8a2e:370:7334:7f3e"),
            gateways = listOf("fe80::1a2b:3c4d:5e6f:7a8b%wlan0"),
            dns = listOf("2001:4860:4860::8888", "2001:4860:4860::8844"),
            privateDns = true,
        ))
        "offline" -> Sample(name, null)
        else -> null
    }
}
