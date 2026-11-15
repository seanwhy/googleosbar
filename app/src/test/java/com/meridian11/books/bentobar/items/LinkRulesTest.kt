package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.LinkRules.Field
import com.meridian11.books.bentobar.items.LinkRules.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Addresses section of the Network menu: which rows a link gets and what each one says, in the app's own words.
 * Addresses are written as Android hands them over: IPv6 in its short form, a zone after "%", no prefix length.
 */
class LinkRulesTest {
    private val texts = StringFiles.read(listOf("strings.xml", "strings_network.xml"))
    private val words = LinkRules.Words(on = texts.strings.getValue("common_on"),
        off = texts.strings.getValue("network_link_private_dns_unused"))

    private fun rows(link: Link?) = LinkRules.rows(link, words).map { it.field to it.value }

    @Test fun theFirstIpv4AddressIsTheRow() {
        assertEquals("192.168.1.42", LinkRules.ipv4(listOf("fe80::1:2:3:4%wlan0", "192.168.1.42", "10.0.0.5")))
        assertNull(LinkRules.ipv4(listOf("fe80::1:2:3:4%wlan0")))
    }

    @Test fun ipv6SkipsTheLinkLocalAndTheLoopback() {
        assertNull(LinkRules.ipv6(listOf("fe80::1c2d:3e4f:5a6b:7c8d%wlan0", "febf::1%wlan0", "::1", "192.168.1.42")))
    }

    @Test fun ipv6PrefersAGlobalAddressToALocalOne() {
        assertEquals("2001:db8::7f3e", LinkRules.ipv6(listOf("fd12:3456:789a::1", "2001:db8::7f3e")))
        assertEquals("fd12:3456:789a::1", LinkRules.ipv6(listOf("fe80::1%wlan0", "fd12:3456:789a::1")))
        assertEquals("fec0::1a2b:3c4d:5e6f:202a", LinkRules.ipv6(listOf("fe80::1:2:3:4%wlan0", "fec0::1a2b:3c4d:5e6f:202a")))
    }

    @Test fun aLongIpv6IsShortenedToItsEndsAndAnythingElseIsWhole() {
        assertEquals("2a02:c7c:…:7f3e", LinkRules.short("2a02:c7c:1234:5600:abcd:ef01:2345:7f3e"))
        assertEquals("fec0:0:…:202a", LinkRules.short("fec0::1a2b:3c4d:5e6f:202a"))
        assertEquals("2001:db8::7f3e", LinkRules.short("2001:db8::7f3e"))
        assertEquals("192.168.1.42", LinkRules.short("192.168.1.42"))
        assertEquals("255.255.255.255", LinkRules.short("255.255.255.255"))
    }

    @Test fun theGatewayIsTheIpv4OneAheadOfAnIpv6One() {
        assertEquals("192.168.1.1", LinkRules.gateway(listOf("fe80::1%wlan0", "192.168.1.1")))
        assertEquals("fe80::1", LinkRules.gateway(listOf("fe80::1%wlan0")))
        assertNull(LinkRules.gateway(emptyList()))
    }

    @Test fun aLongIpv6GatewayIsShortenedToo() {
        val link = Link(addresses = listOf("2001:db8::2"), gateways = listOf("fe80::1a2b:3c4d:5e6f:7a8b%wlan0"))
        assertTrue((Field.GATEWAY to "fe80:0:…:7a8b") in rows(link))
    }

    @Test fun dnsShowsTwoServersAtMostAndShortensALongOne() {
        assertEquals("10.0.0.1 · 2001:4860:…:8888", LinkRules.dns(listOf("10.0.0.1", "2001:4860:4860::8888", "1.1.1.1")))
        assertEquals("192.168.1.1 · 2001:4860:…:8888", LinkRules.dns(listOf("192.168.1.1", "2001:4860:4860::8888")))
        assertEquals("10.0.0.1", LinkRules.dns(listOf("10.0.0.1")))
        assertNull(LinkRules.dns(emptyList()))
    }

    @Test fun dnsShowsTheFirstServerAloneWhereTwoDontFitTheRow() {
        // Two of these pushed the label into a column of letters.
        assertEquals("2001:4860:…:8888", LinkRules.dns(listOf("2001:4860:4860::8888", "2001:4860:4860::8844")))
        assertEquals("192.168.100.100", LinkRules.dns(listOf("192.168.100.100", "192.168.100.101")))
    }

