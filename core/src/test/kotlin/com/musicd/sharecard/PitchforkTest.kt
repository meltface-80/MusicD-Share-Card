package com.musicd.sharecard

import com.musicd.sharecard.meta.Pitchfork
import com.musicd.sharecard.meta.metadataHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The score, and the guard that stops it landing on the wrong record.
 *
 * The fetch is not exercised here — that would be a test of Pitchfork's uptime.
 * What IS tested is the parsing of the markup, because the shape of that page
 * is the part that can silently change, and the artist check, because getting
 * it wrong puts a stranger's score on somebody's card.
 */
class PitchforkTest {

    private val pf = Pitchfork(metadataHttpClient(), "test")

    private fun page(rating: String, extra: String = "") =
        """<html><head><script type="application/ld+json">
           {"@type":"Review","ratingValue":$rating}
           </script></head><body>$extra</body></html>"""

    @Test
    fun `the score comes out of the page's JSON-LD`() {
        val review = pf.reviewFromPage(
            page("\"8.7\""),
            "https://pitchfork.com/reviews/albums/slint-spiderland/",
            "Spiderland", "Slint"
        )
        assertNotNull(review)
        assertEquals(8.7, review!!.score!!, 0.001)
        assertTrue(!review.isBestNewMusic)
    }

    @Test
    fun `Best New Music is a page-level flag`() {
        val review = pf.reviewFromPage(
            page("9.1", "<div class=\"bnm\">Best New Music</div>"),
            "https://pitchfork.com/reviews/albums/nas-illmatic/",
            "Illmatic", "Nas"
        )
        assertTrue(review!!.isBestNewMusic)
    }

    @Test
    fun `a page for a different artist is refused`() {
        // Two records can share a title. A constructed URL that resolves is not
        // evidence it resolved to the right one.
        assertNull(
            pf.reviewFromPage(
                page("7.0"),
                "https://pitchfork.com/reviews/albums/weezer-blue/",
                "Blue", "Joni Mitchell"
            )
        )
    }

    @Test
    fun `a page with no rating is a miss`() {
        assertNull(
            pf.reviewFromPage(
                "<html><body>no review here</body></html>",
                "https://pitchfork.com/reviews/albums/slint-spiderland/",
                "Spiderland", "Slint"
            )
        )
    }

    @Test
    fun `a score outside 0 to 10 is not a score`() {
        assertNull(
            pf.reviewFromPage(
                page("87"),
                "https://pitchfork.com/reviews/albums/slint-spiderland/",
                "Spiderland", "Slint"
            )
        )
    }

    @Test
    fun `slugs are built the way Pitchfork builds them`() {
        assertEquals("my-bloody-valentine", pf.slugify("My Bloody Valentine"))
        assertEquals("channel-orange", pf.slugify("channel ORANGE"))
        assertEquals("dont-look-back", pf.slugify("Don't Look Back"))
        assertEquals("sign-o-the-times", pf.slugify("Sign O' The Times"))
    }

    @Test
    fun `the artist is recovered from a review slug`() {
        assertEquals(
            "Massive Attack",
            pf.artistFromReviewUrl(
                "https://pitchfork.com/reviews/albums/massive-attack-mezzanine/", "Mezzanine"
            )
        )
    }

    @Test
    fun `an accented artist still matches its unaccented slug`() {
        val review = pf.reviewFromPage(
            page("9.0"),
            "https://pitchfork.com/reviews/albums/bjork-homogenic/",
            "Homogenic", "Björk"
        )
        assertNotNull("Björk must match the slug 'bjork'", review)
    }

    // ------------------------------------------- when the URL cannot be guessed

