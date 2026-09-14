package com.musicd.sharecard

import com.musicd.sharecard.discover.NewMusic
import com.musicd.sharecard.discover.PlayHistory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * New records, and which of them this house has any reason to care about.
 *
 * NOT ONE BYTE OF EITHER ENDPOINT HAS BEEN SEEN FROM HERE — the proxy on this
 * machine answers 403 to the CONNECT for both hosts, checked rather than
 * assumed — so these are the documented shapes, pinned as fixtures. The socket
 * is kept out of the parsing precisely so that a wire which differs is one
 * function to correct; treat the first real run as the verification, and read
 * /api/debug first.
 */
class NewMusicTest {

    private fun heard(vararg acts: String) =
        acts.map { PlayHistory.Heard(it, "Some Record", 0L) }

    // ------------------------------------------------- ListenBrainz's window

    private val fresh = """
        {"payload":{"releases":[
          {"artist_credit_name":"Slint","release_name":"Spiderland II",
           "release_date":"2026-09-11",
           "caa_id":12345,"caa_release_mbid":"aaaa-bbbb-cccc-dddd"},
          {"artist_credit_name":"Some Stranger","release_name":"Their Record",
           "release_date":"2026-09-12","caa_id":999,"caa_release_mbid":"eeee-ffff"},
          {"artist_credit_name":"Björk","release_name":"New One",
           "release_date":"2026-09-13"}
        ]}}
    """.trimIndent()

    @Test
    fun `only records by acts this app has drawn a card for are kept`() {
        val picks = NewMusic.parseFreshReleases(fresh, heard("Slint"))
        assertEquals(
            "the endpoint answers with every release in the window; keeping the " +
                "ones whose act is in the history IS the feature",
            listOf("Spiderland II"), picks.map { it.album }
        )
        assertTrue(picks.first().heard)
        assertEquals("Because you played Slint", picks.first().why)
    }

    @Test
    fun `the act is matched by the same folding rule as everything else here`() {
        // Björk against Bjork, through Normalize — one folding rule, not a
        // second copy that drifts.
        val picks = NewMusic.parseFreshReleases(fresh, heard("Bjork"))
        assertEquals(listOf("New One"), picks.map { it.album })
    }

    @Test
    fun `an empty history keeps nothing at all`() {
        assertTrue(
            "with nothing heard there is no such thing as \"based on your " +
                "listening\", and inventing one would be the lie",
            NewMusic.parseFreshReleases(fresh, emptyList()).isEmpty()
        )
    }

    @Test
    fun `a row missing what it needs costs that row and not the screen`() {
        val ragged = """
            {"payload":{"releases":[
              {"release_name":"No Artist Named"},
              {"artist_credit_name":"Slint"},
              {"artist_credit_name":"Slint","release_name":"Kept","release_date":""},
              "not even an object"
            ]}}
        """.trimIndent()
        assertEquals(listOf("Kept"), NewMusic.parseFreshReleases(ragged, heard("Slint")).map { it.album })
    }

    @Test
    fun `the rows are found at the root too`() {
        // Lenient on purpose: nobody here has seen this payload, so it is
        // looked for in both places rather than one.
        val flat = """{"releases":[{"artist_credit_name":"Slint","release_name":"Flat"}]}"""
        assertEquals(listOf("Flat"), NewMusic.parseFreshReleases(flat, heard("Slint")).map { it.album })
    }

    @Test
    fun `nothing readable at all is an empty list, never a throw`() {
        assertTrue(NewMusic.parseFreshReleases("{}", heard("Slint")).isEmpty())
        assertTrue(NewMusic.parseFreshReleases("""{"payload":{}}""", heard("Slint")).isEmpty())
    }

    // ------------------------------------------------------------- the sleeve

    @Test
    fun `a sleeve needs both halves, because the id alone builds a 404`() {
        val row = JSONObject("""{"caa_id":12345,"caa_release_mbid":"aaaa-bbbb"}""")
        assertEquals(
            "https://archive.org/download/mbid-aaaa-bbbb/mbid-aaaa-bbbb-12345_thumb500.jpg",
            NewMusic.coverArtUrl(row)
        )
        assertNull(NewMusic.coverArtUrl(JSONObject("""{"caa_id":12345}""")))
        assertNull(NewMusic.coverArtUrl(JSONObject("""{"caa_release_mbid":"aaaa-bbbb"}""")))
    }

