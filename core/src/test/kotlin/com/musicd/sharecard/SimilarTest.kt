package com.musicd.sharecard

import com.musicd.sharecard.meta.Similar
import com.musicd.sharecard.meta.metadataHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acts to hear next, and the order the two sources are asked in.
 *
 * WHAT THIS CAN AND CANNOT ESTABLISH. Both hosts are answered by a
 * MockWebServer here, so what is exercised is the whole lookup — the URLs
 * built, the JSON read, the fallback, the album chosen for each act — against
 * responses shaped like the real ones. What it CANNOT establish is that the
 * real ListenBrainz answers that shape at all: its similarity endpoint is
 * named after the dataset behind it and those names change, and the network on
 * the machine this was written on refuses both hosts outright. That is exactly
 * why Deezer sits behind it rather than beside it, and why [Similar.attempts]
 * says which one answered.
 */
class SimilarTest {

    private val servers = ArrayList<MockWebServer>()

    @After
    fun tearDown() = servers.forEach { it.shutdown() }

    /** One server standing in for whichever host, routed by path. */
    private fun serve(answer: (RecordedRequest) -> MockResponse): MockWebServer {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = answer(request)
        }
        server.start()
        servers += server
        return server
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)
    private fun missing() = MockResponse().setResponseCode(404).setBody("")

    private fun base(server: MockWebServer) =
        server.url("/").toString().trimEnd('/')

    private val listenBrainzAnswer = """
        [
          {"artist_mbid": "aaaa-1", "name": "Bark Psychosis", "score": 91},
          {"artist_mbid": "aaaa-2", "name": "Slint", "score": 88}
        ]
    """.trimIndent()

    /** MusicBrainz release groups: a studio album, a live record, a comp. */
    private fun releaseGroups(first: String, firstYear: String) = """
        {"release-groups": [
          {"title": "$first", "first-release-date": "$firstYear", "secondary-types": []},
          {"title": "Later One", "first-release-date": "2001-05-05", "secondary-types": []},
          {"title": "A Live Record", "first-release-date": "1979-01-01",
           "secondary-types": ["Live"]}
        ]}
    """.trimIndent()

    @Test
    fun `ListenBrainz answers and each act gets its earliest studio album`() {
        val host = serve { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/similar-artists/json") -> ok(listenBrainzAnswer)
                path.startsWith("/ws/2/release-group") && path.contains("aaaa-1") ->
                    ok(releaseGroups("Hex", "1994-02-14"))
                path.startsWith("/ws/2/release-group") && path.contains("aaaa-2") ->
                    ok(releaseGroups("Spiderland", "1991-03-27"))
                else -> missing()
            }
        }
        val similar = Similar(
            metadataHttpClient(), "test",
            listenBrainz = base(host), musicBrainz = base(host), deezer = base(host)
        )

        val acts = similar.forArtist("Talk Talk", "the-real-mbid")
        assertEquals(listOf("Bark Psychosis", "Slint"), acts.map { it.name })
        assertEquals(listOf("Hex", "Spiderland"), acts.map { it.album })
        // The earliest STUDIO album: the live record is older and is not it.
        assertEquals(listOf(1994, 1991), acts.map { it.year })
        assertTrue(similar.attempts().any { it.contains("listenbrainz") })
    }

    @Test
    fun `the artist id is what ListenBrainz is asked with`() {
        var asked: String? = null
        val host = serve { request ->
            val path = request.path.orEmpty()
            if (path.startsWith("/similar-artists/json")) {
                asked = path
                ok(listenBrainzAnswer)
            } else ok("""{"release-groups": []}""")
        }
        Similar(
            metadataHttpClient(), "test",
            listenBrainz = base(host), musicBrainz = base(host), deezer = base(host)
        ).forArtist("Talk Talk", "9a3b-mbid")

        assertTrue("the MusicBrainz id has to be in the query", asked!!.contains("9a3b-mbid"))
        assertTrue("and so does the dataset name", asked!!.contains("algorithm="))
    }

    @Test
    fun `an act whose records cannot be named keeps its name`() {
        // The row degrades to names rather than disappearing: an act worth
        // hearing is worth showing even when MusicBrainz has nothing filed.
        val host = serve { request ->
            if (request.path.orEmpty().startsWith("/similar-artists/json")) ok(listenBrainzAnswer)
            else missing()
        }
        val acts = Similar(
            metadataHttpClient(), "test",
            listenBrainz = base(host), musicBrainz = base(host), deezer = base(host)
        ).forArtist("Talk Talk", "mbid")

        assertEquals(listOf("Bark Psychosis", "Slint"), acts.map { it.name })
        assertEquals(listOf(null, null), acts.map { it.album })
    }

    @Test
    fun `Deezer answers when ListenBrainz does not`() {
        val host = serve { request ->
            val path = request.path.orEmpty()
            when {
                // The shape the fragile one fails in: a 400 for a dataset name
                // that is no longer current.
                path.startsWith("/similar-artists/json") ->
                    MockResponse().setResponseCode(400).setBody("unknown algorithm")
                path.startsWith("/search/artist") ->
                    ok("""{"data": [{"id": 4050, "name": "Talk Talk"}]}""")
                path.startsWith("/artist/4050/related") ->
                    ok("""{"data": [{"id": 77, "name": "Bark Psychosis"}]}""")
                path.startsWith("/artist/77/albums") -> ok(
                    """{"data": [
                         {"title": "Hex", "release_date": "1994-02-14"},
                         {"title": "Codename Dustsucker", "release_date": "2004-09-06"}
                       ]}"""
                )
                else -> missing()
            }
        }
        val similar = Similar(
            metadataHttpClient(), "test",
            listenBrainz = base(host), musicBrainz = base(host), deezer = base(host)
        )

        val acts = similar.forArtist("Talk Talk", "mbid")
        assertEquals(listOf("Bark Psychosis"), acts.map { it.name })
        assertEquals("Hex", acts[0].album)
        assertEquals(1994, acts[0].year)
        assertTrue(
            "the diagnostics have to say the first one fell through",
            similar.attempts().any { it.contains("falling through") }
        )
    }

    @Test
    fun `Deezer's top hit has to actually be the artist asked for`() {
        // A search that returns SOMETHING is not evidence it returned this act.
        // Without this guard a row of suggestions is about somebody else and
        // nothing downstream could tell.
        val host = serve { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/similar-artists/json") -> missing()
                path.startsWith("/search/artist") ->
                    ok("""{"data": [{"id": 9, "name": "The Guess Who"}]}""")
                else -> ok("""{"data": [{"id": 1, "name": "Somebody Else"}]}""")
            }
        }
        val similar = Similar(
            metadataHttpClient(), "test",
            listenBrainz = base(host), musicBrainz = base(host), deezer = base(host)
        )

        assertTrue(similar.forArtist("The Who", "mbid").isEmpty())
        assertTrue(similar.attempts().any { it.contains("not this artist") })
    }

    @Test
    fun `no MusicBrainz id means ListenBrainz is not asked at all`() {
        // An id resolved by searching a name is how a row ends up being about a
        // different act that shares one. Skip, and say so.
        var lbCalls = 0
        val host = serve { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/similar-artists/json") -> { lbCalls++; ok(listenBrainzAnswer) }
                path.startsWith("/search/artist") ->
                    ok("""{"data": [{"id": 4050, "name": "Talk Talk"}]}""")
                path.startsWith("/artist/4050/related") -> ok("""{"data": []}""")
                else -> missing()
            }
        }
        val similar = Similar(
            metadataHttpClient(), "test",
            listenBrainz = base(host), musicBrainz = base(host), deezer = base(host)
        )

        similar.forArtist("Talk Talk", null)
        assertEquals("ListenBrainz must not be asked without an id", 0, lbCalls)
        assertTrue(similar.attempts().any { it.contains("no MusicBrainz id") })
    }

    @Test
    fun `an answer is remembered, including an empty one`() {
        // A record with no similar acts costs the same requests to find that
        // out as one with plenty.
        var calls = 0
        val host = serve {
            calls++
            missing()
        }
        val similar = Similar(
            metadataHttpClient(), "test",
            listenBrainz = base(host), musicBrainz = base(host), deezer = base(host)
        )

        assertTrue(similar.forArtist("Talk Talk", "mbid").isEmpty())
        val afterFirst = calls
        assertTrue(similar.forArtist("Talk Talk", "mbid").isEmpty())
        assertEquals("the empty answer must come off the shelf", afterFirst, calls)
    }

    @Test
    fun `nothing is looked up for an artist with no name`() {
        val host = serve { missing() }
        val similar = Similar(
            metadataHttpClient(), "test",
            listenBrainz = base(host), musicBrainz = base(host), deezer = base(host)
        )
        assertTrue(similar.forArtist("", "mbid").isEmpty())
        assertTrue(similar.forArtist("   ", null).isEmpty())
    }

    @Test
    fun `ListenBrainz's answer is read however deeply it is nested`() {
        // It has answered with a bare array and with an array whose first
        // element is the query echoed back. Walking by shape rather than down
        // a fixed path means a reshuffle empties nothing.
        val similar = Similar(metadataHttpClient(), "test")
        val nested = """[[{"artist_mbid": "x", "name": "Slint"}]]"""
        assertEquals(listOf("Slint"), similar.readListenBrainz(nested).map { it.name })
        // An entry with no id or no name is skipped rather than drawn blank.
        val ragged = """[{"name": "No Id"}, {"artist_mbid": "y"}, {"artist_mbid": "z", "name": "Bark Psychosis"}]"""
        assertEquals(listOf("Bark Psychosis"), similar.readListenBrainz(ragged).map { it.name })
        // And rubbish is empty, not an exception.
        assertTrue(similar.readListenBrainz("not json at all").isEmpty())
    }
}
