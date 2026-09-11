package com.musicd.sharecard

import com.musicd.sharecard.sonos.Didl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DIDL a real player sends, source by source.
 *
 * Every sample here is the shape Sonos actually returns for that source, not
 * an invented minimal one — the differences BETWEEN them are the whole problem
 * this parser exists to solve, and a tidied-up sample would hide them.
 */
class DidlTest {

    private fun didl(body: String) = """
        <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/"
                   xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"
                   xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/"
                   xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
          $body
        </DIDL-Lite>
    """.trimIndent()

    @Test
    fun `reads a track from the Sonos queue`() {
        val np = Didl.parse(
            didl(
                """
                <item id="-1" parentID="-1" restricted="true">
                  <res protocolInfo="x-file-cifs:*:audio/flac:*">x-file-cifs://nas/Music/a.flac</res>
                  <upnp:albumArtURI>/getaa?u=x-file-cifs%3a%2f%2fnas&amp;v=53</upnp:albumArtURI>
                  <dc:title>Teardrop</dc:title>
                  <upnp:class>object.item.audioItem.musicTrack</upnp:class>
                  <dc:creator>Massive Attack</dc:creator>
                  <upnp:album>Mezzanine</upnp:album>
                </item>
                """
            )
        )
        assertEquals("Teardrop", np.track)
        assertEquals("Mezzanine", np.album)
        assertEquals("Massive Attack", np.artist)
        assertEquals("Mezzanine", np.displayAlbum)
        assertFalse(np.isEmpty)
    }

    @Test
    fun `upnp artist beats dc creator when a source sends both`() {
        // Spotify sends both, and they disagree on a collaboration: creator is
        // the whole billed credit, artist is the primary act.
        val np = Didl.parse(
            didl(
                """
                <item>
                  <dc:title>Pyramids</dc:title>
                  <dc:creator>Frank Ocean, John Mayer</dc:creator>
                  <upnp:artist>Frank Ocean</upnp:artist>
                  <upnp:album>channel ORANGE</upnp:album>
                </item>
                """
            )
        )
        assertEquals("Frank Ocean", np.artist)
    }

    @Test
    fun `album artist wins on a compilation`() {
        // The bug this prevents: a card for a Blue Note compilation headed
        // "by Lee Morgan" because he happened to be playing when you tapped.
        val np = Didl.parse(
            didl(
                """
                <item>
                  <dc:title>Sidewinder</dc:title>
                  <upnp:artist>Lee Morgan</upnp:artist>
                  <upnp:albumArtist>Various Artists</upnp:albumArtist>
                  <upnp:album>Blue Note Trip</upnp:album>
                </item>
                """
            )
        )
        assertEquals("Various Artists", np.displayArtist)
        assertEquals("Lee Morgan", np.artist)
    }

    @Test
    fun `a radio stream has no album, so the track title heads the card`() {
        val np = Didl.parse(
            didl(
                """
                <item>
                  <dc:title>BBC Radio 6 Music</dc:title>
                  <r:streamContent>Bill Callahan - Drover</r:streamContent>
                  <upnp:class>object.item.audioItem.audioBroadcast</upnp:class>
                </item>
                """
            )
        )
        assertEquals("", np.album)
        assertEquals("BBC Radio 6 Music", np.displayAlbum)
        assertEquals("Bill Callahan - Drover", np.streamContent)
    }

    @Test
    fun `NOT_IMPLEMENTED between tracks parses as empty, not as a crash`() {
        val np = Didl.parse(Didl.NOT_IMPLEMENTED)
        assertTrue(np.isEmpty)
        assertEquals("", np.displayAlbum)
    }

    @Test
    fun `malformed XML from a player is a miss, not an exception`() {
        assertTrue(Didl.parse("<DIDL-Lite><item><dc:title>unclosed").isEmpty)
        assertTrue(Didl.parse("").isEmpty)
        assertTrue(Didl.parse("   ").isEmpty)
    }

    @Test
    fun `a nested item does not overwrite the track that is playing`() {
        // Some services wrap a recommendation or a container inside the same
        // DIDL. Reading descendants rather than the item's own children put
        // the WRONG album on the card.
        val np = Didl.parse(
            didl(
                """
                <item>
                  <dc:title>Real Track</dc:title>
                  <upnp:album>Real Album</upnp:album>
                  <desc id="cdudn">
                    <item><dc:title>Suggested</dc:title><upnp:album>Other Album</upnp:album></item>
                  </desc>
                </item>
                """
            )
        )
        assertEquals("Real Track", np.track)
        assertEquals("Real Album", np.album)
    }

