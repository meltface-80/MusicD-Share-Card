package com.musicd.sharecard

import com.musicd.sharecard.meta.Metadata
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE YEAR ON THE CARD IS THE RECORD'S, NOT THE PRESSING'S.
 *
 * Reported from the field with a photograph: Big Star's "#1 Record" drawn as
 * **RELEASED 2003**. It came out in 1972, and 2003 is a reissue.
 *
 * The cause is the question that was being asked. A RELEASE search answers
 * with PRESSINGS — every CD, LP and remaster MusicBrainz holds — and this took
 * the earliest of the first five that came back. Five is nothing for a record
 * that has been reissued for fifty years, and the search is ordered by text
 * relevance, not by date: every pressing of one album scores the same, so
 * which five arrive is arbitrary and the 1972 original is routinely not among
 * them. The answer was then confidently wrong and there was no way to see it.
 *
 * A RELEASE GROUP is the record itself, and MusicBrainz maintains
 * `first-release-date` on it as the answer to exactly this question. One
 * request, no window, no arithmetic over pressings.
 *
 * DRIVEN THROUGH A REAL SERVER, for [LmsQueueTest]'s reason: musicbrainz.org
 * answers 403 to the CONNECT from this working environment, so the URL this
 * app builds is the part most likely to be wrong and a stubbed fetch would
 * never look at it. What is NOT verified here is that MusicBrainz's own search
 * response carries `first-release-date` — that shape is documented rather than
 * observed, which is why the release search is kept as the fallback below and
 * why the diagnostics say which of the two answered.
 */
class MusicBrainzYearTest {

    private val server = MockWebServer()

    /** Every path asked for, so the question itself can be asserted. */
    private val asked = mutableListOf<String>()

    private var releaseGroups: String = "{}"
    private var releases: String = "{}"

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                asked += path
                val body = when {
                    path.startsWith("/ws/2/release-group/") -> releaseGroups
                    path.startsWith("/ws/2/release/") -> releases
                    else -> "{}"
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun metadata() = Metadata(
        OkHttpClient(),
        "test/1.0",
        musicBrainzBase = server.url("/").toString().trimEnd('/')
    )

    /**
     * The window that shipped the bug: five reissues, no original. Dated the
     * way MusicBrainz dates them, and scored the way it scores a title every
     * pressing shares.
     */
    private val reissuesOnly = """
        {"releases":[
          {"score":100,"date":"2003-05-20","artist-credit":[{"artist":{"id":"bs","name":"Big Star"}}]},
          {"score":100,"date":"2009-11-02","artist-credit":[{"artist":{"id":"bs","name":"Big Star"}}]},
          {"score":100,"date":"2012-03-01","artist-credit":[{"artist":{"id":"bs","name":"Big Star"}}]},
          {"score":100,"date":"2014-07-15","artist-credit":[{"artist":{"id":"bs","name":"Big Star"}}]},
          {"score":98,"date":"2016-01-01","artist-credit":[{"artist":{"id":"bs","name":"Big Star"}}]}
        ]}
    """.trimIndent()

    private val theRecord = """
        {"release-groups":[
          {"id":"rg1","score":100,"title":"#1 Record","primary-type":"Album",
           "first-release-date":"1972-04-24",
           "artist-credit":[{"artist":{"id":"bs","name":"Big Star"}}]}
        ]}
    """.trimIndent()

    @Test
    fun `the year is the record's first release, not the earliest reissue in the window`() {
        releaseGroups = theRecord
        releases = reissuesOnly

        val answer = metadata().musicBrainzRelease("#1 Record", "Big Star")

        assertEquals(1972, answer.year)
        assertEquals("bs", answer.artistMbid)
    }

    @Test
    fun `the release group is what gets asked for`() {
        releaseGroups = theRecord
        metadata().musicBrainzRelease("#1 Record", "Big Star")

        assertTrue(
            "asked: $asked",
            asked.any { it.startsWith("/ws/2/release-group/") }
        )
    }

    /**
     * The shape above is DOCUMENTED, not observed, so the old question is kept
     * behind it. A release-group search that answers with nothing usable must
     * leave the card exactly as well off as it was before this change.
     */
    @Test
    fun `a release group search that answers nothing falls back to the pressings`() {
        releaseGroups = """{"release-groups":[]}"""
        releases = reissuesOnly

        val answer = metadata().musicBrainzRelease("#1 Record", "Big Star")

        assertEquals(2003, answer.year)
        assertEquals("bs", answer.artistMbid)
        assertTrue("asked: $asked", asked.any { it.startsWith("/ws/2/release/") })
    }

    /**
     * A SEARCH'S FIRST ROW IS NOT THE ANSWER, and a release group is no
     * exception — the same rule `QobuzAlbum.pick` and the Deezer artist search
     * are written from. A group whose title is a different record is refused
     * rather than dated.
     */
    @Test
    fun `a release group naming another record is not this record's year`() {
        releaseGroups = """
            {"release-groups":[
              {"id":"rg9","score":100,"title":"Radio City","primary-type":"Album",
               "first-release-date":"1974-02-01",
               "artist-credit":[{"artist":{"id":"bs","name":"Big Star"}}]}
            ]}
        """.trimIndent()
        releases = """{"releases":[]}"""

        assertNull(metadata().musicBrainzRelease("#1 Record", "Big Star").year)
    }

    /**
     * And the hash is folded on the way in, so the group MusicBrainz files as
     * "#1 Record" and a speaker reporting "Number 1 Record" are one record.
     */
    @Test
    fun `a hash title still matches its spelled-out twin`() {
        releaseGroups = theRecord
        assertEquals(1972, metadata().musicBrainzRelease("Number 1 Record", "Big Star").year)
    }
}
