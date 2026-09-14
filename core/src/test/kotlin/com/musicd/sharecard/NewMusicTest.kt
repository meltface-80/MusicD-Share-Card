package com.musicd.sharecard

import com.musicd.sharecard.discover.Editorial
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

    /**
     * An unmatched URL answers with the EMPTY STRING, never null.
     *
     * Null falls through to the real fetch, and a test that reaches the
     * network is a test that fails for somebody else's reason. The keys are
     * PATHS rather than hostnames, because the sleeve lookup and the
     * new-release list are both on api.deezer.com and matching "deezer" would
     * hand one the other's payload.
     */
    private fun engine(
        history: PlayHistory,
        answers: Map<String, String>,
        press: Editorial? = null,
        counting: MutableList<String>? = null
    ) = NewMusic(
        http = okhttp3.OkHttpClient(),
        userAgent = "test",
        history = history,
        editorial = press,
        fetchText = { url ->
            counting?.add(url)
            answers.entries.firstOrNull { url.contains(it.key) }?.value ?: ""
        },
        today = { "2026-09-14" }
    )

    /**
     * The press, with a feed answer stubbed in and no socket anywhere.
     *
     * An unmatched feed answers with the EMPTY STRING rather than null: null
     * falls through to the real fetch, and a test that reaches the network is
     * a test that fails for somebody else's reason.
     */
    private fun press(answers: Map<String, String>) = Editorial(
        http = okhttp3.OkHttpClient(),
        userAgent = "test",
        fetchText = { url -> answers.entries.firstOrNull { url.contains(it.key) }?.value ?: "" }
    )

    private val reviewFeed = """
        <rss><channel>
          <item>
            <title><![CDATA[Low: Things We Lost in the Fire]]></title>
            <link>https://pitchfork.com/reviews/albums/low-things-we-lost/</link>
            <description><![CDATA[Three paragraphs of somebody's writing.]]></description>
            <media:content url="https://media.pitchfork.com/photos/cover.jpg" />
          </item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun `what you have heard comes first and the editorial list fills the rest`() {
        val history = PlayHistory.inMemory()
        history.remember("Slint", "Spiderland")
        val picks = engine(history, mapOf("listenbrainz" to fresh, "editorial/0/releases" to editorial)).picks()
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
            mapOf("listenbrainz" to fresh, "editorial/0/releases" to editorial)
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


    @Test
    fun `the press sits between what you have heard and everybody's new releases`() {
        // The order runs from most personal to least: a record by an act this
        // house plays, then somebody's judgement, then a release calendar.
        val history = PlayHistory.inMemory()
        history.remember("Slint", "Spiderland")
        val picks = engine(
            history,
            mapOf("listenbrainz" to fresh, "editorial/0/releases" to editorial),
            press(mapOf("pitchfork.com" to reviewFeed))
        ).picks()
        assertEquals(
            listOf("Spiderland II", "Things We Lost in the Fire", "Out Now", "Small Cover Only"),
            picks.map { it.album }
        )
    }

    @Test
    fun `a reviewed record carries the link and the publisher's name, and nothing else`() {
        val pick = engine(
            PlayHistory.inMemory(),
            emptyMap(),
            press(mapOf("pitchfork.com" to reviewFeed))
        ).picks().first()
        assertEquals("https://pitchfork.com/reviews/albums/low-things-we-lost/", pick.readAt)
        assertEquals("Pitchfork", pick.readAtName)
        assertEquals("Reviewed by Pitchfork", pick.why)
        assertTrue(
            "a record the press picked is not one this house has played, and " +
                "saying otherwise would be the lie the whole screen rests on",
            !pick.heard
        )
        assertTrue(
            "not one word of the article may reach a field",
            listOf(pick.why, pick.album, pick.artist).none { it.contains("paragraphs") }
        )
    }

    @Test
    fun `no press configured is simply the ladder without that rung`() {
        val picks = engine(
            PlayHistory.inMemory(),
            mapOf("editorial/0/releases" to editorial),
            press = null
        ).picks()
        assertEquals(listOf("Out Now", "Small Cover Only"), picks.map { it.album })
    }

    // ------------------------------------------------------------ the sleeve

    /*
     * Deezer's album search, as `/search/album` answers it. THE SECOND ROW IS
     * THE RIGHT RECORD ON PURPOSE: a search's first row is not the answer,
     * which is the lesson QobuzAlbum.pick and the Deezer artist search are
     * both already written from.
     */
    private val albumSearch = """
        {"data":[
          {"title":"Spiderland Revisited","artist":{"name":"Somebody Else"},
           "cover_xl":"https://cdn.deezer.com/wrong_xl.jpg"},
          {"title":"Spiderland","artist":{"name":"Slint"},
           "cover_xl":"https://cdn.deezer.com/right_xl.jpg",
           "cover_big":"https://cdn.deezer.com/right_big.jpg"}
        ]}
    """.trimIndent()

    @Test
    fun `a sleeve is taken only when BOTH names match, never off the first row`() {
        assertEquals(
            "https://cdn.deezer.com/right_big.jpg",
            NewMusic.pickSleeve(albumSearch, "Slint", "Spiderland")
        )
    }

    @Test
    fun `a record Deezer does not carry gets no sleeve rather than a wrong one`() {
        // A press shot or somebody else's cover under an album title says the
        // app knows which record this is when it does not. The tile draws a
        // placeholder instead, which is honest.
        assertEquals("", NewMusic.pickSleeve(albumSearch, "Low", "Things We Lost in the Fire"))
        assertEquals(
            "the act has to match too, or a record sharing a title takes the sleeve",
            "", NewMusic.pickSleeve(albumSearch, "Static-X", "Spiderland")
        )
        assertEquals("", NewMusic.pickSleeve("""{"data":[]}""", "Slint", "Spiderland"))
    }

    @Test
    fun `a tile-sized sleeve is taken, not a wall-sized one`() {
        // Deezer's big is 500px and its xl is 1000px. A tile is about 115px on
        // a phone; twelve xl covers at once is what made the art proxy's shelf
        // too small to hold one screen.
        assertEquals(
            "https://cdn.deezer.com/right_big.jpg",
            NewMusic.pickSleeve(albumSearch, "Slint", "Spiderland")
        )
        val onlyXl = """
            {"data":[{"title":"Spiderland","artist":{"name":"Slint"},
                      "cover_xl":"https://cdn.deezer.com/x.jpg"}]}
        """.trimIndent()
        assertEquals(
            "a sleeve at any size beats a blank tile",
            "https://cdn.deezer.com/x.jpg", NewMusic.pickSleeve(onlyXl, "Slint", "Spiderland")
        )
    }

    @Test
    fun `the sleeve comes from the RECORD, not from the article beside it`() {
        /*
         * Reported on the first real run as "no album artwork". The picture in
         * a review's feed is whatever the publisher put at the top of the page
         * — for NME usually a press shot — so it is the fallback, and the
         * record's own cover wins.
         */
        val press = press(mapOf("pitchfork.com" to reviewFeed))
        val picks = engine(
            PlayHistory.inMemory(),
            mapOf("search/album" to lowSearch),
            press
        ).picks()
        assertEquals(
            "https://cdn.deezer.com/low_big.jpg", picks.first().art
        )
    }

    private val lowSearch = """
        {"data":[{"title":"Things We Lost in the Fire","artist":{"name":"Low"},
                  "cover_big":"https://cdn.deezer.com/low_big.jpg"}]}
    """.trimIndent()

    @Test
    fun `the feed's own picture is kept only where the record has no sleeve`() {
        val press = press(mapOf("pitchfork.com" to reviewFeed))
        val picks = engine(PlayHistory.inMemory(), emptyMap(), press).picks()
        assertEquals(
            "a record Deezer does not carry keeps whatever the feed sent",
            "https://media.pitchfork.com/photos/cover.jpg", picks.first().art
        )
    }

    // ------------------------------------------------------------- the shelf

    @Test
    fun `the whole screen is remembered, so a tab switch asks nobody anything`() {
        /*
         * Opening Discover cost a ListenBrainz window, two feeds, a Deezer
         * list and a sleeve lookup per record — EVERY time, including tapping
         * Playing and tapping back. Same fault as the empty sweep that cost
         * forty-seven seconds a page load: an answer expensive to get and not
         * kept.
         */
        val seen = ArrayList<String>()
        val engine = engine(
            PlayHistory.inMemory(),
            mapOf("editorial/0/releases" to editorial),
            counting = seen
        )
        engine.picks()
        val first = seen.size
        assertTrue("the first look has to actually ask", first > 0)
        engine.picks()
        engine.picks()
        assertEquals("and no look after it may ask again", first, seen.size)
    }

    @Test
    fun `Refresh forces, which is the bargain every source here makes`() {
        val seen = ArrayList<String>()
        val engine = engine(
            PlayHistory.inMemory(),
            mapOf("editorial/0/releases" to editorial),
            counting = seen
        )
        engine.picks()
        val first = seen.size
        engine.forget()
        engine.picks()
        assertTrue("forget() must send it back to the network", seen.size > first)
    }

    @Test
    fun `playing something new reshapes the screen at once, not at the end of a TTL`() {
        // "Based on your listening" has to follow the listening, so the key is
        // the history rather than a constant.
        val history = PlayHistory.inMemory()
        val seen = ArrayList<String>()
        val engine = engine(
            history,
            mapOf("listenbrainz" to fresh, "editorial/0/releases" to editorial),
            counting = seen
        )
        engine.picks()
        val first = seen.size
        history.remember("Slint", "Spiderland")
        val after = engine.picks()
        assertTrue("a record played since the last look must rebuild it", seen.size > first)
        assertEquals("Spiderland II", after.first().album)
    }

    @Test
    fun `a sleeve is looked up once per record and then remembered`() {
        val seen = ArrayList<String>()
        val engine = engine(
            PlayHistory.inMemory(),
            mapOf("editorial/0/releases" to editorial, "search/album" to lowSearch),
            counting = seen
        )
        engine.picks()
        engine.forget()
        val before = seen.count { it.contains("search/album") }
        engine.picks()
        assertEquals(
            "forget() throws away the SCREEN; a record's cover does not change, " +
                "so the sleeve shelf must survive it",
            before, seen.count { it.contains("search/album") }
        )
    }
}
