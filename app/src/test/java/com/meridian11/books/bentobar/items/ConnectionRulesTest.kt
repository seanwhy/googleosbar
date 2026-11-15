package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.ConnectionRules.Link
import com.meridian11.books.bentobar.items.ConnectionRules.Net
import com.meridian11.books.bentobar.items.ConnectionRules.Says
import com.meridian11.books.bentobar.util.Sym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRulesTest {
    private fun net(link: Link = Link.WIFI, validated: Boolean = true, portal: Boolean = false, vpn: Boolean = false, metered: Boolean = false) =
        Net(link, validated, portal, vpn, metered)

    @Test fun whatItSaysInOrder() {
        assertEquals(Says.OFFLINE, ConnectionRules.says(null))
        assertEquals("no link is no connection, whatever else it claims", Says.OFFLINE, ConnectionRules.says(net(Link.NONE)))
        assertEquals("a portal on a VPN asks for the sign-in first", Says.SIGN_IN, ConnectionRules.says(net(validated = false, portal = true, vpn = true, metered = true)))
        assertEquals(Says.NO_INTERNET, ConnectionRules.says(net(validated = false, vpn = true, metered = true)))
        assertEquals(Says.VPN, ConnectionRules.says(net(vpn = true, metered = true)))
        assertEquals(Says.METERED, ConnectionRules.says(net(Link.MOBILE, metered = true)))
        assertEquals(Says.FINE, ConnectionRules.says(net(Link.ETHERNET)))
    }

    @Test fun fineAndMeteredAreQuiet() {
        for (vpnCounts in listOf(true, false)) {
            assertFalse(ConnectionRules.active(Says.FINE, vpnCounts))
            assertFalse(ConnectionRules.active(Says.METERED, vpnCounts))
            for (says in listOf(Says.OFFLINE, Says.SIGN_IN, Says.NO_INTERNET)) assertTrue("$says", ConnectionRules.active(says, vpnCounts))
        }
    }

    @Test fun aVpnIsQuietWhenToldSo() {
        assertTrue(ConnectionRules.active(Says.VPN, vpnCounts = true))
        assertFalse(ConnectionRules.active(Says.VPN, vpnCounts = false))
    }

    @Test fun theTonesGoFromAlertToNormal() {
        assertEquals(Tone.ALERT, ConnectionRules.tone(Says.OFFLINE))
        assertEquals(Tone.ALERT, ConnectionRules.tone(Says.NO_INTERNET))
        assertEquals(Tone.WARN, ConnectionRules.tone(Says.SIGN_IN))
        assertEquals(Tone.ACCENT, ConnectionRules.tone(Says.VPN))
        assertEquals(Tone.NORMAL, ConnectionRules.tone(Says.METERED))
        assertEquals(Tone.NORMAL, ConnectionRules.tone(Says.FINE))
    }

    @Test fun theGlyphSaysWhatIsWrongElseTheLink() {
        assertEquals(Sym.WIFI_OFF, ConnectionRules.icon(null, Says.OFFLINE))
        assertEquals(Sym.CAPTIVE_PORTAL, ConnectionRules.icon(net(validated = false, portal = true), Says.SIGN_IN))
        assertEquals(Sym.SIGNAL_WIFI_BAD, ConnectionRules.icon(net(validated = false), Says.NO_INTERNET))
        assertEquals(Sym.VPN_LOCK, ConnectionRules.icon(net(vpn = true), Says.VPN))
        assertEquals(Sym.WIFI, ConnectionRules.icon(net(), Says.FINE))
        assertEquals(Sym.SETTINGS_ETHERNET, ConnectionRules.icon(net(Link.ETHERNET), Says.FINE))
        assertEquals(Sym.SIGNAL_CELLULAR_ALT, ConnectionRules.icon(net(Link.MOBILE, metered = true), Says.METERED))
        assertEquals(Sym.LAN, ConnectionRules.icon(net(Link.OTHER), Says.FINE))
        // No internet over a cable is the cable's glyph (in the alert's colour): a crossed Wi-Fi would name the wrong link.
        assertEquals(Sym.SETTINGS_ETHERNET, ConnectionRules.icon(net(Link.ETHERNET, validated = false), Says.NO_INTERNET))
    }

    @Test fun aLinkIsNamedByItsTransportACableFirst() {
        // A VPN's network carries the transports of the one under it; a cable beside Wi-Fi is the one in use.
        assertEquals(Link.ETHERNET, ConnectionRules.link(wifi = true, ethernet = true, mobile = false))
        assertEquals(Link.WIFI, ConnectionRules.link(wifi = true, ethernet = false, mobile = true))
        assertEquals(Link.MOBILE, ConnectionRules.link(wifi = false, ethernet = false, mobile = true))
        assertEquals(Link.OTHER, ConnectionRules.link(wifi = false, ethernet = false, mobile = false))
    }

    @Test fun theStagesSayTheirOwnThingAndNoOthers() {
        val said = listOf("signin", "nointernet", "vpn", "metered", "fine", "offline").map { ConnectionRules.says(assertNotNullAnd(ConnectionRules.staged(it))) }
        assertEquals(listOf(Says.SIGN_IN, Says.NO_INTERNET, Says.VPN, Says.METERED, Says.FINE, Says.OFFLINE), said)
        assertNull(ConnectionRules.staged("no-such-state"))
    }

    private fun <T> assertNotNullAnd(v: T?): T { assertNotNull(v); return v!! }
}
