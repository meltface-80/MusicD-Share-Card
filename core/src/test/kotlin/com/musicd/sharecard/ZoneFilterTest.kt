package com.musicd.sharecard

import com.musicd.sharecard.source.PlayState
import com.musicd.sharecard.source.Playing
import com.musicd.sharecard.source.Source
import com.musicd.sharecard.source.Sources
import com.musicd.sharecard.source.ZoneRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rooms are OPT-IN, and switched off means silent rather than merely hidden.
 *
 * Hiding a room in the picker while still asking it on every refresh would be
 * the easy half of this and the wrong one: the point of the zones screen is
 * that a device nobody wants a card from is not interrogated at all. So every
 * one of these asserts the SPEAKER is not reached, not just that a name is
 * missing from a list.
 */
class ZoneFilterTest {

    private class FakeSource(
        override val name: String,
        private val playing: Map<String, Playing> = emptyMap()
    ) : Source {
        /** Which rooms this source was actually asked about. */
        val askedFor = ArrayList<String?>()

        override fun start() {}

        override fun zones(): List<ZoneRef> =
            playing.keys.map { ZoneRef(name, ZoneRef.idFor(name, it), it) }

        override fun nowPlaying(zoneId: String?): Playing? {
            askedFor += zoneId
            if (zoneId != null) return playing[zoneId]
            return playing.values.firstOrNull { it.state.isPlaying }
                ?: playing.values.firstOrNull { !it.isEmpty }
        }

        override fun artHosts() = listOf("$name.local")
    }

    private fun card(source: String, zone: String, album: String, playing: Boolean = true) =
        Playing(
            source = source,
            zoneId = ZoneRef.idFor(source, zone),
            zoneName = zone,
            album = album,
            artist = "Someone",
            state = if (playing) PlayState.PLAYING else PlayState.PAUSED
        )

    private fun sonos(vararg rooms: Pair<String, Playing>) =
        FakeSource("Sonos", rooms.toMap())

    // ------------------------------------------------------------ the list

    @Test
    fun `a fresh install can see every room and may answer about none`() {
        val sources = Sources(listOf(sonos("Kitchen" to card("Sonos", "Kitchen", "Spiderland"))))
        // Nothing enabled, which is what an empty settings file means.
        sources.zoneFilter = { false }

        // allZones is what the settings screen lists: the room is FOUND.
        assertEquals(listOf("Kitchen"), sources.allZones().map { it.name })
        // zones() is what everything else sees, and it is empty — so the page
        // says "enable a zone" rather than "no players found".
        assertTrue(sources.zones().isEmpty())
        assertTrue(!sources.anyZones())
    }

    @Test
    fun `enabling one room lets that one through and no others`() {
        val sources = Sources(
            listOf(
                sonos(
                    "Kitchen" to card("Sonos", "Kitchen", "Spiderland"),
                    "Lounge" to card("Sonos", "Lounge", "Loveless")
                )
            )
        )
        val kitchen = ZoneRef.idFor("Sonos", "Kitchen")
        sources.zoneFilter = { it == kitchen }

        assertEquals(listOf("Kitchen"), sources.zones().map { it.name })
        assertEquals(2, sources.allZones().size)
    }

    // ------------------------------------------- switched off is not asked

    @Test
    fun `a switched-off room is not asked, even by id`() {
        val source = sonos("Kitchen" to card("Sonos", "Kitchen", "Spiderland"))
        val sources = Sources(listOf(source))
        sources.zoneFilter = { false }

        // A page left open from before it was switched off, or a URL typed by
        // hand, still names the room. It must come back with nothing AND must
        // not have touched the speaker.
        assertNull(sources.inZone(ZoneRef.idFor("Sonos", "Kitchen")))
        assertTrue("the speaker was reached anyway: ${source.askedFor}", source.askedFor.isEmpty())
    }

    @Test
    fun `the ladder does not answer with a switched-off room`() {
        val sources = Sources(listOf(sonos("Kitchen" to card("Sonos", "Kitchen", "Spiderland"))))
        sources.zoneFilter = { false }
        assertNull(sources.nowPlaying(null))
    }

    @Test
    fun `naming a switched-off room by id does not take the fast path`() {
        // The fast path returns the chosen room outright when it is playing
        // and complete, which is exactly the shape that would skip the filter.
        val sources = Sources(listOf(sonos("Kitchen" to card("Sonos", "Kitchen", "Spiderland"))))
        sources.zoneFilter = { false }
        assertNull(sources.nowPlaying(ZoneRef.idFor("Sonos", "Kitchen")))
    }

    // ------------------------------------------------ the reason for the loop

    @Test
    fun `an enabled room is found even when its source volunteers a disabled one`() {
        /*
         * THE CASE THAT NEEDS THE TOP-UP ASK. `source.nowPlaying(null)` hands
         * back that source's OWN best room. Here that is the Lounge, which is
         * switched off — so simply dropping the answer would lose the Sonos
         * source entirely, and with it the Kitchen, which is enabled and
         * playing. Reported as a house where enabling one room showed nothing.
         */
        val sources = Sources(
            listOf(
                sonos(
                    // First in the map, so it is what the source volunteers.
                    "Lounge" to card("Sonos", "Lounge", "Loveless"),
                    "Kitchen" to card("Sonos", "Kitchen", "Spiderland")
                )
            )
        )
        sources.zoneFilter = { it == ZoneRef.idFor("Sonos", "Kitchen") }

        val playing = sources.nowPlaying(null)
        assertNotNull("the enabled room was lost with its source", playing)
        assertEquals("Spiderland", playing?.album)
        assertEquals("Kitchen", playing?.zoneName)
    }

    @Test
    fun `the chooser grid lists enabled rooms only, silent ones included`() {
        val sources = Sources(
            listOf(
                sonos(
                    "Kitchen" to card("Sonos", "Kitchen", "Spiderland"),
                    "Lounge" to card("Sonos", "Lounge", "Loveless", playing = false),
                    "Shed" to card("Sonos", "Shed", "Endtroducing")
                )
            )
        )
        sources.zoneFilter = {
            it == ZoneRef.idFor("Sonos", "Kitchen") || it == ZoneRef.idFor("Sonos", "Lounge")
        }

        val rooms = sources.rooms().map { it.zone.name }
        // The silent Lounge is listed, because a room you cannot reach is
        // worse than one that admits it is quiet. The Shed is not: it is off.
        assertEquals(listOf("Kitchen", "Lounge"), rooms)
    }

    @Test
    fun `with no filter set nothing changes at all`() {
        // Every existing caller, and every test written before rooms became
        // opt-in, must behave exactly as it did.
        val sources = Sources(listOf(sonos("Kitchen" to card("Sonos", "Kitchen", "Spiderland"))))
        assertEquals(1, sources.zones().size)
        assertEquals("Spiderland", sources.nowPlaying(null)?.album)
        assertNotNull(sources.inZone(ZoneRef.idFor("Sonos", "Kitchen")))
    }
}