    @Test
    fun `a record with no sleeve is kept without one`() {
        // The screen draws a placeholder. A record worth knowing about does not
        // stop being one because nobody has uploaded the cover yet.
        val picks = NewMusic.parseFreshReleases(fresh, heard("Bjork"))
        assertEquals(1, picks.size)
        assertNull(picks.first().art)
    }

    @Test
    fun `neither half of a sleeve url may walk out of the path`() {
        // Same hazard the Lyrion cover id has a rule about: both halves go
        // straight into a path.
        assertNull(
            NewMusic.coverArtUrl(
                JSONObject("""{"caa_id":"../../etc/passwd","caa_release_mbid":"aaaa"}""")
            )
        )
        assertNull(
            NewMusic.coverArtUrl(
                JSONObject("""{"caa_id":"1","caa_release_mbid":"a/b"}""")
            )
        )
    }

    // ------------------------------------------------------ Deezer's editorial

    private val editorial = """
        {"data":[
          {"title":"Out Now","release_date":"2026-09-12",
           "artist":{"name":"Somebody Else"},
           "cover_xl":"https://e-cdns-images.dzcdn.net/images/cover/xl.jpg"},
          {"title":"No Artist Object"},
          {"title":"Small Cover Only","artist":{"name":"Another Act"},
           "cover":"https://e-cdns-images.dzcdn.net/images/cover/small.jpg"}
        ]}
    """.trimIndent()

    @Test
    fun `the editorial list is everybody's, and says so`() {
        val picks = NewMusic.parseDeezerReleases(editorial)
        assertEquals(listOf("Out Now", "Small Cover Only"), picks.map { it.album })
        assertTrue(
            "this list is the same for every household, so it must not claim to " +
                "be about anybody's listening",
            picks.all { !it.heard && it.why == "New this week" }
        )
    }

    @Test
    fun `the biggest sleeve available is taken, and any beats none`() {
        val picks = NewMusic.parseDeezerReleases(editorial)
        assertTrue(picks[0].art!!.endsWith("xl.jpg"))
        assertTrue(picks[1].art!!.endsWith("small.jpg"))
    }

    @Test
    fun `an unreadable editorial answer is empty rather than a throw`() {
        assertTrue(NewMusic.parseDeezerReleases("{}").isEmpty())
    }

    // ------------------------------------------------------------ the ladder

    private fun engine(history: PlayHistory, answers: Map<String, String>) = NewMusic(
        http = okhttp3.OkHttpClient(),
        userAgent = "test",
        history = history,
        fetchText = { url -> answers.entries.firstOrNull { url.contains(it.key) }?.value },
        today = { "2026-09-14" }
    )

    @Test
    fun `what you have heard comes first and the editorial list fills the rest`() {
        val history = PlayHistory.inMemory()
        history.remember("Slint", "Spiderland")
        val picks = engine(history, mapOf("listenbrainz" to fresh, "deezer" to editorial)).picks()
        assertEquals(
            listOf("Spiderland II", "Out Now", "Small Cover Only"),
            picks.map { it.album }
        )
        assertTrue("a record by an act this house plays must never be pushed out",
            picks.first().heard)
    }

    @Test
    fun `a first run is the editorial list and does not pretend otherwise`() {
        val picks = engine(
            PlayHistory.inMemory(),
            mapOf("listenbrainz" to fresh, "deezer" to editorial)
        ).picks()
        assertTrue(
            "with an empty history the honest answer is \"I do not know you yet\"",
            picks.all { !it.heard }
        )
        assertEquals(listOf("Out Now", "Small Cover Only"), picks.map { it.album })
    }

    @Test
    fun `neither source answering is an empty screen, not a failure`() {
        val engine = engine(PlayHistory.inMemory(), emptyMap())
        assertTrue(engine.picks().isEmpty())
        assertTrue(
            "an empty screen says nothing about why, so the diagnostics must",
            engine.attempts().isNotEmpty()
        )
    }
}
