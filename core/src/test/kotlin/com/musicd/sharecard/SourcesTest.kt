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
 * Choosing between sources.
 *
 * Two sources can see the same room and answer differently — Roon streaming to
 * a Sonos speaker is exactly that, and only one of the two knows what the
 * record is. So the order sources are asked in is a decision, not an accident.
 */
class SourcesTest {

    private class FakeSource(
        override val name: String,
        private val playing: Map<String, Playing> = emptyMap(),
        private val fail: Boolean = false
    ) : Source {
        var asked = 0
        var started = false

        override fun start() { started = true }

        override fun zones(): List<ZoneRef> {
            if (fail) throw RuntimeException("$name is broken")
            return playing.keys.map { ZoneRef(name, ZoneRef.idFor(name, it), it) }
        }

        override fun nowPlaying(zoneId: String?): Playing? {
            asked++
            if (fail) throw RuntimeException("$name is broken")
            if (zoneId != null) return playing[zoneId]
            return playing.values.firstOrNull { it.state.isPlaying }
                ?: playing.values.firstOrNull { !it.isEmpty }
        }

        override fun artHosts() = listOf("$name.local")
    }

    private fun card(
        source: String,
        zone: String,
        album: String,
        state: PlayState = PlayState.PLAYING
    ) = Playing(
        source = source,
        zoneId = ZoneRef.idFor(source, zone),
        zoneName = zone,
        album = album,
        artist = "Someone",
        state = state
    )

    @Test
    fun `the source that is playing answers, whichever it is`() {
        val sources = Sources(
            listOf(
                FakeSource("Roon"),
                FakeSource("Sonos", mapOf("Kitchen" to card("Sonos", "Kitchen", "Kind of Blue")))
            )
        )
        assertEquals("Kind of Blue", sources.nowPlaying()?.album)
    }

    @Test
    fun `Roon is asked before Sonos, because only Roon knows the record`() {
        // The whole reason for the ordering: both can see this room, and the
        // speaker's view of a Roon stream is a session id.
        val sources = Sources(
            listOf(
                FakeSource("Roon", mapOf("Stereo Fives" to card("Roon", "Stereo Fives", "Ænima"))),
                FakeSource("Sonos", mapOf("Stereo Fives" to card("Sonos", "Stereo Fives", "")))
            )
        )
        val playing = sources.nowPlaying()
        assertEquals("Roon", playing?.source)
        assertEquals("Ænima", playing?.album)
    }

    @Test
    fun `a chosen zone is asked of its own source and nothing else is disturbed`() {
        val roon = FakeSource("Roon", mapOf("Stereo Fives" to card("Roon", "Stereo Fives", "Ænima")))
        val sonos = FakeSource("Sonos", mapOf("Kitchen" to card("Sonos", "Kitchen", "Blue")))
        val sources = Sources(listOf(roon, sonos))

        val playing = sources.nowPlaying("roon:Stereo Fives")
        assertEquals("Ænima", playing?.album)
        assertEquals("the other source should not have been asked", 0, sonos.asked)
    }

    @Test
    fun `a playing room beats the chosen one that has stopped`() {
        val sources = Sources(
            listOf(
                FakeSource("Roon", mapOf("Study" to card("Roon", "Study", "Old", PlayState.STOPPED))),
                FakeSource("Sonos", mapOf("Kitchen" to card("Sonos", "Kitchen", "Now Playing")))
            )
        )
        assertEquals("Now Playing", sources.nowPlaying("roon:Study")?.album)
    }

    @Test
    fun `a paused room still makes a card when nothing is playing`() {
        val sources = Sources(
            listOf(FakeSource("Sonos", mapOf("Kitchen" to card("Sonos", "Kitchen", "Blue", PlayState.PAUSED))))
        )
        val playing = sources.nowPlaying()
        assertNotNull(playing)
        assertEquals("Blue", playing!!.album)
        assertTrue(!playing.state.isPlaying)
    }

    @Test
    fun `a source that throws does not stop the others answering`() {
        val sources = Sources(
            listOf(
                FakeSource("Roon", fail = true),
                FakeSource("Sonos", mapOf("Kitchen" to card("Sonos", "Kitchen", "Kind of Blue")))
            )
        )
        assertEquals("Kind of Blue", sources.nowPlaying()?.album)
        assertEquals(1, sources.zones().size)
    }

    @Test
    fun `zone ids carry their source, so two sources cannot collide`() {
        val sources = Sources(
            listOf(
                FakeSource("Roon", mapOf("Stereo Fives" to card("Roon", "Stereo Fives", "A"))),
                FakeSource("Sonos", mapOf("Stereo Fives" to card("Sonos", "Stereo Fives", "B")))
            )
        )
        val ids = sources.zones().map { it.id }
        assertEquals(listOf("roon:Stereo Fives", "sonos:Stereo Fives"), ids)
        assertEquals("A", sources.nowPlaying("roon:Stereo Fives")?.album)
        assertEquals("B", sources.nowPlaying("sonos:Stereo Fives")?.album)
    }

    @Test
    fun `a silent household answers nothing`() {
        assertNull(Sources(listOf(FakeSource("Roon"), FakeSource("Sonos"))).nowPlaying())
    }

    @Test
    fun `every source is started, and every art host is collected`() {
        val roon = FakeSource("Roon")
        val sonos = FakeSource("Sonos")
        val sources = Sources(listOf(roon, sonos))
        sources.start()
        assertTrue(roon.started && sonos.started)
        assertEquals(setOf("Roon.local", "Sonos.local"), sources.artHosts())
    }

    @Test
    fun `an id with no source prefix is not guessed at`() {
        val sources = Sources(listOf(FakeSource("Roon", mapOf("a" to card("Roon", "a", "A")))))
        // Falls through to "whatever is playing" rather than picking a source
        // at random for an id it cannot attribute.
        assertEquals("A", sources.nowPlaying("a")?.album)
    }
}
