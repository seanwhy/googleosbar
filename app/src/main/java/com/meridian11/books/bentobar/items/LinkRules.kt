package com.meridian11.books.bentobar.items

/**
 * The Addresses section of the Network menu: which rows a link gets and what each one says. Unit-tested
 * (`LinkRulesTest`).
 */
object LinkRules {
    /**
     * The active network's link as the reader hands it over, every address as Android's text for it, so that the rules
     * below need no Android: "10.0.2.16", and IPv6 in its short form with a link-local one's zone behind it
     * ("fe80::1c2d:3e4f:5a6b:7c8d%wlan0"). [gateways]: those of its default routes. [dns]: its resolvers.
     * [privateDns]: Private DNS is in use on this link (Android's `isPrivateDnsActive`: in the automatic mode it is not
     * while the network's resolver can't do it); [privateDnsHost]: the host it was set to, null in the automatic mode.
     * Nested here so that it is never taken for the Connection item's `ConnectionRules.Link`.
     */
    data class Link(
        val addresses: List<String> = emptyList(),
        val gateways: List<String> = emptyList(),
        val dns: List<String> = emptyList(),
        val privateDns: Boolean = false,
        val privateDnsHost: String? = null,
    )

    /** The rows, in the menu's order. */
    enum class Field { IPV4, IPV6, GATEWAY, DNS, PRIVATE_DNS }

    data class Row(val field: Field, val value: String)

    /** The words from the resources: Private DNS's On, and what it says while it is not in use. */
    class Words(val on: String, val off: String)

    /** Between the parts of one value, as the menu's subtitle joins its words. */
    const val SEP = " · "

    /**
     * The DNS row's value has room for this many characters beside its label: the widest pair seen to fit on the
     * card, an IPv4 resolver and a shortened IPv6 one. Two shortened IPv6 resolvers are 35, and the label broke into
     * a column of letters. A count of characters, not a measure of the text: a change to the card's width or to the
     * row's type needs a new look at it (`./bento debug network stage wifi`, then `stage ipv6` and `stage vpn`). The
     * Private DNS row keeps to the same room.
     */
    private const val DNS_ROW = 30

    /**
     * The rows for [link]: none for no link, or one without an address yet, and the section is left out. Private DNS
     * says whether this link's lookups are encrypted, so its row is there with any address.
     */
    fun rows(link: Link?, words: Words): List<Row> {
        if (link == null) return emptyList()
        val v4 = ipv4(link.addresses)
        val v6 = ipv6(link.addresses)
        if (v4 == null && v6 == null) return emptyList()
        return listOfNotNull(
            v4?.let { Row(Field.IPV4, it) },
            v6?.let { Row(Field.IPV6, short(it)) },
            gateway(link.gateways)?.let { Row(Field.GATEWAY, short(it)) },
            dns(link.dns)?.let { Row(Field.DNS, it) },
            Row(Field.PRIVATE_DNS, privateDns(link, words)),
        )
    }

    /** The first IPv4 address. */
    fun ipv4(addresses: List<String>): String? = addresses.firstOrNull { ':' !in it }

    /**
     * The first IPv6 address that reaches beyond the link: a global one (2000::/3) ahead of a site's own, never the
     * link-local one (fe80::/10) or the loopback. Told apart by the text's first digits, which Android writes in
     * lower case and without a leading zero.
     */
    fun ipv6(addresses: List<String>): String? {
        val beyond = addresses.map(::plain).filter { ':' in it && it != "::1" && it.take(3) !in LINK_LOCAL }
        return beyond.firstOrNull { it[0] == '2' || it[0] == '3' } ?: beyond.firstOrNull()
    }

    /**
     * The gateway: an IPv4 one ahead of an IPv6 one, which is the router's link-local address and tells a reader
     * little; null where the default route has none (a tunnel).
     */
    fun gateway(gateways: List<String>): String? = (gateways.firstOrNull { ':' !in it } ?: gateways.firstOrNull())?.let(::plain)

    /** The DNS row: the first resolver, and the second with it where both fit the row; null for none. */
    fun dns(servers: List<String>): String? {
        val shown = servers.take(2).map { short(plain(it)) }
        if (shown.isEmpty()) return null
        return shown.joinToString(SEP).takeIf { it.length <= DNS_ROW } ?: shown.first()
    }

    /**
     * Private DNS as the row says it: not in use, On (the automatic mode), or On with the host it was set to, as
     * much of it as the row has room for ([host]).
     */
    fun privateDns(link: Link, words: Words): String {
        val host = link.privateDnsHost
        return when {
            !link.privateDns -> words.off
            host.isNullOrBlank() -> words.on
            else -> words.on + SEP + host(host, DNS_ROW - words.on.length - SEP.length)
        }
    }

    /**
     * A host name in [room] characters: whole where it fits, else its last labels behind "…"
     * ("…cloudflare-dns.com"), so that the provider stays readable; a provider's host that names an account
     * ("abc123.dns.nextdns.io") then also loses that part first. A last label longer than the room is cut to its end.
     */
    fun host(name: String, room: Int): String {
        if (name.length <= room) return name
        val labels = name.split('.')
        for (from in 1 until labels.size) {
            val tail = labels.drop(from).joinToString(".")
            if (tail.length + 1 <= room) return "…$tail"
        }
        return "…" + name.takeLast((room - 1).coerceAtLeast(1))
    }

    /** An address without its zone ("%wlan0"). */
    fun plain(address: String): String = address.substringBefore('%')

    /**
     * An address as a row has room for it: one of more than sixteen characters, which only an IPv6 one is (the
     * longest IPv4 one is fifteen), as its first two groups, "…" and its last ("2a02:c7c:…:7f3e"). A group folded
     * into "::" reads 0. What comes out is sixteen characters at most, so every shortened value fits where a whole
     * one does.
     */
    fun short(address: String): String {
        if (address.length <= 16) return address
        // Over sixteen characters there are four groups or more, so the second one is there.
        val groups = address.split(':').map { it.ifEmpty { "0" } }
        return "${groups[0]}:${groups[1]}:…:${groups.last()}"
    }

    /** A line for the debug hook: which rows the link has, and never an address or a host. */
    fun present(link: Link?): String {
        if (link == null) return "no link"
        return rows(link, Words("", "")).joinToString(" ") {
            if (it.field == Field.PRIVATE_DNS) "private_dns=" + (if (link.privateDns) "on" else "off") else it.field.name.lowercase()
        }.ifEmpty { "no address" }
    }

    /** How the text of a link-local address (fe80::/10, so fe80 to febf) starts. */
    private val LINK_LOCAL = setOf("fe8", "fe9", "fea", "feb")
}
