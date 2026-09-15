package com.musicd.sharecard

import com.musicd.sharecard.lms.LmsClient
import com.musicd.sharecard.lms.LmsQueue
import com.musicd.sharecard.sonos.soapHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Queueing a suggestion into a Lyrion player.
 *
 * DRIVEN THROUGH A REAL HTTP SERVER, like [LmsSourceTest] and for its reason:
 * the JSON-RPC envelope this app builds is the most likely thing to be wrong
 * against real hardware, and a stubbed client would never look at it. What the
 * wire carries is asserted directly — above all that the verb is `cmd:add` and
 * never `cmd:load`, because those two words are one letter apart and a very
 * long way apart in what they do to a room somebody is listening to.
 *
 * The decisions that can be wrong on their own — which string to search for,
 * which row is the record, whether the server actually added anything — are
 * pure functions and are tested as such.
 */
class LmsQueueTest {

    private val server = MockWebServer()

    /** Every request body, so the envelope can be asserted. */
    private val asked = mutableListOf<String>()

    /** Search term (as it appears in the body) -> the rows to answer with. */
    private var library: (String) -> String = { """{"result":{"albums_loop":[]}}""" }

    /** What playlistcontrol answers. */
    private var added: String = """{"result":{"count":11}}"""

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                asked += body
                val answer = when {
                    body.contains("playlistcontrol") -> added
                    body.contains("\"albums\"") -> library(body)
                    else -> """{"result":{}}"""
                }
                return MockResponse().setBody(answer)
                    .setHeader("Content-Type", "application/json")
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun base() = "http://${server.hostName}:${server.port}"

    private fun queue(found: String? = null) = LmsQueue(LmsClient(soapHttpClient())) { found ?: base() }

    private fun rows(vararg pairs: Triple<String, String, String>) =
        """{"result":{"albums_loop":[""" +
            pairs.joinToString(",") { (id, album, artist) ->
                """{"id":"$id","album":"$album","artist":"$artist"}"""
            } + "]}}"

    private val searches: List<String> get() = asked.filter { it.contains("\"albums\"") }

    // ------------------------------------------------------------- the wire

    @Test
    fun `it appends rather than replacing what is playing`() {
        library = { rows(Triple("42", "Spiderland", "Slint")) }
        val outcome = queue().queueAlbum("Spiderland", "Slint", "00:04:20:aa:bb:cc")
        assertTrue(outcome.detail, outcome.queued)

        val control = asked.single { it.contains("playlistcontrol") }
        // cmd:add appends. cmd:load replaces and plays; cmd:insert plays next.
        assertTrue(control, control.contains("cmd:add"))
        assertFalse(control, control.contains("cmd:load"))
        assertFalse(control, control.contains("cmd:insert"))
        // The album, by id, and the player in the slot LMS reads it from.
        assertTrue(control, control.contains("album_id:42"))
        assertTrue(control, control.contains("\"params\":[\"00:04:20:aa:bb:cc\""))
        assertTrue(control, control.contains("\"method\":\"slim.request\""))
    }

    @Test
    fun `the search asks for albums with the artist tag`() {
        library = { rows(Triple("42", "Spiderland", "Slint")) }
        queue().queueAlbum("Spiderland", "Slint", "p1")
        val search = searches.first()
        assertTrue(search, search.contains("\"albums\",0,"))
        assertTrue(search, search.contains("search:Slint Spiderland"))
        // WITHOUT THE ARTIST TAG THE ROWS NAME NOBODY, which reads exactly like
        // a library that files everything anonymously — and those are matched on
        // title alone. The tag string is what decides that.
        assertTrue(search, search.contains("tags:la"))
    }

    @Test
    fun `an ordinary hit costs one search`() {
        library = { rows(Triple("42", "Spiderland", "Slint")) }
        queue().queueAlbum("Spiderland", "Slint", "p1")
        assertEquals(1, searches.size)
    }

