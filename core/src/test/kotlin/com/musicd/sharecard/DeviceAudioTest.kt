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

    /**
     * THE PATH IT NAMES MUST BE ONE THAT WORKS.
     *
     * Photographed from a real phone: tapping the toggle the report pointed at
     * gave "App was denied access — access to this permission can put your
     * personal and financial info at risk". That is Android's RESTRICTED
     * SETTINGS, which refuses notification-listener access to anything
     * installed outside the Play Store — so the grant could never land and the
     * report was sending somebody back to the same refusal.
     *
     * And `ApkInstaller` uses the legacy ACTION_VIEW install flow, so every
     * self-update is restricted the same way and the block returns each time.
     * A path already walked and refused is worse than no path.
     */
    @Test
    fun `a refusal names the way past Android's restricted settings`() {
        val text = lines(Report.DENIED)
        assertTrue(text, text.contains("Notification access"))
        // The escape, in the words the system uses, so it is recognisable.
        assertTrue(text, text.contains("denied access"))
        assertTrue(text, text.contains("restricted settings"))
        // And that it is not a one-off, or somebody does it once and is puzzled
        // when the next update takes it away again.
        assertTrue(text, text.contains("after every update"))
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

    // --------------------------- why "not granted" was not the whole answer

    /**
     * THE ONE FROM THE FIELD, AND IT IS THE THIRD TIME THIS SESSION.
     *
     * Reported as: notification access granted, and the report still said NOT
     * GRANTED. Three causes wear that one sentence — the grant did not take, it
     * went to a different app, or this app's own reading of the setting is
     * wrong — and the report stopped exactly one step short of saying which.
     * Same shape as the ListenBrainz 400 with its reason discarded and the
     * Pitchfork index failing in silence.
     */
    @Test
    fun `a refusal carries what was actually looked for`() {
        val detail = DeviceAudio.listenerNote(
            component = "com.musicd.sharecard/com.musicd.sharecard.android.MediaAccess",
            listed = false,
            total = 4,
            refused = true
        )
        val text = lines(Report(Access.DENIED, emptyList(), detail))
        assertTrue(text, text.contains("MediaAccess"))
        assertTrue(text, text.contains("4"))
        assertTrue(text, text.contains("NOT GRANTED"))
    }

    @Test
    fun `a grant that IS there and still refused reads differently from one that is not`() {
        val missing = DeviceAudio.listenerNote("c", listed = false, total = 3, refused = true)
        val present = DeviceAudio.listenerNote("c", listed = true, total = 3, refused = true)
        assertFalse("these are different bugs", missing == present)
        // The one that matters: the grant is there and the call was still
        // turned down, which means this app's reading of it was never the
        // problem and the next fix is somewhere else entirely.
        assertTrue(present, present.contains("IS one of"))
        assertTrue(present, present.contains("something else is wrong"))
        assertTrue(missing, missing.contains("NOT among"))
    }

    @Test
    fun `a refusal reads differently from an empty answer`() {
        val refused = DeviceAudio.listenerNote("c", listed = false, total = 2, refused = true)
        val quiet = DeviceAudio.listenerNote("c", listed = false, total = 2, refused = false)
        assertFalse("a system refusal is not a silent phone", refused == quiet)
        assertTrue(refused, refused.contains("refused"))
        assertFalse(quiet, quiet.contains("refused"))
    }

    /**
     * NO OTHER APP IS NAMED, AND THAT IS A PRIVACY DECISION RATHER THAN BREVITY.
     *
     * `enabled_notification_listeners` is every app somebody has given
     * notification access to, and this report gets pasted into chat windows. A
     * COUNT answers the question; the list would be their installed software.
     * Enforced by the signature — the note is given a number, so it has no
     * names to leak even if somebody later decides to print more.
     */
    @Test
    fun `the note is given counts, never the other listeners`() {
        val note = DeviceAudio.listenerNote("ours", listed = false, total = 7, refused = false)
        assertTrue(note, note.contains("7"))
        assertTrue("only our own component is named", note.contains("ours"))
    }

    /**
     * AND "the permission is fine" IS A CLAIM, SO ITS EVIDENCE GOES BESIDE IT.
     *
     * A granted probe with no sessions says nothing is playing. That is the
     * happy answer and it is also what a subtly broken grant would look like,
     * so the same note is carried on it.
     */
    @Test
    fun `a granted but silent phone still shows what was checked`() {
        val detail = DeviceAudio.listenerNote("ours", listed = true, total = 2, refused = false)
        val text = lines(Report(Access.GRANTED, emptyList(), detail))
        assertTrue(text, text.contains("granted"))
        assertTrue(text, text.contains("IS one of"))
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
