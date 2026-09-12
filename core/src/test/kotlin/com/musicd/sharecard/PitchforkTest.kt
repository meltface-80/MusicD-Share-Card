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
    fun `an album too old for the index falls through to its own review page`() {
        // Nothing lists a review from 1991, so the index answers nothing and
        // the constructed URL is what finds it. The RSS feed is never reached,
        // because the page itself had the rating on it.
        val path = "/reviews/albums/slint-spiderland/"
        val (s, fake) = server(mapOf(path to page("\"10.0\"")))
        try {
            val pf = Pitchfork(metadataHttpClient(), "test", s.url("/").toString().trimEnd('/'))
            val review = pf.reviewFor("Spiderland", "Slint")
            assertNotNull(review)
            assertEquals(10.0, review!!.score!!, 0.001)
            assertEquals(listOf("/reviews/albums/", path), fake.asked)
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun `the edition is stripped before the feed is ever asked, index first`() {
        val path = "/reviews/albums/bjork-post/"
        val (s, fake) = server(mapOf(path to page("\"9.0\"")))
        try {
            val pf = Pitchfork(metadataHttpClient(), "test", s.url("/").toString().trimEnd('/'))
            val review = pf.reviewFor("Post (Deluxe Edition)", "Björk")
            assertNotNull("the edition suffix should not have hidden it", review)
            assertEquals(9.0, review!!.score!!, 0.001)
            // The long name first, then the short one. No feed.
            assertEquals(
                listOf("/reviews/albums/", "/reviews/albums/bjork-post-deluxe-edition/", path),
                fake.asked
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

    @Test
    fun `a feed title that names the artist too still matches the album`() {
        // A feed <title> is whatever the publisher puts there. Matching only
        // the whole string makes the fallback miss exactly the records it
        // exists for; the artist check on the review page is what actually
        // stops a wrong match.
        assertTrue(pf.titleForms("Bon Iver: SABLE, fABLE").contains("sable fable"))
        assertTrue(pf.titleForms("SABLE, fABLE").contains("sable fable"))
        assertTrue(pf.titleForms("Björk: Post").contains("post"))
        // The whole string is still a form, for a title that has a colon in it.
        assertTrue(pf.titleForms("Untitled: Unmastered").contains("untitled unmastered"))
    }

    // ------------------------------------------- the score, as Pitchfork writes it

    /**
     * THE ACTUAL BUG, from a real /api/debug dump.
     *
     * The right review page was fetched — "This Is Lorelei", "The Singer in My
     * Band", the slug this app built, HTTP 200 — and no score came out of it.
     * Pitchfork's ratingValue is an OBJECT:
     *
     *     "ratingValue": { "score": "8.5", "isBestNewMusic": true, … }
     *
     * and a pattern looking for `"ratingValue": 8.5` cannot match it, because
     * after the colon comes a brace. The app this was ported from reads exactly
     * this object out of its listing page and always has; only the review-page
     * reader was left looking for the older scalar form.
     */
    private fun modernPage(score: String, bnm: Boolean = false, extra: String = "") =
        """<html><body>$extra<script>window.__PRELOADED_STATE__ = {"items":[{
           "contentType":"review","url":"/reviews/albums/x/",
           "ratingValue":{"score":"$score","isBestNewMusic":$bnm,"isBestNewReissue":false}
           }]}</script></body></html>"""

    @Test
    fun `a score inside Pitchfork's ratingValue object is read`() {
        val review = pf.reviewFromPage(
            modernPage("8.5"),
            "https://pitchfork.com/reviews/albums/this-is-lorelei-the-singer-in-my-band/",
            "The Singer in My Band", "This Is Lorelei"
        )
        assertNotNull("the object form is the one Pitchfork publishes", review)
        assertEquals(8.5, review!!.score!!, 0.001)
        assertTrue(!review.isBestNewMusic)
    }

    @Test
    fun `the older scalar form still works`() {
        // Both shapes are accepted; a page that has not been migrated must not
        // stop working to make room for one that has.
        val review = pf.reviewFromPage(
            page("\"10.0\""),
            "https://pitchfork.com/reviews/albums/slint-spiderland/",
            "Spiderland", "Slint"
        )
        assertEquals(10.0, review!!.score!!, 0.001)
    }

    @Test
    fun `Best New Music comes from the flag beside the score`() {
        val yes = pf.reviewFromPage(
            modernPage("8.5", bnm = true),
            "https://pitchfork.com/reviews/albums/a-b/", "B", "A"
        )
        assertTrue(yes!!.isBestNewMusic)
    }

    /**
     * THE FALSE POSITIVE THIS REPLACES. Every Pitchfork page carries "Best New
     * Music" in its own navigation, so scanning the page text for those words
     * marks every single review as Best New Music. The structured flag beside
     * the score is the answer whenever there is one.
     */
    @Test
    fun `Pitchfork's own navigation does not make every record Best New Music`() {
        val review = pf.reviewFromPage(
            modernPage("6.8", bnm = false, extra = "<nav><a href=\"/best/\">Best New Music</a></nav>"),
            "https://pitchfork.com/reviews/albums/a-b/", "B", "A"
        )
        assertNotNull(review)
        assertTrue("the nav link is not this record's flag", !review!!.isBestNewMusic)
    }

    @Test
    fun `a page with no rating at all reports no score, not a wrong artist`() {
        // The two were indistinguishable in the diagnostics, and that is what
        // sent two releases' worth of fixes after the wrong cause.
        val noScore = pf.readPage(
            "<html><body>nothing here</body></html>",
            "https://pitchfork.com/reviews/albums/this-is-lorelei-the-singer-in-my-band/",
            "The Singer in My Band", "This Is Lorelei"
        )
        assertTrue("$noScore", noScore is Pitchfork.Outcome.NoScore)

        val wrongArtist = pf.readPage(
            modernPage("8.5"),
            "https://pitchfork.com/reviews/albums/somebody-else-the-singer-in-my-band/",
            "The Singer in My Band", "This Is Lorelei"
        )
        assertTrue("$wrongArtist", wrongArtist is Pitchfork.Outcome.WrongArtist)
    }

    // ------------------------------------ the index, which is where the score is

    /**
     * THE ONE THAT MATTERS, and it took a dump off a real device to find.
     *
     * "https://pitchfork.com/reviews/albums/this-is-lorelei-the-singer-in-my-band/
     *  -> page read, NO SCORE IN IT"
     *
     * The right review, fetched, HTTP 200, and no rating anywhere in it — while
     * a human reading that page sees 8.0. A review page served to something
     * that is not a browser simply does not carry the number, so no amount of
     * parsing it will ever work.
     *
     * The LISTING does carry it, in a window.__PRELOADED_STATE__ blob, and
     * MusicD Remote Lite has read it that way all along — which is why typing
     * the album into that app's search box finds the 8.0. Same source here now.
     */
    private fun indexPage(vararg reviews: String) = """
        <html><body><script>
        window.__PRELOADED_STATE__ = {"transformed":{"bundle":{"containers":[
          {"items":[${reviews.joinToString(",")}]}
        ]}}};
        </script></body></html>
    """.trimIndent()

    private fun indexed(
        album: String, artist: String, slug: String, score: String, bnm: Boolean = false
    ) = """{"contentType":"review","url":"/reviews/albums/$slug/",
        "dangerousHed":"<em>$album</em>","subHed":{"name":"$artist"},
        "ratingValue":{"score":"$score","isBestNewMusic":$bnm,"isBestNewReissue":false}}"""

    @Test
    fun `the score comes out of Pitchfork's own index`() {
        val html = indexPage(
            indexed("The Singer in My Band", "This Is Lorelei", "this-is-lorelei-the-singer-in-my-band", "8.0")
        )
        val state = pf.extractPreloadedState(html)
        assertNotNull("the preloaded state was not found", state)
        val items = pf.collectListing(org.json.JSONObject(state!!))
        assertEquals(1, items.size)
        assertEquals("The Singer in My Band", items[0].album)
        assertEquals("This Is Lorelei", items[0].artist)
        assertEquals(8.0, items[0].score!!, 0.001)
        assertTrue(items[0].url.endsWith("/reviews/albums/this-is-lorelei-the-singer-in-my-band/"))
    }

    @Test
    fun `a review is recognised by its shape, not by where the page put it`() {
        // Pitchfork reshuffling its containers must not silently empty the
        // list, so the walk looks for contentType + ratingValue + url wherever
        // they turn up rather than following a fixed path.
        val buried = """<html><script>window.__PRELOADED_STATE__ = {"a":{"b":[{"c":{"d":[
            ${indexed("Post", "Björk", "bjork-post", "9.0")}
        ]}}]}};</script></html>"""
        val items = pf.collectListing(org.json.JSONObject(pf.extractPreloadedState(buried)!!))
        assertEquals(1, items.size)
        assertEquals(9.0, items[0].score!!, 0.001)
    }

    @Test
    fun `the preloaded state survives a brace inside a review's own text`() {
        // The blob is found by matching braces, and a title or blurb containing
        // one would end a naive scan early.
        val html = """<html><script>window.__PRELOADED_STATE__ = {"t":"a } b {","items":[
            ${indexed("Kid A", "Radiohead", "radiohead-kid-a", "10.0")}
        ]};</script></html>"""
        val state = pf.extractPreloadedState(html)!!
        assertTrue("the scan stopped early: $state", state.endsWith("]}"))
        assertEquals(10.0, pf.collectListing(org.json.JSONObject(state))[0].score!!, 0.001)
    }

    @Test
    fun `Best New Music comes through the index too`() {
        val html = indexPage(indexed("Ants From Up There", "Black Country, New Road", "x-y", "9.0", bnm = true))
        assertTrue(pf.collectListing(org.json.JSONObject(pf.extractPreloadedState(html)!!))[0].isBestNewMusic)
    }

    @Test
    fun `a page with no preloaded state is not an error`() {
        assertNull(pf.extractPreloadedState("<html><body>nothing</body></html>"))
        assertNull(pf.extractPreloadedState(""))
    }

    @Test
    fun `the index is consulted before the review page is ever fetched`() {
        // The listing is one request, cached, and carries the score. Fetching a
        // review page per album to find a number that is not on it is what the
        // last two releases did.
        val indexPath = "/reviews/albums/"
        val (s, fake) = server(
            mapOf(
                indexPath to indexPage(
                    indexed("The Singer in My Band", "This Is Lorelei",
                        "this-is-lorelei-the-singer-in-my-band", "8.0")
                )
            )
        )
        try {
            val pf = Pitchfork(metadataHttpClient(), "test", s.url("/").toString().trimEnd('/'))
            val review = pf.reviewFor("The Singer in My Band", "This Is Lorelei")
            assertNotNull("the index should have answered it", review)
            assertEquals(8.0, review!!.score!!, 0.001)
            assertTrue(review.url.endsWith("/reviews/albums/this-is-lorelei-the-singer-in-my-band/"))
            assertEquals("one request, and it was the index", listOf(indexPath), fake.asked)
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun `the index is not allowed to answer for a different artist`() {
        // A covers record, a re-recording, a namesake. The index names the
        // artist properly, which is a better check than a slug read backwards.
        val indexPath = "/reviews/albums/"
        val (s, _) = server(
            mapOf(indexPath to indexPage(indexed("Mezzanine", "Somebody Else", "somebody-else-mezzanine", "6.0")))
        )
        try {
            val pf = Pitchfork(metadataHttpClient(), "test", s.url("/").toString().trimEnd('/'))
            assertNull(pf.reviewFor("Mezzanine", "Massive Attack"))
        } finally {
            s.shutdown()
        }
    }
}
