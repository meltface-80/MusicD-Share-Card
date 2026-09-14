package com.musicd.sharecard

import com.musicd.sharecard.discover.Editorial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the press has been reviewing, read as RECORDS rather than as articles.
 *
 * THE LINE THIS FILE GUARDS is the one the whole screen rests on: a headline is
 * used to identify a record and is then thrown away, the `description` is never
 * read at all, and what survives is a link with the publisher's name on it. The
 * prose is theirs in any wrapper, and the safest way not to publish somebody's
 * writing is not to hold it.
 *
 * NEITHER FEED HAS BEEN REACHED FROM HERE — the proxy answers 403 to the
 * CONNECT for both hosts, checked rather than assumed — so these are the
 * documented shapes as fixtures. Treat the first real run as the verification.
 */
class EditorialTest {

    private val pitchfork = Editorial.FEEDS.first { it.name == "Pitchfork" }
    private val nme = Editorial.FEEDS.first { it.name == "NME" }

    // ------------------------------------------------------- the two shapes

    @Test
    fun `Pitchfork writes Artist colon Album`() {
        assertEquals(
            "Slint" to "Spiderland",
            Editorial.split("Slint: Spiderland", Editorial.Companion.Shape.COLON)
        )
    }

    @Test
    fun `the FIRST colon splits it, because an ALBUM often carries one`() {
        // An album with a subtitle is ordinary; an artist with a colon in their
        // name is rare enough that nobody here can name one. Split at the last
        // and the album's own subtitle lands on the end of the artist — a name
        // that matches nothing and a row of links about nobody.
        assertEquals(
            "Godspeed You! Black Emperor" to "F# A# Infinity: A Reissue",
            Editorial.split(
                "Godspeed You! Black Emperor: F# A# Infinity: A Reissue",
                Editorial.Companion.Shape.COLON
            )
        )
    }

    @Test
    fun `NME quotes the album and dashes it off the artist`() {
        assertEquals(
            "Wolf Alice" to "The Clearing",
            Editorial.split(
                "Wolf Alice – ‘The Clearing’ review: a fine return",
                Editorial.Companion.Shape.QUOTED
            )
        )
        // Straight quotes and a plain hyphen, because publishers use both.
        assertEquals(
            "Wolf Alice" to "The Clearing",
            Editorial.split("Wolf Alice - 'The Clearing' review", Editorial.Companion.Shape.QUOTED)
        )
    }

    @Test
    fun `a headline that names no record is skipped rather than guessed at`() {
        // The cost of guessing is a sleeve and a set of links belonging to
        // somebody else's record sitting under somebody's review.
        assertNull(Editorial.split("The Strange World of...", Editorial.Companion.Shape.COLON))
        assertNull(Editorial.split("Best albums of the year so far", Editorial.Companion.Shape.QUOTED))
        assertNull(Editorial.split(": nothing before it", Editorial.Companion.Shape.COLON))
        assertNull(Editorial.split("nothing after it:", Editorial.Companion.Shape.COLON))
        assertNull(Editorial.split("", Editorial.Companion.Shape.COLON))
    }

    // ------------------------------------------------------------- the feed

