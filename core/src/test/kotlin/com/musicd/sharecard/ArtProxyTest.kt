package com.musicd.sharecard

import com.musicd.sharecard.api.ArtProxy
import com.musicd.sharecard.meta.metadataHttpClient
import org.junit.Assert.assertFalse
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

    private val proxy = ArtProxy(metadataHttpClient())

    @Test
    fun `a Sonos player on the LAN is allowed`() {
        assertTrue(proxy.isAllowed("http://192.168.1.40:1400/getaa?u=x&v=53"))
        assertTrue(proxy.isAllowed("http://10.0.0.5:1400/getaa?u=x"))
        assertTrue(proxy.isAllowed("http://172.16.4.1:1400/getaa"))
        assertTrue(proxy.isAllowed("http://172.31.255.254:1400/getaa"))
    }

    @Test
    fun `the public internet is refused`() {
        assertFalse(proxy.isAllowed("https://example.com/cover.jpg"))
        assertFalse(proxy.isAllowed("http://93.184.216.34/cover.jpg"))
        // 172.32 is OUTSIDE the private /12, and an off-by-one here would open
        // the proxy to a public range.
        assertFalse(proxy.isAllowed("http://172.32.0.1/cover.jpg"))
        assertFalse(proxy.isAllowed("http://172.15.0.1/cover.jpg"))
    }

    @Test
    fun `a hostname is refused even if it would resolve privately`() {
        // Resolving first would let the answer change between the check and
        // the fetch. Sonos art URLs are always literal addresses.
        assertFalse(proxy.isAllowed("http://sonos.local/getaa"))
        assertFalse(proxy.isAllowed("http://evil.example/getaa"))
    }

    @Test
    fun `a scheme that is not http is refused`() {
        assertFalse(proxy.isAllowed("file:///etc/passwd"))
        assertFalse(proxy.isAllowed("ftp://192.168.1.1/x"))
        assertFalse(proxy.isAllowed("gopher://192.168.1.1/x"))
        assertFalse(proxy.isAllowed(""))
        assertFalse(proxy.isAllowed("not a url"))
    }

    @Test
    fun `an octal-looking address is refused rather than guessed at`() {
        // Some resolvers read a leading zero as octal and some as decimal, so
        // "0177.0.0.1" can mean two different hosts. Neither is worth serving.
        assertFalse(proxy.isAllowed("http://0177.0.0.1/x"))
        assertFalse(proxy.isAllowed("http://010.0.0.1/x"))
    }

    @Test
    fun `an address with too few or too many parts is refused`() {
        assertFalse(proxy.isPrivateHost("192.168.1"))
        assertFalse(proxy.isPrivateHost("192.168.1.1.1"))
        assertFalse(proxy.isPrivateHost("192.168.1.999"))
        assertFalse(proxy.isPrivateHost("192.168.one.1"))
    }
}
