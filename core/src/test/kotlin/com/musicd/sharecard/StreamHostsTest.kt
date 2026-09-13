package com.musicd.sharecard

import com.musicd.sharecard.api.ArtProxy
import com.musicd.sharecard.meta.metadataHttpClient
import com.musicd.sharecard.source.StreamHosts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A music server on the LAN may serve the picture for the music it is playing.
 *
 * REPORTED FROM THE FIELD. A MusicD Server on a DietPi box at
 * 192.168.0.57:3400 streamed "Heaven or Las Vegas" to a Sonos, and the card
 * drew the album, the artist and the Wikipedia blurb with a blank sleeve —
 * while the Sonos app showed the cover, because it fetches that URL directly.
 * /api/debug named it in one line:
 *
 *   "art":"http://192.168.0.57:3400/art/YTpDb2N0ZWF1IFR3aW5z…"
 *
 * .57 is not a speaker and not public https, so the art proxy refused it.
 */
class StreamHostsTest {

    private val hosts = StreamHosts()

    @Test
    fun `the host streaming the audio may serve the picture`() {
        hosts.remember("http://192.168.0.57:3400/stream/track/1234.flac")
        assertTrue(hosts.hosts().contains("192.168.0.57"))
    }

    @Test
    fun `a speaker's own scheme names no web host`() {
        // x-rincon: is one Sonos following another; x-sonos-htastream: is a TV
        // input. Neither serves pictures over HTTP.
        hosts.remember("x-rincon:RINCON_949F3EC13E4801400")
        hosts.remember("x-sonos-htastream:RINCON_542A1B8B58A401400:spdif")
        hosts.remember("x-file-cifs://nas/music/track.flac")
        hosts.remember("")
        assertTrue(hosts.hosts().toString(), hosts.hosts().isEmpty())
    }

    @Test
    fun `it keeps the most recently played and drops the stalest`() {
        val small = StreamHosts(limit = 2)
        small.remember("http://a.example/1")
        small.remember("http://b.example/1")
        small.remember("http://c.example/1")
        assertEquals(setOf("b.example", "c.example"), small.hosts())
    }

    @Test
    fun `playing from a host again keeps it alive`() {
        val small = StreamHosts(limit = 2)
        small.remember("http://a.example/1")
        small.remember("http://b.example/1")
        small.remember("http://a.example/2")
        small.remember("http://c.example/1")
        // a was re-played, so b is the stalest and goes.
        assertEquals(setOf("a.example", "c.example"), small.hosts())
    }

    @Test
    fun `THE REPORTED CASE - the proxy allows the art once the audio is seen`() {
        val known = mutableSetOf("192.168.0.93", "192.168.0.192", "192.168.0.238")
        val proxy = ArtProxy(metadataHttpClient()) { known }
        val art = "http://192.168.0.57:3400/art/YTpDb2N0ZWF1IFR3aW5z"

        // Before: .57 is not a speaker and not public https.
        assertFalse("this is the bug", proxy.isAllowed(art))

        // The transport says .57 is the machine playing the music.
        hosts.remember("http://192.168.0.57:3400/stream/track/1234.flac")
        known += hosts.hosts()
        assertTrue("and this is the fix", proxy.isAllowed(art))
    }

    @Test
    fun `an art host that never streamed anything is still refused`() {
        val proxy = ArtProxy(metadataHttpClient()) { hosts.hosts() }
        hosts.remember("http://192.168.0.57:3400/stream/track/1234.flac")
        // Nothing about .57 streaming makes a DIFFERENT private address safe,
        // which is the whole point of not trusting "looks local".
        assertFalse(proxy.isAllowed("http://192.168.0.99:3400/art/x"))
        assertFalse(proxy.isAllowed("http://127.0.0.1/art/x"))
        assertFalse(proxy.isAllowed("http://169.254.169.254/latest/meta-data/"))
    }
}
