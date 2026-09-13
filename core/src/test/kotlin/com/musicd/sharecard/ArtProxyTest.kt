package com.musicd.sharecard

import com.musicd.sharecard.api.ArtProxy
import com.musicd.sharecard.meta.metadataHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the art proxy will and will not fetch.
 *
 * The URL arrives as a query parameter on a route served to the whole LAN, so
 * without this check anybody on the network could hand the app any URL and
 * have it fetched on their behalf — including one naming a host only the app
 * can reach.
 */
class ArtProxyTest {

    /** A household with two discovered players, as topology would report. */
    private val proxy = ArtProxy(metadataHttpClient()) {
        listOf("192.168.0.93", "192.168.0.192")
    }

    @Test
    fun `a discovered player is allowed`() {
        assertTrue(proxy.isAllowed("http://192.168.0.93:1400/getaa?u=x&v=53"))
        assertTrue(proxy.isAllowed("http://192.168.0.192:1400/getaa?u=x"))
    }

    @Test
    fun `a LAN address that is NOT one of our players is refused`() {
        // The old rule allowed anything that looked private, which made this
        // app a fetcher for every other box on the network.
        assertFalse(proxy.isAllowed("http://192.168.0.50:1400/getaa"))
        assertFalse(proxy.isAllowed("http://10.0.0.5:8080/secret"))
        assertFalse(proxy.isAllowed("http://172.16.4.1/x"))
    }

    /**
     * The bug that left a Spotify Connect card with a blank sleeve.
     *
     * Spotify reports its art as an absolute CDN link rather than a player
     * path, and the proxy refused it because it was not a private address —
     * while the Sonos app showed the cover perfectly.
     */
    @Test
    fun `a music service CDN over https is allowed`() {
        assertTrue(proxy.isAllowed("https://i.scdn.co/image/ab67616d0000b273abcdef"))
        assertTrue(proxy.isAllowed("https://is1-ssl.mzstatic.com/image/thumb/x/100x100bb.jpg"))
        assertTrue(proxy.isAllowed("https://resources.tidal.com/images/x/640x640.jpg"))
    }

    /**
     * The half the old rule had backwards. These are the addresses an SSRF
     * guard exists to protect, and it was letting every one of them through.
     */
    @Test
    fun `loopback and the metadata endpoint are refused`() {
        assertFalse(proxy.isAllowed("http://127.0.0.1:8747/api/debug"))
        assertFalse(proxy.isAllowed("http://localhost/x"))
        assertFalse(proxy.isAllowed("https://169.254.169.254/latest/meta-data/"))
        assertFalse("our own card server must not be fetchable through itself",
            proxy.isAllowed("http://127.0.0.1/"))
    }

    @Test
    fun `plain http to the public internet is refused`() {
        // A known player is reached over http because Sonos offers nothing
        // else. Everything beyond the LAN has no such excuse.
        assertFalse(proxy.isAllowed("http://i.scdn.co/image/x"))
    }

    @Test
    fun `a scheme that is not http is refused`() {
        assertFalse(proxy.isAllowed("file:///etc/passwd"))
        assertFalse(proxy.isAllowed("ftp://192.168.0.93/x"))
        assertFalse(proxy.isAllowed(""))
        assertFalse(proxy.isAllowed("not a url"))
    }

    @Test
    fun `other numeric forms of loopback are refused`() {
        // 0177.0.0.1 is octal for 127.0.0.1 and 2130706433 is its decimal
        // form. Neither may pass as "not recognised as private, so public".
        assertFalse(proxy.isAllowed("https://2130706433/x"))
        assertFalse(proxy.isAllowed("https://192.168.0.93.1/x"))
        assertFalse(proxy.isAllowed("https://1.2.3/x"))
        assertFalse(proxy.isPublicHost("0177.0.0.1"))
        assertFalse(proxy.isPublicHost("2130706433"))
    }

    @Test
    fun `carrier-grade NAT and multicast are not public either`() {
        assertFalse(proxy.isPublicHost("100.64.0.1"))
        assertFalse(proxy.isPublicHost("239.255.255.250"))
        assertFalse(proxy.isPublicHost("0.0.0.0"))
    }

    @Test
    fun `an IPv6 literal is refused rather than classified`() {
        assertFalse(proxy.isAllowed("https://[::1]/x"))
        assertFalse(proxy.isAllowed("https://[::ffff:127.0.0.1]/x"))
    }

    @Test
    fun `an octal-looking address is not mistaken for a public host`() {
        // Some resolvers read a leading zero as octal and some as decimal, so
        // "0177.0.0.1" can mean two different hosts. It is not a known player,
        // so it must not pass as "public" either.
        assertFalse(proxy.isAllowed("https://0177.0.0.1/x"))
        assertFalse(proxy.isAllowed("https://010.0.0.1/x"))
    }

    @Test
    fun `private ranges are still recognised as private`() {
        assertTrue(proxy.isPrivateHost("10.1.2.3"))
        assertTrue(proxy.isPrivateHost("172.31.255.254"))
        assertTrue(proxy.isPrivateHost("192.168.1.1"))
        assertTrue(proxy.isPrivateHost("169.254.169.254"))
        assertFalse(proxy.isPrivateHost("172.32.0.1"))
        assertFalse(proxy.isPrivateHost("93.184.216.34"))
    }

    @Test
    fun `a refusal is written down, because a blank sleeve says nothing`() {
        val proxy = ArtProxy(metadataHttpClient()) { setOf("192.168.0.93") }
        assertNull(proxy.fetch("http://192.168.0.57:3400/art/abc"))
        val notes = proxy.attempts()
        assertTrue(notes.toString(), notes.any { it.contains("192.168.0.57") })
        // Named, not just logged: "no cover" has four causes that look the
        // same on a card, and only /api/debug can tell them apart.
        assertTrue(notes.toString(), notes.any { it.contains("REFUSED") })
    }

    @Test
    fun `the note says WHICH failure it was`() {
        val proxy = ArtProxy(metadataHttpClient()) { emptySet() }
        proxy.fetch("http://10.1.2.3/art.jpg")
        // A refusal and a 404 want different fixes, so they must not share a
        // line. This one never reached the network at all.
        assertTrue(proxy.attempts().none { it.contains("HTTP ") })
    }

    @Test
    fun `the log is bounded, because this runs for months`() {
        val proxy = ArtProxy(metadataHttpClient()) { emptySet() }
        repeat(40) { proxy.fetch("http://10.1.2.3/art-$it.jpg") }
        assertTrue(proxy.attempts().size.toString(), proxy.attempts().size <= 12)
    }
}
