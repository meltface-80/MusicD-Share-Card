package com.musicd.sharecard

import com.musicd.sharecard.source.PlayState
import com.musicd.sharecard.source.Playing
import com.musicd.sharecard.source.Source
import com.musicd.sharecard.source.Sources
import com.musicd.sharecard.source.ZoneRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every room, each asked about itself — what the chooser grid is drawn from.
 *
 * The grid exists because answering "what is on in the house" with ONE card
 * meant picking a room and silently discarding the rest. Two rooms on is a
 * choice, and the person looking makes it.
 */
class RoomsTest {

    private class FakeSource(
        override val name: String,
        private val byZone: Map<String, Playing>
    ) : Source {
        override fun zones(): List<ZoneRef> =
            byZone.keys.map { ZoneRef(name, ZoneRef.idFor(name, it), it) }

        override fun nowPlaying(zoneId: String?): Playing? {
            if (zoneId != null) return byZone[zoneId]
            return byZone.values.firstOrNull { it.state.isPlaying } ?: byZone.values.firstOrNull()
        }
    }

    private fun playing(
        source: String,
        zone: String,
        album: String = "",
        artist: String = "",
        track: String = "",
        state: PlayState = PlayState.PLAYING
    ) = Playing(
        source = source,
        zoneId = ZoneRef.idFor(source, zone),
        zoneName = zone,
        album = album,
        artist = artist,
        track = track,
        state = state
    )

    @Test
    fun `a silent room is listed, not dropped`() {
        val sources = Sources(
            listOf(
                FakeSource(
                    "Sonos",
                    mapOf(
                        "Kitchen" to playing("Sonos", "Kitchen", "Blue", "Joni Mitchell"),
                        "Study" to playing("Sonos", "Study", state = PlayState.STOPPED)
                    )
                )
            )
        )

        val rooms = sources.rooms()
        assertEquals(2, rooms.size)
        // A grid of only the live rooms reads as the others having dropped off
        // the network, which is a different and much more alarming statement.
        val study = rooms.single { it.zone.name == "Study" }
        assertNull("a stopped room describing nothing has no answer", study.playing)
        assertEquals("Blue", rooms.single { it.zone.name == "Kitchen" }.playing?.album)
    }

    @Test
    fun `one room seen by two sources is ONE tile, and Roon supplies it`() {
        // THE REPORTED SHAPE, one layer up. Roon playing to a Sonos speaker is
        // seen by both, and both say "playing" — so a grid that counted zones
        // would draw two tiles for one piece of music, one of them headed with
        // a session id.
        val sources = Sources(
            listOf(
                FakeSource(
                    "Roon",
                    mapOf("Stereo Fives" to playing("Roon", "Stereo Fives", "Ænima", "TOOL"))
                ),
                FakeSource(
                    "Sonos",
                    mapOf(
                        "Stereo Fives" to playing(
                            "Sonos", "Stereo Fives",
                            track = "Roon19fd926d031b41739a2ba36854671c"
                        )
                    )
                )
            )
        )

        val rooms = sources.rooms()
        assertEquals("two sources, one room, one tile", 1, rooms.size)
        assertEquals("Roon", rooms[0].zone.source)
        assertEquals("Ænima", rooms[0].playing?.album)
    }

    @Test
    fun `two rooms genuinely on stay two tiles`() {
        val sources = Sources(
            listOf(
                FakeSource(
                    "Sonos",
                    mapOf(
                        "Kitchen" to playing("Sonos", "Kitchen", "Blue", "Joni Mitchell"),
                        "Study" to playing("Sonos", "Study", "Spiderland", "Slint")
                    )
                )
            )
        )

        val rooms = sources.rooms()
        assertEquals(2, rooms.count { it.playing?.state?.isPlaying == true })
    }

    @Test
    fun `a room collapses on its name however it is punctuated or cased`() {
        // Normalize.text is the one folding rule, so "Stereo Fives" and
        // "stereo-fives" are the same room and not two tiles.
        val sources = Sources(
            listOf(
                FakeSource(
                    "Roon",
                    mapOf("Stereo Fives" to playing("Roon", "Stereo Fives", "Ænima", "TOOL"))
                ),
                FakeSource(
                    "Sonos",
                    mapOf("stereo-fives" to playing("Sonos", "stereo-fives", track = "hash"))
                )
            )
        )

        assertEquals(1, sources.rooms().size)
    }

    @Test
    fun `a room under two DIFFERENT names is two tiles, and that limit is deliberate`() {
        // Nothing here can know that a Roon zone called Study and a Sonos room
        // called Office are one speaker. A spare tile is visible and tappable;
        // wrongly merging two real rooms would hide one of them.
        val sources = Sources(
            listOf(
                FakeSource(
                    "Roon",
                    mapOf("Study" to playing("Roon", "Study", "Ænima", "TOOL"))
                ),
                FakeSource(
                    "Sonos",
                    mapOf("Office" to playing("Sonos", "Office", track = "hash"))
                )
            )
        )

        assertEquals(2, sources.rooms().size)
    }

    @Test
    fun `a paused room is listed but does not count as on`() {
        val sources = Sources(
            listOf(
                FakeSource(
                    "Sonos",
                    mapOf(
                        "Kitchen" to playing("Sonos", "Kitchen", "Blue", "Joni Mitchell"),
                        "Study" to playing(
                            "Sonos", "Study", "Spiderland", "Slint", state = PlayState.PAUSED
                        )
                    )
                )
            )
        )

        val rooms = sources.rooms()
        assertEquals(2, rooms.size)
        // One room ON is not a choice, so this house still draws a card.
        assertEquals(1, rooms.count { it.playing?.state?.isPlaying == true })
        // But the paused room still knows its record, which the grid shows.
        assertTrue(rooms.any { it.zone.name == "Study" && it.playing?.album == "Spiderland" })
    }
}