    /**
     * Roon streaming to Sonos reports its session id as the track title.
     *
     * A card headed "Roon698eb0332b8c432d98294f5a377f3a11" looks like the app
     * working, which is worse than one that admits it knows nothing.
     */
    @Test
    fun `an opaque session id is not used as an album title`() {
        val np = Didl.parse(
            didl(
                """
                <item>
                  <dc:title>Roon698eb0332b8c432d98294f5a377f3a11</dc:title>
                  <res protocolInfo="http-get:*:audio/flac:*">http://10.0.0.9:9200/stream</res>
                </item>
                """
            )
        )
        assertEquals("Roon698eb0332b8c432d98294f5a377f3a11", np.track)
        assertEquals("the card must not be headed with a hash", "", np.displayAlbum)
    }

    @Test
    fun `a real album title is never mistaken for an identifier`() {
        // The cost of getting this wrong is throwing away a real record, so the
        // test is narrow on purpose.
        assertFalse(Didl.looksLikeStreamId("Kind of Blue"))
        assertFalse(Didl.looksLikeStreamId("Ænima"))
        assertFalse(Didl.looksLikeStreamId("OK Computer"))
        assertFalse(Didl.looksLikeStreamId("1989"))
        assertFalse(Didl.looksLikeStreamId("Face"))          // short, all hex
        assertFalse(Didl.looksLikeStreamId("Deadbeef"))      // hex but only 8
        assertFalse(Didl.looksLikeStreamId("The Decca Sessions 1934"))
        assertTrue(Didl.looksLikeStreamId("Roon698eb0332b8c432d98294f5a377f3a11"))
        assertTrue(Didl.looksLikeStreamId("698eb0332b8c432d98294f5a377f3a11"))
    }

    @Test
    fun `an album that IS present always wins over the identifier rule`() {
        val np = Didl.parse(
            didl(
                """
                <item>
                  <dc:title>Roon698eb0332b8c432d98294f5a377f3a11</dc:title>
                  <upnp:album>Spiderland</upnp:album>
                </item>
                """
            )
        )
        assertEquals("Spiderland", np.displayAlbum)
    }

    // ------------------------------------------------------------ art URIs

    @Test
    fun `a player-relative art path is resolved against that player`() {
        assertEquals(
            "http://192.168.1.40:1400/getaa?u=spotify%3atrack&v=53",
            Didl.absoluteArt("/getaa?u=spotify%3atrack&v=53", "http://192.168.1.40:1400")
        )
    }

    @Test
    fun `an absolute art URL is left alone`() {
        val cdn = "https://i.scdn.co/image/ab67616d0000b273"
        assertEquals(cdn, Didl.absoluteArt(cdn, "http://192.168.1.40:1400"))
    }

    @Test
    fun `no art is an empty string rather than a bad URL`() {
        assertEquals("", Didl.absoluteArt("", "http://192.168.1.40:1400"))
        assertEquals("", Didl.absoluteArt("   ", "http://192.168.1.40:1400"))
    }

    @Test
    fun `a base URL with a trailing slash resolves the same way`() {
        assertEquals(
            "http://192.168.1.40:1400/getaa?x=1",
            Didl.absoluteArt("/getaa?x=1", "http://192.168.1.40:1400/")
        )
    }

    // -------------------------------------------------- stream announcements

    @Test
    fun `a station announcement splits on the spaced dash`() {
        assertEquals(
            "Bill Callahan" to "Drover",
            Didl.splitStreamContent("Bill Callahan - Drover")
        )
        assertEquals("Björk" to "Jóga", Didl.splitStreamContent("Björk – Jóga"))
    }

    @Test
    fun `the first separator wins, so a dash in the title survives`() {
        assertEquals(
            "Bruce Springsteen" to "Born to Run - Live",
            Didl.splitStreamContent("Bruce Springsteen - Born to Run - Live")
        )
    }

    @Test
    fun `an unspaced hyphen is part of a name, not a separator`() {
        // "Jay-Z" must not become artist "Jay" and title "Z".
        assertNull(Didl.splitStreamContent("Jay-Z"))
    }

    @Test
    fun `a station announcing only a title is not split in half`() {
        assertNull(Didl.splitStreamContent("The Breakfast Show"))
        assertNull(Didl.splitStreamContent(""))
        assertNull(Didl.splitStreamContent(" - Drover"))
    }
}