    private val feed = """
        <rss><channel>
          <item>
            <title><![CDATA[Slint: Spiderland]]></title>
            <link>https://pitchfork.com/reviews/albums/slint-spiderland/?utm=x</link>
            <description><![CDATA[<p>Three paragraphs of somebody's writing.</p>]]></description>
            <media:content url="https://media.pitchfork.com/photos/cover.jpg" />
          </item>
          <item>
            <title>Pitchfork's 50 best albums of the decade</title>
            <link>https://pitchfork.com/features/lists/50-best/</link>
          </item>
          <item>
            <title>Low: Things We Lost in the Fire</title>
            <link>https://pitchfork.com/reviews/albums/low-things-we-lost/</link>
          </item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun `only the album reviews are taken, by their own link`() {
        val rows = Editorial.parse(feed, pitchfork)
        assertEquals(listOf("Spiderland", "Things We Lost in the Fire"), rows.map { it.album })
        assertEquals(listOf("Slint", "Low"), rows.map { it.artist })
        assertTrue(
            "a publisher putting a feature in a review feed is ordinary, and the " +
                "link's own path is what tells them apart",
            rows.none { it.album.contains("best albums") }
        )
    }

    @Test
    fun `the tracking query is not part of the link`() {
        assertEquals(
            "https://pitchfork.com/reviews/albums/slint-spiderland/",
            Editorial.parse(feed, pitchfork).first().url
        )
    }

    @Test
    fun `not one word of the article is kept`() {
        // The description is never read. Not to excerpt, not to shorten, not
        // for a hover: the safest way not to publish somebody's writing is not
        // to hold it, and this asserts it rather than trusting it.
        val rows = Editorial.parse(feed, pitchfork)
        val everything = rows.joinToString(" ") { "${it.artist} ${it.album} ${it.source} ${it.url}" }
        assertTrue(
            "the feed's description reached a field it should never reach",
            !everything.contains("paragraphs") && !everything.contains("somebody's writing")
        )
    }

    @Test
    fun `the publisher is named on every record it supplied`() {
        assertTrue(Editorial.parse(feed, pitchfork).all { it.source == "Pitchfork" })
    }

    // ------------------------------------------------------------ the sleeve

    @Test
    fun `a sleeve is taken from wherever the feed puts one`() {
        assertEquals(
            "https://media.pitchfork.com/photos/cover.jpg",
            Editorial.parse(feed, pitchfork).first().art
        )
        assertEquals(
            "https://x/thumb.jpg",
            Editorial.imageIn("""<media:thumbnail url="https://x/thumb.jpg" />""")
        )
        assertEquals(
            "https://x/enc.jpg",
            Editorial.imageIn("""<enclosure url="https://x/enc.jpg" type="image/jpeg" />""")
        )
    }

    @Test
    fun `a record with no sleeve is still a record`() {
        // The grid draws a placeholder, and the review is still worth reaching.
        assertNull(Editorial.parse(feed, pitchfork)[1].art)
        assertEquals(2, Editorial.parse(feed, pitchfork).size)
    }

    @Test
    fun `an http image is refused, because this page is served over https too`() {
        assertNull(Editorial.imageIn("""<media:content url="http://x/insecure.jpg" />"""))
    }

    @Test
    fun `nothing readable at all is an empty list, never a throw`() {
        assertTrue(Editorial.parse("", pitchfork).isEmpty())
        assertTrue(Editorial.parse("<rss><channel></channel></rss>", nme).isEmpty())
        assertTrue(Editorial.parse("not xml at all", pitchfork).isEmpty())
    }

    @Test
    fun `every feed is an album-review feed with a shape declared for it`() {
        // Declared per feed rather than guessed. The Quietus and Bandcamp Daily
        // were left out on purpose: they mix features, lists and interviews
        // into one feed, so their headlines are prose and name no record.
        assertTrue(Editorial.FEEDS.isNotEmpty())
        assertTrue(Editorial.FEEDS.all { it.url.startsWith("https://") })
        assertTrue(Editorial.FEEDS.all { it.mustContain.isNotBlank() })
    }

    // ------------------------------------------------------------- the shelf

    @Test
    fun `the feeds are read once an hour, not once a visit`() {
        // Two people opening Discover a minute apart are asking the same
        // question, and a review feed turns over slowly. Fetching both on
        // every call spends somebody else's bandwidth to be told the same
        // thing, which is the no-polling rule wearing another hat.
        val seen = ArrayList<String>()
        val press = Editorial(
            http = okhttp3.OkHttpClient(),
            userAgent = "test",
            fetchText = { url -> seen.add(url); if (url.contains("pitchfork")) feed else "" }
        )
        assertEquals(2, press.recent().size)
        val first = seen.size
        assertTrue("the first read has to actually ask", first >= Editorial.FEEDS.size)
        press.recent()
        press.recent()
        assertEquals("and no read after it may ask again", first, seen.size)

        press.forget()
        press.recent()
        assertTrue("Refresh forces, like every other source here", seen.size > first)
    }
}
