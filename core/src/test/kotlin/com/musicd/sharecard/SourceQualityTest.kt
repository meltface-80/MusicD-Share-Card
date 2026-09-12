package com.musicd.sharecard

import com.musicd.sharecard.source.PlayState
import com.musicd.sharecard.source.Playing
import com.musicd.sharecard.source.Source
import com.musicd.sharecard.source.Sources
import com.musicd.sharecard.source.ZoneRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The best answer wins, not the first one.
 *
 * REPORTED FROM A REAL INSTALL, TWICE. Roon streaming to a Sonos speaker is
 * seen by BOTH sources, and both say "playing" — so returning the first playing
 * answer returned whichever happened to be asked first. With the Sonos zone
 * picked in the dropdown, that was a card headed
 * "Roon19fd926d031b41739a2ba36854671c" while Roon sat there knowing it was
 * Ænima by TOOL.
 *
 * Source order alone could never have fixed it: the chosen zone was asked
 * before any source order applied. Ranking the ANSWER is what does.
 */
class SourceQualityTest {

    private class FakeSource(
        override val name: String,
        private val byZone: Map<String, Playing>
    ) : Source {
        var asked = 0
        override fun zones(): List<ZoneRef> =
            byZone.keys.map { ZoneRef(name, ZoneRef.idFor(name, it), it) }

        override fun nowPlaying(zoneId: String?): Playing? {
            asked++
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

    /** Exactly the reported failure: the Sonos zone is the one picked. */
    @Test
    fun `Roon wins for a room the user picked on Sonos`() {
        val sources = Sources(
            listOf(
                FakeSource(
                    "Roon",
                    mapOf("Stereo Fives" to playing("Roon", "Stereo Fives", "Ænima", "TOOL"))
                ),
                FakeSource(
                    "Sonos",
                    // What the speaker sees of a Roon stream once the session id
                    // has been stripped: playing, and describing nothing.
                    mapOf("Stereo Fives" to playing("Sonos", "Stereo Fives"))
                )
            )
        )
        val best = sources.nowPlaying("sonos:Stereo Fives")
        assertEquals("Roon", best?.source)
        assertEquals("Ænima", best?.album)
        assertEquals("TOOL", best?.artist)
    }

    /**
     * And it must hold even when the session id is NOT recognised as one — the
     * point of ranking the answer rather than trusting a pattern to catch every
     * shape of rubbish.
     */
    @Test
    fun `a hash that slipped through still loses to a real album and artist`() {
        val sources = Sources(
            listOf(
                FakeSource(
                    "Roon",
                    mapOf("Fives" to playing("Roon", "Fives", "Ænima", "TOOL"))
                ),
                FakeSource(
                    "Sonos",
                    mapOf("Fives" to playing("Sonos", "Fives", album = "Roon19fd926d031b4173x"))
                )
            )
        )
        assertEquals("Ænima", sources.nowPlaying("sonos:Fives")?.album)
    }

    @Test
    fun `a full answer from the chosen room short-circuits, disturbing nobody`() {
        val roon = FakeSource(
            "Roon", mapOf("Fives" to playing("Roon", "Fives", "Ænima", "TOOL"))
        )
        val sonos = FakeSource("Sonos", mapOf("Kitchen" to playing("Sonos", "Kitchen", "Blue", "Joni")))
        val sources = Sources(listOf(roon, sonos))

        assertEquals("Ænima", sources.nowPlaying("roon:Fives")?.album)
        assertEquals("the other source must not be asked", 0, sonos.asked)
    }

    @Test
    fun `a playing room still beats a fuller paused one`() {
        // Quality decides between equals; it does not outrank being on.
        val sources = Sources(
            listOf(
                FakeSource(
                    "Roon",
                    mapOf("Study" to playing("Roon", "Study", "Old", "Artist", state = PlayState.PAUSED))
                ),
                FakeSource(
                    "Sonos",
                    mapOf("Kitchen" to playing("Sonos", "Kitchen", "Now", "Someone"))
                )
            )
        )
        assertEquals("Now", sources.nowPlaying()?.album)
    }

    @Test
    fun `an answer describing nothing at all is not offered as a card`() {
        val sources = Sources(
            listOf(FakeSource("Sonos", mapOf("Fives" to playing("Sonos", "Fives"))))
        )
        val best = sources.nowPlaying("sonos:Fives")
        // Either nothing, or something — but never a card with no words on it.
        assertTrue(best == null || !best.isEmpty)
    }

    @Test
    fun `an artist with no album beats a title with neither`() {
        val sources = Sources(
            listOf(
                FakeSource("Roon", mapOf("A" to playing("Roon", "A", track = "Some Track"))),
                FakeSource("Sonos", mapOf("B" to playing("Sonos", "B", artist = "Bill Callahan")))
            )
        )
        assertEquals("Bill Callahan", sources.nowPlaying()?.artist)
    }
}
