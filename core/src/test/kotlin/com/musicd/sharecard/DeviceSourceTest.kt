package com.musicd.sharecard

import com.musicd.sharecard.device.DeviceAudio
import com.musicd.sharecard.device.DeviceAudio.Access
import com.musicd.sharecard.device.DeviceAudio.Report
import com.musicd.sharecard.device.DeviceAudio.Session
import com.musicd.sharecard.device.DeviceSource
import com.musicd.sharecard.source.PlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone as a room.
 *
 * The values here are the ones a real phone actually sent — photographed off
 * `/api/debug` while Spotify played and Qobuz sat paused, both with full
 * records and only one of them offering a cover this app could fetch. That
 * matters: the two-sessions case is not hypothetical, and neither is a
 * `content://` art uri.
 */
class DeviceSourceTest {

    // --------------------------------------------------- the observed phone

    /** PLAYING, and its art uri is another app's private storage. */
    private val spotify = Session(
        packageName = "com.spotify.music",
        label = "Spotify",
        state = "PLAYING",
        title = "Cognitive Dissident",
        artist = "The The",
        album = "Ensoulment",
        artUri = "content://com.spotify.mobile.android.mediaapi/" +
            "spotify%3Aimage%3Aab67616d0000b2736900383e72eb02bf27bbd482?transformation=NONE",
        artBitmap = "300x300"
    )

    /** PAUSED, and its art uri is a public https CDN the proxy already allows. */
    private val qobuz = Session(
        packageName = "com.qobuz.music",
        label = "Qobuz",
        state = "PAUSED",
        title = "Daahoud (Album Version)",
        artist = "Dizzy Gillespie",
        album = "The Trumpet Summit Meets The Oscar Peterson Big Four",
        artUri = "https://static.qobuz.com/images/covers/32/60/0002521866032_600.jpg",
        artBitmap = "600x600"
    )

    private fun source(
        report: Report,
        enabled: Boolean = false
    ) = DeviceSource(DeviceAudio { report }, { enabled })

    private fun granted(vararg sessions: Session) =
        Report(Access.GRANTED, sessions.toList())

    // ------------------------------------------------------------ the zone

    @Test
    fun `the container offers no zone at all`() {
        assertEquals(emptyList<Any>(), source(Report.UNSUPPORTED).zones())
    }

    @Test
    fun `a phone that has not granted access still offers the room`() {
        // Otherwise there is nothing in Settings to switch on, and no way to
        // reach the notice explaining why it is empty.
        val zones = source(Report.DENIED).zones()
        assertEquals(1, zones.size)
        assertEquals("android:this", zones[0].id)
        assertEquals("This device", zones[0].name)
    }

    @Test
    fun `the zone id carries the source prefix like every other`() {
        // Two sources must not be able to collide on one room, and /api/queue
        // dispatches on this prefix.
        assertEquals("android", zoneSource(source(granted(spotify)).zones()[0].id))
    }

    private fun zoneSource(id: String) = id.substringBefore(':')

    // -------------------------------------------- which app wins, and why

    @Test
    fun `playing beats paused even when both describe a full record`() {
        // The observed phone, exactly: Spotify PLAYING, Qobuz PAUSED, both
        // with an album and an artist. Ranking on quality alone is a tie.
        val playing = source(granted(spotify, qobuz)).nowPlaying(null)
        assertNotNull(playing)
        assertEquals("Ensoulment", playing!!.album)
        assertEquals(PlayState.PLAYING, playing.state)
    }

    @Test
    fun `the order Android gave them does not decide it`() {
        // Same two sessions, other way round. A source that took the first
        // one would pass the test above by accident.
        val playing = source(granted(qobuz, spotify)).nowPlaying(null)
        assertEquals("Ensoulment", playing?.album)
    }

    @Test
    fun `a fuller record beats a thinner one when both are playing`() {
        val thin = spotify.copy(packageName = "org.thin", album = "", artist = "")
        val playing = source(granted(thin, qobuz.copy(state = "PLAYING"))).nowPlaying(null)
        assertEquals("The Trumpet Summit Meets The Oscar Peterson Big Four", playing?.album)
    }

    @Test
    fun `a paused app still makes a card when nothing is playing`() {
        // Rung 4 of the ladder: a paused room is a room, and the card says so.
        val playing = source(granted(qobuz)).nowPlaying(null)
        assertEquals(PlayState.PAUSED, playing?.state)
        assertEquals("Dizzy Gillespie", playing?.artist)
    }