    @Test
    fun `the ladder tries the album alone when act plus album finds nothing`() {
        // A library that files it under a name the suggestion does not carry.
        library = { body ->
            if (body.contains("search:Spiderland\"")) rows(Triple("7", "Spiderland", "Slint"))
            else rows()
        }
        val outcome = queue().queueAlbum("Spiderland", "Slint", "p1")
        assertTrue(outcome.detail, outcome.queued)
        assertEquals(2, searches.size)
        assertTrue(asked.any { it.contains("album_id:7") })
    }

    @Test
    fun `a record spelled Deezer's way is searched for plainly`() {
        // Deezer sells "Ignition (2008 Remaster)"; the library calls it "Ignition".
        library = { rows(Triple("9", "Ignition", "The Offspring")) }
        val outcome = queue().queueAlbum("Ignition (2008 Remaster)", "The Offspring", "p1")
        assertTrue(outcome.detail, outcome.queued)
        val search = searches.first()
        assertTrue(search, search.contains("search:The Offspring Ignition\""))
        assertFalse(search, search.contains("2008 Remaster"))
    }

    @Test
    fun `a server that added nothing is a failure, not a success`() {
        library = { rows(Triple("42", "Spiderland", "Slint")) }
        added = """{"result":{"count":0}}"""
        val outcome = queue().queueAlbum("Spiderland", "Slint", "p1")
        // THE ROON BUG THIS EXISTS TO NOT REPEAT: the final call was never read,
        // so a refusal reached the page as "Added to the end of the queue".
        assertFalse(outcome.detail, outcome.queued)
        assertTrue(outcome.detail, outcome.detail.contains("added nothing"))
    }

    @Test
    fun `a playlistcontrol that never answers is a failure`() {
        library = { rows(Triple("42", "Spiderland", "Slint")) }
        added = "not json at all"
        val outcome = queue().queueAlbum("Spiderland", "Slint", "p1")
        assertFalse(outcome.detail, outcome.queued)
    }

    @Test
    fun `a record the library does not hold says so and touches nothing`() {
        library = { rows(Triple("42", "Cult", "Static-X")) }
        val outcome = queue().queueAlbum("Cult", "To-Die-For", "p1")
        assertFalse(outcome.detail, outcome.queued)
        assertTrue(outcome.detail, outcome.detail.contains("Lyrion library"))
        assertTrue(asked.none { it.contains("playlistcontrol") })
    }

