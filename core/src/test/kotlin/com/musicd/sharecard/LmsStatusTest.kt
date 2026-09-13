package com.musicd.sharecard

import com.musicd.sharecard.lms.LmsClient
import com.musicd.sharecard.lms.LmsStatus
import com.musicd.sharecard.source.PlayState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a Lyrion `status` answer.
 *
 * No Lyrion server is reachable from here, so this is where the protocol
 * actually gets exercised: the responses below are assembled by hand from the
 * documented command set. If a real server words something differently, this
 * is the file that says so — and one function to correct, rather than a socket
 * to debug from another room.
 */
class LmsStatusTest {

    /** A local file, which is the simple case: everything is in the loop entry. */
    private val localFile = JSONObject(
        """
        {
          "player_name": "Kitchen",
          "mode": "play",
          "time": 42.5,
          "playlist_loop": [{
            "playlist index": 0,
            "id": 12345,
            "title": "Good Morning, Captain",
            "artist": "Slint",
            "album": "Spiderland",
            "coverid": "778899",
            "duration": 449
          }]
        }
        """.trimIndent()
    )

    /**
     * A radio stream, which is the awkward case: the loop entry is the STATION
     * and remoteMeta is the SONG.
     */
    private val radio = JSONObject(
        """
        {
          "mode": "play",
          "playlist_loop": [{
            "id": -140455328,
            "title": "BBC Radio 6 Music",
            "remote": 1,
            "artwork_url": "http://cdn.example/station.png"
          }],
          "remoteMeta": {
            "title": "Windowlicker",
            "artist": "Aphex Twin",
            "album": "Windowlicker",
            "remote": 1,
            "artwork_url": "http://cdn.example/windowlicker.jpg"
          }
        }
        """.trimIndent()
    )

    @Test
    fun `a local file gives the record and a cover built from its id`() {
        val now = LmsStatus.read(localFile, "http://attic:9000")
        assertEquals("Spiderland", now.album)
        assertEquals("Slint", now.artist)
        assertEquals("Good Morning, Captain", now.title)
        assertEquals(PlayState.PLAYING, now.state)
        assertFalse(now.isStream)
        assertEquals("http://attic:9000/music/778899/cover.jpg", now.artUrl)
    }

    @Test
    fun `a stream is headed with the SONG, never the station`() {
        // The other way round draws a card headed "BBC Radio 6 Music" while the
        // server knows it is playing Aphex Twin — right-looking and wrong.
        val now = LmsStatus.read(radio, "http://attic:9000")
        assertEquals("Windowlicker", now.title)
        assertEquals("Aphex Twin", now.artist)
        assertEquals("Windowlicker", now.album)
        assertTrue(now.isStream)
        assertEquals("http://cdn.example/windowlicker.jpg", now.artUrl)
    }

    @Test
    fun `the station's artwork survives a remoteMeta that carries none`() {
        val thin = JSONObject(radio.toString())
        thin.getJSONObject("remoteMeta").put("artwork_url", "")
        val now = LmsStatus.read(thin, "http://attic:9000")
        assertEquals("Windowlicker", now.title)
        // An empty field is not an answer, so the station's picture stands
        // rather than the card losing its cover entirely.
        assertEquals("http://cdn.example/station.png", now.artUrl)
    }

    @Test
    fun `a relative artwork_url is resolved against the server`() {
        val track = JSONObject().put("artwork_url", "/imageproxy/abc/image.png")
        assertEquals(
            "http://attic:9000/imageproxy/abc/image.png",
            LmsStatus.artUrl("http://attic:9000", track)
        )
    }

    @Test
    fun `a remote track's negative id never becomes a cover path`() {
        // LMS numbers anything remote with a negative id. Pasting one into
        // /music/<id>/cover.jpg builds a URL that 404s on every card.
        val track = JSONObject().put("id", -140455328)
        assertEquals("", LmsStatus.artUrl("http://attic:9000", track))
    }

