package com.musicd.sharecard

import com.musicd.sharecard.sonos.Didl
import com.musicd.sharecard.sonos.Household
import com.musicd.sharecard.sonos.NowPlaying
import com.musicd.sharecard.sonos.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which room the card is about.
 *
 * This is the decision the whole app turns on — the user opens the page and
 * expects a card for whatever is playing, without naming a room — and it is
 * made from the transport state of every group at once. Driven here with
 * scripted players, because the alternative is four speakers and a stopwatch.
 */
class HouseholdTest {

    private val topology = """
        <ZoneGroupState><ZoneGroups>
          <ZoneGroup Coordinator="RINCON_A" ID="g1">
            <ZoneGroupMember UUID="RINCON_A" ZoneName="Living Room"
              Location="http://10.0.0.1:1400/x"/>
            <ZoneGroupMember UUID="RINCON_B" ZoneName="Kitchen"
              Location="http://10.0.0.2:1400/x"/>
          </ZoneGroup>
          <ZoneGroup Coordinator="RINCON_C" ID="g2">
            <ZoneGroupMember UUID="RINCON_C" ZoneName="Study"
              Location="http://10.0.0.3:1400/x"/>
          </ZoneGroup>
        </ZoneGroups></ZoneGroupState>
    """.trimIndent()

    private val players = mutableMapOf<String, FakePlayer>()

    private fun household(seeds: List<String> = listOf("10.0.0.1")): Household {
        players.clear()
        for (ip in listOf("10.0.0.1", "10.0.0.2", "10.0.0.3")) {
            players[ip] = FakePlayer(ip, topology = topology)
        }
        return Household(
            playerAt = { ip -> players.getValue(ip) },
            seedHosts = seeds,
            discover = { emptyList() }
        )
    }

    private fun album(title: String, artist: String) =
        NowPlaying(track = "A Track", album = title, artist = artist)

    // ------------------------------------------------------------- grouping

    @Test
    fun `a group is presented once, named after its coordinator`() {
        val h = household()
        h.refresh()
        val groups = h.groups()
        assertEquals(2, groups.size)
        assertEquals("Living Room + 1", groups.first { it.uid == "RINCON_A" }.displayName)
        assertEquals("Study", groups.first { it.uid == "RINCON_C" }.displayName)
    }

    @Test
    fun `a grouped room resolves to the coordinator that is actually playing`() {
        // Asking for the Kitchen, which is grouped under the Living Room, must
        // answer with the group rather than nothing: the music IS coming out
        // of the kitchen, it just is not coordinated there.
        val h = household()
        h.refresh()
        assertEquals("RINCON_A", h.group("RINCON_B")?.uid)
    }

    @Test
    fun `the group lists its rooms, coordinator first`() {
        val h = household()
        h.refresh()
        assertEquals(
            listOf("Living Room", "Kitchen"),
            h.group("RINCON_A")?.roomNames
        )
    }

    // ------------------------------------------------------------ selection

    @Test
    fun `the room that is playing is chosen without being named`() {
        val h = household()
        h.refresh()
        players["10.0.0.3"]!!.state = TransportState.PLAYING
        players["10.0.0.3"]!!.track = album("Spiderland", "Slint")

        val state = h.nowPlaying()
        assertNotNull(state)
        assertEquals("Study", state!!.group.coordinator.name)
        assertEquals("Spiderland", state.nowPlaying.album)
        assertTrue(state.isPlaying)
    }

    @Test
    fun `a playing room beats the one the user picked but which has stopped`() {
        // The app's purpose is a card for what is ON. Answering with an
        // hour-old paused album because that room was chosen once is the wrong
        // answer to the question being asked.
        val h = household()
        h.refresh()
        players["10.0.0.1"]!!.state = TransportState.STOPPED
        players["10.0.0.3"]!!.state = TransportState.PLAYING
        players["10.0.0.3"]!!.track = album("Rumours", "Fleetwood Mac")

        val state = h.nowPlaying(preferUid = "RINCON_A")
        assertEquals("Study", state!!.group.coordinator.name)
    }

