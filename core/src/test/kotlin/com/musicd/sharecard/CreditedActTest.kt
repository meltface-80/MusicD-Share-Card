package com.musicd.sharecard

import com.musicd.sharecard.meta.Metadata
import com.musicd.sharecard.meta.Pitchfork
import com.musicd.sharecard.meta.metadataHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * THE REVIEW LOOKUPS ASK ABOUT THE FIRST CREDITED ACT, NOT THE WHOLE CREDIT.
 *
 * Reported with a photograph: a Roon card of "Western Stars" credited
 * "Bruce Springsteen / Scott Tibbs" (Tibbs arranged the strings) drew no
 * blurb, no year, no Wikipedia chip and no Pitchfork score, only the two
 * AllMusic links that need no lookup. Every lookup was asked about an act
 * called "Bruce Springsteen / Scott Tibbs", and there is no such act:
 * MusicBrainz searched for that artist phrase, the Wikipedia guard wanted
 * that run of words in the article, and Pitchfork built
 * `/bruce-springsteen-scott-tibbs-western-stars/`.
 *
 * The links row fixed this with [com.musicd.sharecard.library.Normalize
 * .primaryArtist] after the Stan Getz credit. The lookups were left alone on
 * the grounds that `namesOverlap` accepts a name qualified on the right, and
 * that stopped being true when `albumArticleFits` began reading the article
 * through `mentions`, which needs the WHOLE credit in the prose. Roon credits
 * arrangers, composers and guests routinely, so this looked like reviews
 * coming and going at random.
 */
class CreditedActTest {

    private val asked = CopyOnWriteArrayList<String>()
    private val server = MockWebServer()

    private val springsteen = "Bruce Springsteen / Scott Tibbs"

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                asked += path
                val body = when {
                    path.startsWith("/ws/2/release-group/") &&
                        !path.contains("Tibbs") -> """
                        {"release-groups":[
                          {"id":"rg","score":100,"title":"Western Stars","primary-type":"Album",
                           "first-release-date":"2019-06-14",
                           "artist-credit":[{"artist":{"id":"bs","name":"Bruce Springsteen"}}]}
                        ]}"""
                    path.startsWith("/w/api.php") -> """
                        {"query":{"search":[{"title":"Western Stars"},{"title":"Bruce Springsteen"}]}}"""
                    path == "/api/rest_v1/page/summary/Western_Stars" -> """
                        {"type":"standard","extract":"Western Stars is the nineteenth studio album by American singer-songwriter Bruce Springsteen, released in 2019."}"""
                    path == "/api/rest_v1/page/summary/Bruce_Springsteen" -> """
                        {"type":"standard","extract":"Bruce Frederick Joseph Springsteen is an American singer, songwriter and musician."}"""
                    path == "/reviews/albums/bruce-springsteen-western-stars/" ->
                        """<html><head><script type="application/ld+json">
                           {"@type":"Review","ratingValue":"7.1"}
                           </script></head></html>"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private val base get() = server.url("/").toString().trimEnd('/')

    @Test
    fun `a two-name credit still finds the year and both blurbs`() {
        val extras = Metadata(
            metadataHttpClient(), "test/1.0",
            musicBrainzBase = base, wikipediaBase = base
        ).extras("Western Stars", springsteen)

        assertEquals("asked: $asked", 2019, extras.year)
        assertNotNull("no album blurb; asked: $asked", extras.album)
        assertTrue(extras.album!!.url.orEmpty().endsWith("/wiki/Western_Stars"))
        assertNotNull("no artist blurb; asked: $asked", extras.artist)
        assertEquals("bs", extras.artistMbid)
    }

    @Test
    fun `a two-name credit still finds the Pitchfork score`() {
        val review = Pitchfork(metadataHttpClient(), "test", base)
            .reviewFor("Western Stars", springsteen)

        assertNotNull("no review; asked: $asked", review)
        assertEquals(7.1, review!!.score!!, 0.001)
    }

    /** And the cached read agrees with the lookup that filled it. */
    @Test
    fun `the fast path finds what the slow one stored`() {
        val pf = Pitchfork(metadataHttpClient(), "test", base)
        pf.reviewFor("Western Stars", springsteen)
        assertNotNull(pf.cachedReviewFor("Western Stars", springsteen))

        val meta = Metadata(
            metadataHttpClient(), "test/1.0",
            musicBrainzBase = base, wikipediaBase = base
        )
        meta.extras("Western Stars", springsteen)
        assertNotNull(meta.cachedExtras("Western Stars", springsteen)?.album)
    }
}