    /**
     * THE REPORT THAT CAUSED THIS. A just-released album was sitting in
     * Pitchfork's own feed and this app showed nothing, because the only thing
     * it ever tried was a URL built out of the speaker's spelling of the name.
     * When that spelling is not Pitchfork's, there was no second try.
     */
    private fun feed(vararg titleAndSlug: Pair<String, String>) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
        ${titleAndSlug.joinToString("") { (title, slug) ->
            "<item><title><![CDATA[$title]]></title>" +
                "<link>https://pitchfork.com/reviews/albums/$slug/</link></item>"
        }}
        <item><title>Some news story</title>
          <link>https://pitchfork.com/news/something/</link></item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun `the feed gives a URL for every album it carries`() {
        val items = pf.parseFeed(
            feed("Sable, Fable" to "bon-iver-sable-fable", "Mezzanine" to "massive-attack-mezzanine")
        )
        assertEquals(2, items.size)
        assertEquals("Sable, Fable", items[0].album)
        assertEquals("https://pitchfork.com/reviews/albums/bon-iver-sable-fable/", items[0].url)
    }

    @Test
    fun `anything that is not an album review is left out of the feed`() {
        // The same feed carries news and features. A news URL has no score on
        // it, so following one would spend a request to find nothing.
        val items = pf.parseFeed(feed("Sable, Fable" to "bon-iver-sable-fable"))
        assertTrue(items.none { it.url.contains("/news/") })
    }

    @Test
    fun `a feed with nothing in it is not an error`() {
        assertTrue(pf.parseFeed("").isEmpty())
        assertTrue(pf.parseFeed("<rss><channel></channel></rss>").isEmpty())
    }

    @Test
    fun `a title with markup in it comes back as text`() {
        val xml = "<rss><channel><item><title>Kid A &amp; Amnesiac</title>" +
            "<link>https://pitchfork.com/reviews/albums/radiohead-kid-a-amnesiac/</link>" +
            "</item></channel></rss>"
        assertEquals("Kid A & Amnesiac", pf.parseFeed(xml)[0].album)
    }

    // --------------------------------------------------- the edition suffix

    /**
     * A speaker reports what the streaming service calls the record, and a
     * service calls it "Album (Deluxe Edition)". Pitchfork reviewed "Album", so
     * the slug built from the long name finds nothing.
     */
    @Test
    fun `an edition the streaming service added is stripped`() {
        assertEquals("Sable, Fable", pf.stripEdition("Sable, Fable (Deluxe Edition)"))
        assertEquals("Kid A", pf.stripEdition("Kid A [Remastered]"))
        assertEquals("Illmatic", pf.stripEdition("Illmatic (Explicit)"))
        assertEquals("Mezzanine", pf.stripEdition("Mezzanine (2019 Remaster)"))
        // More than one, which a service will happily do.
        assertEquals("Post", pf.stripEdition("Post (Deluxe Edition) [Explicit]"))
    }

    /**
     * DELIBERATELY NARROW, and this is the half that matters. Plenty of
     * brackets are part of the name, and stripping one finds a different
     * record — which is worse than finding none, because the card would then
     * carry somebody else's score.
     */
    @Test
    fun `a bracket that is part of the title is left alone`() {
        assertEquals("Red (Taylor's Version)", pf.stripEdition("Red (Taylor's Version)"))
        assertEquals("Bitte Orca", pf.stripEdition("Bitte Orca"))
        assertEquals("( )", pf.stripEdition("( )"))
        assertEquals("Untitled (Rise)", pf.stripEdition("Untitled (Rise)"))
        // Not a TRAILING bracket, so not an edition.
        assertEquals("(What's the Story) Morning Glory?", pf.stripEdition("(What's the Story) Morning Glory?"))
    }

    @Test
    fun `a title that is nothing but an edition keeps its name`() {
        // Stripping to nothing would leave no album to look up at all.
        assertEquals("(Deluxe Edition)", pf.stripEdition("(Deluxe Edition)"))
    }

    // ------------------------------------------- the three tries, in order

    /**
     * A fake Pitchfork: 404 for everything except the pages named.
     *
     * The whole point of these two is the ORDER and the FALLBACK, which no
     * amount of testing the pieces separately establishes — the bug being
     * fixed was not a broken piece, it was a missing second try.
     */
    private class FakePitchfork(private val pages: Map<String, String>) :
        okhttp3.mockwebserver.Dispatcher() {
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun dispatch(
            request: okhttp3.mockwebserver.RecordedRequest
        ): okhttp3.mockwebserver.MockResponse {
            val path = request.path.orEmpty()
            asked += path
            val body = pages[path] ?: return okhttp3.mockwebserver.MockResponse().setResponseCode(404)
            return okhttp3.mockwebserver.MockResponse().setBody(body)
        }
    }

    private fun server(pages: Map<String, String>): Pair<okhttp3.mockwebserver.MockWebServer, FakePitchfork> {
        val dispatcher = FakePitchfork(pages)
        val s = okhttp3.mockwebserver.MockWebServer()
        s.dispatcher = dispatcher
        s.start()
        return s to dispatcher
    }

    @Test
    fun `a review Pitchfork filed under another slug is found through the feed`() {
        // The speaker says "Sable, Fable" by "Bon Iver", so this app builds
        // /bon-iver-sable-fable/ — and Pitchfork filed it somewhere else. That
        // used to be the end of it.
        val realPath = "/reviews/albums/bon-iver-sable-fable-2025/"
        val (s, fake) = server(
            mapOf(
                "/feed/feed-album-reviews/rss" to
                    """<rss><channel><item><title><![CDATA[Sable, Fable]]></title>
                       <link>${'$'}{"http://x"}</link></item></channel></rss>""",
                realPath to page("\"8.6\"")
            )
        )
        try {
            // Point the feed's link at this server's own review path.
            val feedXml = """<rss><channel><item><title><![CDATA[Sable, Fable]]></title>
                <link>${s.url(realPath)}</link></item></channel></rss>"""
            s.dispatcher = FakePitchfork(
                mapOf("/feed/feed-album-reviews/rss" to feedXml, realPath to page("\"8.6\""))
            )
            val pf = Pitchfork(metadataHttpClient(), "test", s.url("/").toString().trimEnd('/'))

            val review = pf.reviewFor("Sable, Fable", "Bon Iver")
            assertNotNull("the feed should have found it", review)
            assertEquals(8.6, review!!.score!!, 0.001)
            assertTrue(review.url.endsWith(realPath))
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun `the constructed URL is tried first and the feed is not fetched when it hits`() {
        val path = "/reviews/albums/slint-spiderland/"
        val (s, fake) = server(mapOf(path to page("\"10.0\"")))
        try {
            val pf = Pitchfork(metadataHttpClient(), "test", s.url("/").toString().trimEnd('/'))
            val review = pf.reviewFor("Spiderland", "Slint")
            assertNotNull(review)
            assertEquals(10.0, review!!.score!!, 0.001)
            // One request. An album that resolves straight away must not cost
            // a feed download as well.
            assertEquals(listOf(path), fake.asked)
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun `the edition is stripped before the feed is ever asked`() {
        val path = "/reviews/albums/bjork-post/"
        val (s, fake) = server(mapOf(path to page("\"9.0\"")))
        try {
            val pf = Pitchfork(metadataHttpClient(), "test", s.url("/").toString().trimEnd('/'))
            val review = pf.reviewFor("Post (Deluxe Edition)", "Björk")
            assertNotNull("the edition suffix should not have hidden it", review)
            assertEquals(9.0, review!!.score!!, 0.001)
            // The long name first, then the short one. No feed.
            assertEquals(
                listOf("/reviews/albums/bjork-post-deluxe-edition/", path), fake.asked
            )
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun `a record nobody reviewed costs three tries and then stops`() {
        val (s, fake) = server(mapOf("/feed/feed-album-reviews/rss" to "<rss><channel></channel></rss>"))
        try {
            val pf = Pitchfork(metadataHttpClient(), "test", s.url("/").toString().trimEnd('/'))
            assertNull(pf.reviewFor("Nonexistent Album Xyzzy", "Zzzz"))
            // And the miss is cached, so asking again asks nobody.
            val before = fake.asked.size
            assertNull(pf.reviewFor("Nonexistent Album Xyzzy", "Zzzz"))
            assertEquals("a cached miss must not be re-fetched", before, fake.asked.size)
            // Which is also what /api/debug now shows instead of silence.
            assertTrue(pf.attempts().isNotEmpty())
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun `an accent in a name folds the way Pitchfork folds it`() {
        // "bj-rk" was what this built before — every letter Pitchfork uses
        // except the one it folds — so no album by Björk, Sigur Rós or
        // Mötley Crüe ever resolved, and the card just had no score.
        assertEquals("bjork", pf.slugify("Björk"))
        assertEquals("sigur-ros", pf.slugify("Sigur Rós"))
        assertEquals("aenima", pf.slugify("Ænima"))
        // The apostrophe is deleted, not folded: Pitchfork writes "dont".
        assertEquals("dont-smile-at-me", pf.slugify("Don't Smile at Me"))
        assertEquals("blowin-up", pf.slugify("Blowin' Up"))
        // And the ordinary case is unchanged.
        assertEquals("massive-attack", pf.slugify("Massive Attack"))
        assertEquals("good-kid-m-a-a-d-city", pf.slugify("good kid, m.A.A.d city"))
    }
}