    @Test
    fun `the picked room wins while it is playing, and nothing else is asked`() {
        val h = household()
        h.refresh()
        players["10.0.0.1"]!!.state = TransportState.PLAYING
        players["10.0.0.1"]!!.track = album("Loveless", "My Bloody Valentine")
        players["10.0.0.3"]!!.state = TransportState.PLAYING
        players["10.0.0.3"]!!.track = album("Something Else", "Someone")
        players["10.0.0.3"]!!.asked.clear()

        val state = h.nowPlaying(preferUid = "RINCON_A")
        assertEquals("Loveless", state!!.nowPlaying.album)
        // One round trip to one speaker: the other rooms are never disturbed.
        assertTrue(
            "the other room should not have been asked: ${players["10.0.0.3"]!!.asked}",
            players["10.0.0.3"]!!.asked.isEmpty()
        )
    }

    @Test
    fun `a paused room still makes a card when nothing is playing`() {
        val h = household()
        h.refresh()
        players["10.0.0.1"]!!.state = TransportState.PAUSED
        players["10.0.0.1"]!!.track = album("Blue", "Joni Mitchell")

        val state = h.nowPlaying()
        assertNotNull(state)
        assertEquals("Blue", state!!.nowPlaying.album)
        assertFalse(state.isPlaying)
    }

    @Test
    fun `a silent household answers nothing rather than an empty card`() {
        val h = household()
        h.refresh()
        assertNull(h.nowPlaying()?.nowPlaying?.album)
    }

    @Test
    fun `a speaker that has dropped off wifi is skipped, not fatal`() {
        val h = household()
        h.refresh()
        players["10.0.0.1"]!!.offline = true
        players["10.0.0.3"]!!.state = TransportState.PLAYING
        players["10.0.0.3"]!!.track = album("Illmatic", "Nas")

        val state = h.nowPlaying(preferUid = "RINCON_A")
        assertEquals("Illmatic", state!!.nowPlaying.album)
    }

    // ------------------------------------------------------------- topology

    @Test
    fun `the topology is held rather than re-read on every card`() {
        val h = household()
        h.refresh()
        val first = players["10.0.0.1"]!!.asked.count { it == "topology" }
        h.refresh()
        h.refresh()
        assertEquals(first, players["10.0.0.1"]!!.asked.count { it == "topology" })
    }

    @Test
    fun `refresh with force re-reads it, which is what a regroup needs`() {
        val h = household()
        h.refresh()
        val before = players["10.0.0.1"]!!.asked.count { it == "topology" }
        h.refresh(force = true)
        assertEquals(before + 1, players["10.0.0.1"]!!.asked.count { it == "topology" })
    }

    @Test
    fun `a failed scan keeps the last good picture instead of emptying the UI`() {
        val h = household()
        h.refresh()
        assertEquals(2, h.groups().size)
        players.values.forEach { it.offline = true }
        h.refresh(force = true)
        assertEquals("a single bad scan must not empty the room list", 2, h.groups().size)
    }

    @Test
    fun `a second player answers the topology when the first will not`() {
        val h = household(seeds = listOf("10.0.0.1", "10.0.0.2"))
        players["10.0.0.1"]!!.offline = true
        h.refresh()
        assertEquals(2, h.groups().size)
    }

    // ---------------------------------------------------------------- merge

    @Test
    fun `transport metadata fills the gaps a track leaves, field by field`() {
        val track = NowPlaying(track = "Drover", streamContent = "Bill Callahan - Drover")
        val media = NowPlaying(track = "BBC 6 Music", album = "", artUri = "/getaa?x=1")
        val merged = Didl.merge(track, media)
        // The song's own title survives; the station supplies only what was
        // missing. Taking one whole record or the other loses one of them.
        assertEquals("Drover", merged.track)
        assertEquals("/getaa?x=1", merged.artUri)
        assertEquals("Bill Callahan - Drover", merged.streamContent)
    }

    @Test
    fun `an empty transport reply leaves the track untouched`() {
        val track = NowPlaying(track = "Teardrop", album = "Mezzanine")
        assertEquals(track, Didl.merge(track, NowPlaying()))
    }
}