    @Test
    fun `with no server nothing is asked at all`() {
        val outcome = LmsQueue(LmsClient(soapHttpClient())) { null }
            .queueAlbum("Spiderland", "Slint", "p1")
        assertFalse(outcome.detail, outcome.queued)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `an unnamed player is refused before the search`() {
        val outcome = queue().queueAlbum("Spiderland", "Slint", "  ")
        assertFalse(outcome.detail, outcome.queued)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `the attempts name the step, because there cannot be another test`() {
        library = { rows(Triple("42", "Cult", "Static-X")) }
        val q = queue()
        q.queueAlbum("Cult", "To-Die-For", "p1")
        val notes = q.attempts().joinToString("\n")
        // "Not in your library" and "it is right there and the match refused it"
        // are the same sentence from outside, so the rows Lyrion sent are in it.
        assertTrue(notes, notes.contains("Static-X"))
        assertTrue(notes, notes.contains("no match"))
    }

    // -------------------------------------------------- what to search for

    @Test
    fun `the terms run most specific first`() {
        val terms = LmsQueue.searchTerms("Ignition (2008 Remaster)", "The Offspring / Noodles")
        assertEquals("The Offspring Ignition", terms[0])
        assertEquals("Ignition", terms[1])
        // And the suggestion exactly as it was spelled, last, in case the
        // library is the one carrying the edition.
        assertEquals("The Offspring / Noodles Ignition (2008 Remaster)", terms[2])
    }

    @Test
    fun `an album with no artist does not search for a leading space`() {
        val terms = LmsQueue.searchTerms("Spiderland", "")
        assertEquals(listOf("Spiderland"), terms)
    }

    @Test
    fun `identical rungs are not asked twice`() {
        // Nothing to strip and nothing to cut, so the third rung — the pair
        // exactly as the suggestion spelled it — IS the first, and is dropped.
        // The album alone stays: an artist is extra words to fail on, which is
        // the whole reason that rung exists.
        assertEquals(listOf("Slint Spiderland", "Spiderland"), LmsQueue.searchTerms("Spiderland", "Slint"))
    }

    // ------------------------------------------------------ which row it is

    private fun row(id: String, album: String, artist: String?): JSONObject {
        val o = JSONObject().put("id", id).put("album", album)
        if (artist != null) o.put("artist", artist)
        return o
    }

    @Test
    fun `both names must overlap where the row names an artist`() {
        val rows = listOf(row("1", "Cult", "Static-X"), row("2", "Cult", "To/Die/For"))
        assertEquals("2", LmsQueue.pickAlbum(rows, "Cult", "To/Die/For"))
    }

    @Test
    fun `a row naming somebody else is refused outright`() {
        // The pair this repo has already been burned by, one lookup over.
        val rows = listOf(row("1", "Cult", "Static-X"))
        assertNull(LmsQueue.pickAlbum(rows, "Cult", "To/Die/For"))
    }

    @Test
    fun `a row naming nobody is matched on its title alone`() {
        // A box set, a soundtrack, something filed under no artist at all.
        val rows = listOf(row("1", "Spiderland", null))
        assertEquals("1", LmsQueue.pickAlbum(rows, "Spiderland", "Slint"))
    }

    @Test
    fun `two anonymous candidates is a coin toss and neither is taken`() {
        val rows = listOf(row("1", "Spiderland", ""), row("2", "Spiderland", null))
        assertNull(LmsQueue.pickAlbum(rows, "Spiderland", "Slint"))
    }

    @Test
    fun `a named match beats an anonymous one`() {
        val rows = listOf(row("1", "Spiderland", null), row("2", "Spiderland", "Slint"))
        assertEquals("2", LmsQueue.pickAlbum(rows, "Spiderland", "Slint"))
    }

    @Test
    fun `a row with no id is not a candidate`() {
        val rows = listOf(JSONObject().put("album", "Spiderland").put("artist", "Slint"))
        assertNull(LmsQueue.pickAlbum(rows, "Spiderland", "Slint"))
    }

    @Test
    fun `the edition comes off both sides before they are compared`() {
        val rows = listOf(row("3", "Ignition", "The Offspring"))
        assertEquals("3", LmsQueue.pickAlbum(rows, "Ignition (2008 Remaster)", "The Offspring"))
    }

    // ------------------------------------------------------- reading a reply

    @Test
    fun `the rows come out of albums_loop`() {
        val reply = JSONObject("""{"albums_loop":[{"id":"1"},{"id":"2"}]}""")
        assertEquals(2, LmsQueue.rows(reply).size)
    }

    @Test
    fun `a reply with no loop at all is no rows, not a failure`() {
        assertEquals(0, LmsQueue.rows(JSONObject("""{"count":0}""")).size)
    }

    @Test
    fun `a count written as a string is still a count`() {
        // LMS writes its numbers as strings as often as not — the same family
        // as LmsClient.truthy, which exists because optBoolean reads 1 as false.
        assertEquals(11, LmsQueue.count(JSONObject("""{"count":"11"}""")))
        assertEquals(11, LmsQueue.count(JSONObject("""{"count":11}""")))
    }

    @Test
    fun `a count that cannot be read is zero, so it fails loudly`() {
        assertEquals(0, LmsQueue.count(JSONObject("""{"count":"lots"}""")))
        assertEquals(0, LmsQueue.count(JSONObject("{}")))
    }
}
