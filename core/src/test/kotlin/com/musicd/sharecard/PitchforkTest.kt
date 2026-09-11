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
}
