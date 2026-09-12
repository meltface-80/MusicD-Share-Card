package com.musicd.sharecard

import com.musicd.sharecard.meta.StreamingLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The links out to the streaming services.
 *
 * WHAT THIS CAN AND CANNOT ESTABLISH. It tests the URLs that get built — the
 * encoding, the storefront, the shape. It cannot test which app answers which
 * host, and that is where the one known failure came from: MusicD Remote Lite
 * shipped open.qobuz.com, whose app claims every path on that host and has no
 * search screen, so the link opened the app on Discover and looked like it had
 * done nothing. Only a phone can catch that.
 */
class StreamingLinksTest {

    private fun urls(artist: String?, album: String, locale: Locale = Locale.UK) =
        StreamingLinks.forAlbum(artist, album, locale).associate { it.service to it.url }

    @Test
    fun `every service is offered, in one order, for one record`() {
        // The page draws whatever comes back, so this list IS the feature.
        assertEquals(
            listOf("qobuz", "tidal", "spotify", "apple", "amazon", "deezer"),
            StreamingLinks.forAlbum("TOOL", "Ænima").map { it.service }
        )
        assertEquals(
            listOf("Qobuz", "TIDAL", "Spotify", "Apple Music", "Amazon Music", "Deezer"),
            StreamingLinks.forAlbum("TOOL", "Ænima").map { it.name }
        )
    }

    @Test
    fun `nothing is linked when there is nothing to search for`() {
        assertNull(StreamingLinks.searchQuery(null, ""))
        assertNull(StreamingLinks.searchQuery("   ", "  "))
        assertTrue(StreamingLinks.forAlbum(null, "").isEmpty())
        // An album with no artist is still an album worth finding.
        assertTrue(StreamingLinks.forAlbum(null, "Spirit of Eden").isNotEmpty())
    }

    @Test
    fun `a space is percent-encoded and never a plus`() {
        // THIS IS THE ONE THAT WOULD SLIP THROUGH A CASUAL READING. URLEncoder
        // writes a space as "+", which a query string decodes back to a space
        // only by convention — and four of these six carry the query in the
        // PATH, where "+" is a literal plus and gets searched for.
        val q = StreamingLinks.searchQuery("Sigur Rós", "Ágætis byrjun")!!
        assertFalse("a plus would be searched for literally in a path", q.contains("+"))
        assertTrue(q.contains("%20"))

        for ((service, url) in urls("Sigur Rós", "Ágætis byrjun")) {
            assertFalse("$service must not carry a raw space", url.contains(" "))
            assertFalse("$service must not carry a form-encoded space", url.contains("+"))
        }
    }

    @Test
    fun `a slash is spent as a space rather than encoded`() {
        // "AC/DC" encoded as %2F arrives at Qobuz as two path segments after
        // its own redirect decodes it, and 404s. A slash means nothing to a
        // search box, so it is spent before any encoding happens.
        val q = StreamingLinks.searchQuery("AC/DC", "Back in Black")!!
        assertEquals("AC%20DC%20Back%20in%20Black", q)
        assertFalse(q.contains("%2F", ignoreCase = true))
        // A Windows-style backslash goes the same way.
        assertEquals("AC%20DC", StreamingLinks.searchQuery("AC\\DC", "")!!)
    }

    @Test
    fun `the characters that break a URL are encoded`() {
        val q = StreamingLinks.searchQuery("Godspeed You! Black Emperor", "F#A#∞")!!
        assertFalse("a bare # would truncate every one of these URLs", q.contains("#"))
        assertFalse(StreamingLinks.searchQuery("Q&A", "R&B")!!.contains("&"))
    }

    @Test
    fun `the Qobuz storefront is one Qobuz actually sells in`() {
        // Qobuz answers an unknown country with a 404, so this cannot be built
        // by joining language and country and hoping.
        assertEquals("gb-en", StreamingLinks.storefront(Locale.UK))
        assertEquals("fr-fr", StreamingLinks.storefront(Locale.FRANCE))
        assertEquals("jp-ja", StreamingLinks.storefront(Locale.JAPAN))
        // English in Belgium: no be-en, but Belgium is a country Qobuz sells
        // in, so the storefront for that country wins over falling back to us.
        assertEquals("be-fr", StreamingLinks.storefront(Locale("en", "BE")))
        // Somewhere Qobuz does not sell, and a device that will not say where
        // it is, both land where qobuz.com sends a visitor it cannot place.
        assertEquals("us-en", StreamingLinks.storefront(Locale("en", "ZW")))
        assertEquals("us-en", StreamingLinks.storefront(Locale("en")))
    }

    @Test
    fun `Qobuz is linked on the host that reaches a browser`() {
        // open.qobuz.com publishes assetlinks.json and an
        // apple-app-site-association claiming paths ["*"], so the app swallows
        // every link to it — including a search it has no screen for. That
        // shipped once; it must not come back.
        val qobuz = urls("TOOL", "Ænima")["qobuz"]!!
        assertFalse(qobuz.contains("open.qobuz.com"))
        assertTrue(qobuz.startsWith("https://www.qobuz.com/gb-en/search/?q="))
    }

    @Test
    fun `every link is https`() {
        // These are handed to the system to open. Plain http would be one an
        // attacker on the same wifi could answer.
        for ((service, url) in urls("Talk Talk", "Laughing Stock")) {
            assertTrue("$service is not https: $url", url.startsWith("https://"))
        }
    }

    @Test
    fun `each service gets the query where that service expects it`() {
        val links = urls("Talk Talk", "Laughing Stock")
        val q = "Talk%20Talk%20Laughing%20Stock"
        assertEquals("https://www.qobuz.com/gb-en/search/?q=$q", links["qobuz"])
        assertEquals("https://tidal.com/search?q=$q", links["tidal"])
        // Spotify, Amazon and Deezer take it as a path segment; TIDAL and
        // Apple take it as a parameter, under different names.
        assertEquals("https://open.spotify.com/search/$q", links["spotify"])
        assertEquals("https://music.apple.com/search?term=$q", links["apple"])
        assertEquals("https://music.amazon.com/search/$q", links["amazon"])
        assertEquals("https://www.deezer.com/search/$q", links["deezer"])
    }
}
