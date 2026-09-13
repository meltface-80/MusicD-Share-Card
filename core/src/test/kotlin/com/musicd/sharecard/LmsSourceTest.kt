package com.musicd.sharecard

import com.musicd.sharecard.lms.LmsClient
import com.musicd.sharecard.lms.LmsDiscovery
import com.musicd.sharecard.lms.LmsSource
import com.musicd.sharecard.sonos.soapHttpClient
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
 * The Lyrion source, driven through a real HTTP server.
 *
 * MockWebServer rather than a stubbed client, so the JSON-RPC envelope itself
 * is exercised: a request this app builds wrongly is the single most likely way
 * this breaks against real hardware, and a fake client would never notice.
 */
class LmsSourceTest {

    private val server = MockWebServer()

    /** What each player is playing, keyed by player id. */
    private val playing = mutableMapOf(
        "00:04:20:aa:bb:cc" to track("play", "Spiderland", "Slint", "Good Morning, Captain"),
        "00:04:20:dd:ee:ff" to track("stop", "", "", "")
    )

    private var playersBody = """
        {"result":{"players_loop":[
          {"playerid":"00:04:20:aa:bb:cc","name":"Kitchen","connected":1},
          {"playerid":"00:04:20:dd:ee:ff","name":"Study","connected":1}
        ]}}
    """.trimIndent()

    /** Every request this app makes, so the envelope can be asserted. */
    private val asked = mutableListOf<String>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                asked += body
                if (body.contains("\"players\"")) {
                    return MockResponse().setBody(playersBody)
                        .setHeader("Content-Type", "application/json")
                }
                val player = playing.keys.firstOrNull { body.contains(it) }
                return MockResponse()
                    .setBody(playing[player] ?: """{"result":{}}""")
                    .setHeader("Content-Type", "application/json")
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun track(mode: String, album: String, artist: String, title: String) = """
        {"result":{"mode":"$mode","playlist_loop":[
          {"id":12345,"title":"$title","artist":"$artist","album":"$album","coverid":"778899"}
        ]}}
    """.trimIndent()

    /** A source pointed at the fake server by a configured address. */
    private fun source(discovered: List<LmsDiscovery.Server> = emptyList()) = LmsSource(
        client = LmsClient(soapHttpClient()),
        hosts = { listOf(server.hostName) },
        discover = { discovered },
        port = server.port
    )

    @Test
    fun `the players become zones`() {
        val zones = source().zones()
        assertEquals(2, zones.size)
        assertTrue(zones.all { it.source == "Lyrion" })
        assertEquals(
            listOf("Kitchen", "Study"),
            zones.map { it.name }
        )
        // Prefixed, so two sources cannot collide on one room.
        assertTrue(zones.all { it.id.startsWith("lyrion:") })
    }

    @Test
    fun `it asks the documented JSON-RPC envelope`() {
        source().zones()
        val players = asked.first { it.contains("\"players\"") }
        // The shape LMS documents: a player slot, then the command array.
        assertTrue(players, players.contains("\"method\":\"slim.request\""))
        assertTrue(players, players.contains("\"params\":[\"\",[\"players\""))
    }

    @Test
    fun `a status request names the player and asks for one current track`() {
        source().nowPlaying("00:04:20:aa:bb:cc")
        val status = asked.first { it.contains("\"status\"") }
        assertTrue(status, status.contains("\"00:04:20:aa:bb:cc\""))
        // "-" is LMS's own word for the track playing now.
        assertTrue(status, status.contains("\"status\",\"-\",1"))
        assertTrue(status, status.contains("tags:"))
    }

    @Test
    fun `whatever is playing finds the room that is on`() {
        val now = source().nowPlaying(null)
        assertEquals("Spiderland", now?.album)
        assertEquals("Kitchen", now?.zoneName)
    }

    @Test
    fun `a named player that is idle answers for itself, not for the neighbour`() {
        // Each zone is independent — the same lock every other source applies.
        val now = source().nowPlaying("00:04:20:dd:ee:ff")
        assertTrue(
            "it must not describe the room that IS playing",
            now == null || now.album.isEmpty()
        )
    }

    @Test
    fun `the art proxy is told about the server, and only the server`() {
        val lms = source()
        lms.zones()
        assertEquals(listOf(server.hostName), lms.artHosts().toList())
    }

    @Test
    fun `a broadcast answer is used and reported`() {
        val lms = source(listOf(LmsDiscovery.Server(server.hostName, server.port, "attic")))
        assertEquals(2, lms.zones().size)
        val notes = lms.diagnostics()
        assertTrue(notes.toString(), notes.any { it.contains("broadcast") && it.contains("attic") })
    }

    @Test
    fun `a server that answers with no players is reported rather than kept`() {
        playersBody = """{"result":{"players_loop":[]}}"""
        val lms = source()
        assertTrue(lms.zones().isEmpty())
        assertNull(lms.nowPlaying(null))
        assertTrue(
            lms.diagnostics().toString(),
            lms.diagnostics().any { it.contains("no players") || it.contains("none answered") }
        )
    }

    @Test
    fun `nothing configured and nothing found says so instead of throwing`() {
        val lms = LmsSource(LmsClient(soapHttpClient()), hosts = { emptyList() }, discover = { emptyList() })
        assertTrue(lms.zones().isEmpty())
        assertNull(lms.nowPlaying(null))
        assertTrue(
            lms.diagnostics().toString(),
            lms.diagnostics().any { it.contains("no address configured") }
        )
    }
}
