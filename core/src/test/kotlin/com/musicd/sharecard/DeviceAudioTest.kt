package com.musicd.sharecard

import com.musicd.sharecard.device.DeviceAudio
import com.musicd.sharecard.device.DeviceAudio.Access
import com.musicd.sharecard.device.DeviceAudio.Report
import com.musicd.sharecard.device.DeviceAudio.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe that asks what is playing on the phone itself.
 *
 * Nothing here can touch `MediaSessionManager` — it is an Android class and
 * :core holds none — so what is tested is every decision the report makes about
 * data the shell hands it. That is deliberately all of them: the Android half
 * is a read and a copy into these data classes, which is the only shape that
 * leaves anything testable at all.
 */
class DeviceAudioTest {

    private fun lines(report: Report) = DeviceAudio { report }.diagnostics().joinToString("\n")

    private val spotify = Session(
        packageName = "com.spotify.music",
        label = "Spotify",
        state = "PLAYING",
        title = "Dark Angel",
        artist = "Kelly Lee Owens",
        album = "Dreamstate"
    )

    // ------------------------------------------- the distinction it exists for

    /**
     * THE WHOLE REASON THIS TYPE CARRIES [Access] SEPARATELY.
     *
     * `getActiveSessions` returns an EMPTY LIST when the permission is missing
     * — it does not throw — so a refused probe and a silent phone arrive as the
     * same value. Reported as "it finds nothing" they would be one sentence and
     * two completely different fixes.
     */
    @Test
    fun `a refused probe and a silent phone never read the same`() {
        val refused = lines(Report.DENIED)
        val silent = lines(Report(Access.GRANTED, emptyList()))

        assertFalse("these are two different problems", refused == silent)
        // The refusal says so in its own words, and says where to go.
        assertTrue(refused, refused.contains("NOT GRANTED"))
        assertTrue(refused, refused.contains("Notification access"))
        // And the silent phone says the permission is NOT the problem, or the
        // next report chases a permission that was already granted.
        assertTrue(silent, silent.contains("granted"))
        assertFalse("a silent phone must not read as a refusal", silent.contains("NOT GRANTED"))
    }

    @Test
    fun `the container says it is the container, rather than saying nothing`() {
        val text = lines(Report.UNSUPPORTED)
        // A section that silently vanishes on one of the two shells reads as a
        // broken build rather than a different machine.
        assertTrue(text, text.contains("not an Android build"))
        assertTrue(text, text.contains("container"))
    }

    /**
     * THE DEFAULT MUST REPORT, NOT GO QUIET — AND A NULL WENT QUIET.
     *
     * `ShareCardApp` took this as `DeviceAudio? = null`, so the container
     * printed no "device" section at all while the honest branch below sat here
     * unit-tested and unreachable. Found by driving the real server, not by any
     * test: no test of this class can see how a HOST wires it. The parameter is
     * non-nullable now, so "no probe at all" cannot be expressed — and this
     * pins the other half, that the value a host gets for free still speaks.
     */
    @Test
    fun `a probe with no shell behind it says it cannot see, rather than nothing`() {
        val bare = DeviceAudio().diagnostics()
        assertFalse("silence is what the null default did", bare.isEmpty())
        assertTrue(bare.joinToString("\n"), bare.joinToString("\n").contains("not an Android build"))
    }

    @Test
    fun `a shell that throws is reported as unsupported, never as a crash`() {
        // A probe must not be able to take /api/debug down — the report is what
        // somebody opens BECAUSE something is wrong.
        val audio = DeviceAudio { throw UnsatisfiedLinkError("no such class") }
        assertTrue(audio.diagnostics().joinToString("\n").contains("not an Android build"))
    }

    // ------------------------------------------------------- what it reports

    @Test
    fun `a session names the app, the state and the record`() {
        val text = lines(Report(Access.GRANTED, listOf(spotify)))
        assertTrue(text, text.contains("1 session(s)"))
        // The package is what says WHICH app, and it is the one field always
        // present — a label is resolved by the shell and may not be.
        assertTrue(text, text.contains("com.spotify.music"))
        assertTrue(text, text.contains("Spotify"))
        assertTrue(text, text.contains("PLAYING"))
        assertTrue(text, text.contains("Dark Angel / Kelly Lee Owens / Dreamstate"))
    }

    @Test
    fun `a session holding no metadata says so rather than drawing a blank`() {
        val text = lines(Report(Access.GRANTED, listOf(Session(packageName = "com.example.app"))))
        assertTrue(text, text.contains("nothing described"))
        assertTrue(text, text.contains("no state"))
    }

    @Test
    fun `a wall of sessions is capped and says how many it left out`() {
        val many = (1..12).map { Session(packageName = "com.app$it") }
        val text = lines(Report(Access.GRANTED, many))
        assertTrue(text, text.contains("12 session(s)"))
        assertTrue(text, text.contains("com.app${DeviceAudio.MAX_SESSIONS}"))
        assertFalse("past the cap is not listed", text.contains("com.app12 "))
        assertTrue(text, text.contains("4 more not listed"))
    }

    // ------------------------------------------ the question about a cover

    /**
     * WHAT A CARD COULD DRAW IS THE OPEN QUESTION, so the three cases must
     * never collapse into one. The art pipeline is a URL fetched by a proxy,
     * and only one of these three is that.
     */
    @Test
    fun `an https cover is named as the one the proxy could fetch unchanged`() {
        val note = DeviceAudio.artNote(spotify.copy(artUri = "https://i.scdn.co/image/ab67"))
        assertTrue(note, note.contains("PROXYABLE"))
        assertTrue(note, note.contains("https://i.scdn.co/image/ab67"))
    }

    @Test
    fun `a content uri is named as the one that cannot be fetched`() {
        // Another app's private storage: this process has no grant to read it
        // and no proxy anywhere can, however local it looks.
        val note = DeviceAudio.artNote(spotify.copy(artUri = "content://com.spotify/art/1"))
        assertTrue(note, note.contains("NOT FETCHABLE"))
        assertFalse("it is not proxyable", note.contains("PROXYABLE"))
    }

    @Test
    fun `a bitmap is named as having no url at all`() {
        val note = DeviceAudio.artNote(spotify.copy(artBitmap = "640x640"))
        assertTrue(note, note.contains("bitmap 640x640"))
        assertTrue(note, note.contains("no url"))
    }

    @Test
    fun `both a uri and a bitmap are reported, not just the first`() {
        // A fallback chain that stops at the first candidate is not a fallback
        // chain — and here it would hide the only fetchable half.
        val note = DeviceAudio.artNote(
            spotify.copy(artUri = "https://i.scdn.co/image/ab67", artBitmap = "300x300")
        )
        assertTrue(note, note.contains("PROXYABLE"))
        assertTrue(note, note.contains("bitmap 300x300"))
    }

    @Test
    fun `no cover at all is stated, because that is a real answer`() {
        // It decides whether the sleeve has to be looked up from the record the
        // way Discover already does. Silence here would read as "not checked".
        assertEquals("NONE", DeviceAudio.artNote(spotify))
        assertTrue(lines(Report(Access.GRANTED, listOf(spotify))).contains("art: NONE"))
    }
}