    @Test
    fun `a session describing nothing is never drawn`() {
        // A transport with a state and no record. Returning it would draw a
        // blank card, which looks like the app working.
        val empty = Session(packageName = "com.example", state = "PLAYING")
        assertNull(source(granted(empty)).nowPlaying(null))
    }

    @Test
    fun `buffering counts as playing`() {
        // It means loading in order to play. Ranked below PAUSED it would hand
        // the card to whatever somebody stopped listening to an hour ago.
        val loading = spotify.copy(state = "BUFFERING")
        assertEquals(PlayState.PLAYING, source(granted(loading, qobuz)).nowPlaying(null)?.state)
    }

    @Test
    fun `a state nobody recognises is UNKNOWN and not silence`() {
        // DeviceSessions writes "state 7" for a code it does not know. Reading
        // that as STOPPED would be a guess with a card riding on it.
        val odd = spotify.copy(state = "state 7")
        assertEquals(PlayState.UNKNOWN, source(granted(odd)).nowPlaying(null)?.state)
    }

    // ------------------------------------------------------------- the art

    @Test
    fun `a public https cover is carried through to the card`() {
        val playing = source(granted(qobuz)).nowPlaying(null)
        assertEquals(
            "https://static.qobuz.com/images/covers/32/60/0002521866032_600.jpg",
            playing?.artUrl
        )
    }

    @Test
    fun `a content uri is never handed to the art proxy`() {
        // The proxy cannot fetch another app's private storage, so passing it
        // on would put an identical refusal in the notes on every single card.
        val playing = source(granted(spotify)).nowPlaying(null)
        assertEquals("", playing?.artUrl)
        assertFalse(playing?.artUrl.orEmpty().contains("content://"))
    }

    @Test
    fun `no LAN host is ever allowed on a session's say-so`() {
        // A session's metadata is another app's string. Widening the proxy on
        // it is exactly what StreamHosts was careful not to do.
        assertEquals(emptyList<String>(), source(granted(qobuz)).artHosts().toList())
    }

    // ----------------------------------------------------- the named zone

    @Test
    fun `a zone belonging to another source is refused`() {
        assertNull(source(granted(spotify)).nowPlaying("RINCON_12345"))
    }

    @Test
    fun `the room answers when it is named`() {
        assertNotNull(source(granted(spotify)).nowPlaying("this"))
    }

    @Test
    fun `nothing is answered without the permission`() {
        assertNull(source(Report(Access.DENIED, listOf(spotify))).nowPlaying(null))
        assertNull(source(Report(Access.UNSUPPORTED, listOf(spotify))).nowPlaying(null))
    }

    // ---------------------------------------------------------- the notice

    @Test
    fun `the grant is asked for only once the room is switched on`() {
        // THE RULE THIS SOURCE EXISTS UNDER. Rooms are opt-in, so a permission
        // notice shown before anybody asked would sit on every Android install
        // for ever — which is the exact bug the Roon notice was narrowed after.
        assertNull(source(Report.DENIED, enabled = false).notice())
        val asked = source(Report.DENIED, enabled = true).notice()
        assertNotNull(asked)
        assertTrue(asked!!.contains("Notification access"))
        assertTrue("it must name the way past restricted settings", asked.contains("restricted"))
    }

    @Test
    fun `a granted phone and a container both say nothing`() {
        assertNull(source(granted(spotify), enabled = true).notice())
        assertNull(source(Report.UNSUPPORTED, enabled = true).notice())
    }

    // ----------------------------------------------------- the diagnostics

    @Test
    fun `the report names the app that won and does not repeat the list`() {
        // "Playing on this device" already prints every session. What is not
        // there is the choice this class makes, which is the part that can be
        // wrong.
        val lines = source(granted(spotify, qobuz)).diagnostics().joinToString("\n")
        assertTrue(lines, lines.contains("2 app(s)"))
        assertTrue(lines, lines.contains("Ensoulment"))
        // The losing session's own line belongs to the other section.
        assertFalse(lines, lines.contains("com.qobuz.music"))
    }

    @Test
    fun `a refused permission reads differently from a quiet phone`() {
        // The Pitchfork lesson: a diagnostic that cannot tell two failures
        // apart is worse than none.
        val denied = source(Report.DENIED).diagnostics().joinToString("\n")
        val quiet = source(granted()).diagnostics().joinToString("\n")
        assertTrue(denied, denied.contains("not granted"))
        assertFalse(quiet, quiet.contains("not granted"))
        assertNotEquals(denied, quiet)
    }

    private fun assertNotEquals(a: String, b: String) =
        assertTrue("both read \"$a\"", a != b)
}