    @Test
    fun `a hex coverid is a cover, not a reason to give up`() {
        // REPORTED: Lyrion zones detected, album, artist and blurb all right,
        // and no cover at all on any of them. coverid is an OPAQUE token and
        // current Lyrion writes it as hex — the first rule here demanded
        // digits, so every local track lost its cover.
        val track = JSONObject().put("coverid", "8f3a2b1c")
        assertEquals(
            "http://attic:9000/music/8f3a2b1c/cover.jpg",
            LmsStatus.artUrl("http://attic:9000", track)
        )
    }

    @Test
    fun `OBSERVED - the real coverid from the field builds the real url`() {
        // Not reasoned from documentation: this is a coverid captured from a
        // live Lyrion on a DietPi box, and the URL asserted is the one the
        // UPnP source had already built from the same server's DIDL while the
        // Lyrion source was returning nothing. Hex, which is exactly what the
        // digits-only rule threw away.
        val track = JSONObject().put("coverid", "c8536003")
        assertEquals(
            "http://192.168.0.57:9000/music/c8536003/cover.jpg",
            LmsStatus.artUrl("http://192.168.0.57:9000", track)
        )
    }

    @Test
    fun `an unusable coverid falls through to the next candidate`() {
        // AND THIS IS THE HALF THAT MADE IT TOTAL. The old code took the first
        // PRESENT of the three keys and then checked it, so a coverid it could
        // not use ended the search there — with artwork_track_id sitting right
        // beside it, never looked at.
        val track = JSONObject()
            .put("coverid", "-140455328")
            .put("artwork_track_id", 99321)
        assertEquals(
            "http://attic:9000/music/99321/cover.jpg",
            LmsStatus.artUrl("http://attic:9000", track)
        )
    }

    @Test
    fun `a cover id may not walk out of its own path`() {
        for (bad in listOf("../../etc/passwd", "a/b", "a.b", "-1", "", " ")) {
            assertFalse(bad, LmsStatus.usableCoverId(bad))
        }
        for (good in listOf("12345", "8f3a2b1c", "abcDEF123")) {
            assertTrue(good, LmsStatus.usableCoverId(good))
        }
    }

    @Test
    fun `no artwork at all is empty, not a broken path`() {
        assertEquals("", LmsStatus.artUrl("http://attic:9000", JSONObject()))
    }

    @Test
    fun `the artist is found wherever this server put it`() {
        val onlyAlbumArtist = JSONObject()
            .put("playlist_loop", org.json.JSONArray().put(
                JSONObject().put("title", "Venus as a Boy").put("albumartist", "Björk")
            ))
            .put("mode", "play")
        assertEquals("Björk", LmsStatus.read(onlyAlbumArtist, "http://attic:9000").artist)
    }

    @Test
    fun `every transport word maps, and an unknown one is not silently stopped`() {
        fun state(mode: String) =
            LmsStatus.read(JSONObject().put("mode", mode), "http://x").state
        assertEquals(PlayState.PLAYING, state("play"))
        assertEquals(PlayState.PAUSED, state("pause"))
        assertEquals(PlayState.STOPPED, state("stop"))
        // UNKNOWN, not STOPPED: the chooser treats them differently, and
        // guessing "stopped" would hide a room that is playing.
        assertEquals(PlayState.UNKNOWN, state("buffering"))
    }

    @Test
    fun `a player list keeps the ones that are asleep`() {
        val result = JSONObject(
            """
            {"players_loop":[
              {"playerid":"00:04:20:aa:bb:cc","name":"Kitchen","connected":1},
              {"playerid":"00:04:20:dd:ee:ff","name":"Study","connected":0},
              {"name":"nameless, no id"}
            ]}
            """.trimIndent()
        )
        val players = LmsClient.readPlayers(result)
        // A Squeezebox that is asleep is still a room in the house.
        assertEquals(2, players.size)
        assertTrue(players.any { it.name == "Study" && !it.connected })
        assertTrue(players.any { it.name == "Kitchen" && it.connected })
    }

    @Test
    fun `connected reads as true when LMS writes it as the number one`() {
        // optBoolean reads the number 1 as false, which would mark a whole
        // household asleep.
        assertTrue(LmsClient.truthy(1))
        assertTrue(LmsClient.truthy("1"))
        assertTrue(LmsClient.truthy(true))
        assertFalse(LmsClient.truthy(0))
        assertFalse(LmsClient.truthy(null))
    }
}