    @Test fun privateDnsReadsNotInUseOnOrOnWithItsHost() {
        val link = Link(addresses = listOf("10.0.0.2"))
        assertTrue((Field.PRIVATE_DNS to "Not in use") in rows(link))
        assertTrue((Field.PRIVATE_DNS to "On") in rows(link.copy(privateDns = true)))
        assertTrue((Field.PRIVATE_DNS to "On · dns.google") in rows(link.copy(privateDns = true, privateDnsHost = "dns.google")))
    }

    @Test fun aLongPrivateDnsHostKeepsItsLastLabelsWithinTheDnsRowsRoom() {
        val on = Link(addresses = listOf("10.0.0.2"), privateDns = true)
        // Cloudflare's own host for Android, 32 characters: whole, the label broke into a column of letters.
        assertTrue((Field.PRIVATE_DNS to "On · …cloudflare-dns.com") in rows(on.copy(privateDnsHost = "1dot1dot1dot1.cloudflare-dns.com")))
        // A host that names an account loses that part first.
        assertEquals("…dns.nextdns.io", LinkRules.host("abc123def456.dns.nextdns.io", 20))
        assertEquals("dns.google", LinkRules.host("dns.google", 25))
        // A last label longer than the room is cut to its end.
        assertEquals("…hijklmnopqrstuvwxyz", LinkRules.host("abcdefghijklmnopqrstuvwxyz", 20))
        // Whatever the host, the row's value keeps to the DNS row's thirty characters.
        val long = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + ".example"
        for (host in listOf(long, "x".repeat(253), "1dot1dot1dot1.cloudflare-dns.com", "dns.adguard-dns.com")) {
            val value = rows(on.copy(privateDnsHost = host)).single { it.first == Field.PRIVATE_DNS }.second
            assertTrue("$value is ${value.length} characters", value.length <= 30)
        }
    }

    @Test fun noLinkAndALinkWithoutAnAddressHaveNoRows() {
        assertTrue(rows(null).isEmpty())
        assertTrue(rows(Link(dns = listOf("1.1.1.1"), gateways = listOf("10.0.0.1"))).isEmpty())
    }

    @Test fun theRowsComeInTheMenusOrder() {
        val wifi = LinkSamples.of("wifi")!!.link
        assertEquals(listOf(Field.IPV4, Field.IPV6, Field.GATEWAY, Field.DNS, Field.PRIVATE_DNS), rows(wifi).map { it.first })
        assertEquals(listOf("192.168.1.42", "2001:db8:…:7f3e", "192.168.1.1", "192.168.1.1 · 2001:4860:…:8888", "Not in use"), rows(wifi).map { it.second })
    }

    @Test fun aTunnelHasNoGatewayRow() {
        val vpn = LinkSamples.of("vpn")!!.link
        assertEquals(listOf(Field.IPV4, Field.DNS, Field.PRIVATE_DNS), rows(vpn).map { it.first })
        assertTrue((Field.PRIVATE_DNS to "On · …cloudflare-dns.com") in rows(vpn))
    }

    @Test fun aNetworkWithIpv6AloneHasNoIpv4Row() {
        val v6 = LinkSamples.of("ipv6")!!.link
        assertEquals(listOf(Field.IPV6 to "2001:db8:…:7f3e", Field.GATEWAY to "fe80:0:…:7a8b", Field.DNS to "2001:4860:…:8888",
            Field.PRIVATE_DNS to "On"), rows(v6))
    }

    @Test fun everySampleHasANameAndOfflineIsNoLink() {
        for (name in LinkSamples.names) assertNotNull("$name is no sample", LinkSamples.of(name))
        assertNull(LinkSamples.of("offline")!!.link)
        assertNotNull(LinkSamples.of("ethernet")!!.link)
        assertNull(LinkSamples.of("moon"))
    }

    @Test fun theDebugLineNamesTheRowsAndNoAddress() {
        assertEquals("ipv4 ipv6 gateway dns private_dns=off", LinkRules.present(LinkSamples.of("wifi")!!.link))
        assertEquals("ipv4 dns private_dns=on", LinkRules.present(LinkSamples.of("vpn")!!.link))
        assertEquals("no link", LinkRules.present(null))
        // Whatever the link: no dot and no colon, so nothing of an address or a host.
        for (name in LinkSamples.names) assertFalse(name, LinkRules.present(LinkSamples.of(name)!!.link).any { it == '.' || it == ':' })
    }
}
