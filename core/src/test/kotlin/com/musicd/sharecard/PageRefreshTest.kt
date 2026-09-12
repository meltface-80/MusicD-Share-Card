package com.musicd.sharecard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The card is redrawn when somebody asks for it, and at no other time.
 *
 * There are two halves to that and only one of them was ever written down.
 * "Never poll" is in CLAUDE.md and there has never been a timer. But the page
 * also reloaded on `visibilitychange`, which is not a poll and looked like the
 * same rule — ask at the moment somebody wants to know — while doing something
 * quite different: returning to the app from the Home Screen fires it, so the
 * card you were looking at was thrown away and replaced by a spinner every
 * time you glanced at anything else. A card deliberately left up to show
 * somebody could not survive being backgrounded.
 *
 * Neither half can be reproduced here — there is no browser and no WebView —
 * so the source is scanned, the same way FilePickerContractTest scans for a
 * WebChromeClient. The failure mode is not an error either: it is a card that
 * quietly went away, which reads as the app working.
 */
class PageRefreshTest {

    private fun read(path: String): String {
        val file = File("../$path")
        assertTrue("cannot find $path at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private val page get() = read("app/src/main/assets/web/app.js")

    /**
     * Every event that fires when an app is brought back to the foreground.
     * `visibilitychange` is the one that shipped; the others are the shapes
     * the same mistake takes next.
     */
    @Test
    fun `nothing redraws the card because the app came back to the foreground`() {
        val source = page
        for (event in listOf("visibilitychange", "pageshow", "pagehide", "resume", "focus")) {
            assertFalse(
                "a \"$event\" listener throws away the card every time the app is " +
                    "reopened — leaving the app is not a request for a different record",
                source.contains("\"$event\"") || source.contains("'$event'")
            )
        }
        // The state that listener read. Anything consulting it is asking the
        // same question and will reach the same wrong answer.
        assertFalse("document.hidden has no business deciding what is drawn",
            source.contains("document.hidden"))
    }

    /**
     * The other half, and the one the rule in CLAUDE.md has always named: this
     * runs on a device that is never switched off, so a timer means
     * interrogating the household all day to answer a question nobody is in
     * the room to read.
     *
     * setInterval is not banned outright — the update download has a progress
     * poll, and that one is bounded by a transfer somebody started. What may
     * never be on a timer is the card.
     */
    @Test
    fun `the card is never on a timer`() {
        val timed = Regex("""set(Interval|Timeout)\([^)]*\bload\(""")
        assertFalse(
            "a timer that reloads the card polls the speakers all day",
            timed.containsMatchIn(page)
        )
    }

    /**
     * The shell's side of it. A WebView that is told to load the page again
     * when the Activity resumes produces exactly the same loss, from below,
     * where nothing in the page could see it coming.
     */
    @Test
    fun `the shell loads the page once and never reloads it`() {
        val shell = read("app/src/main/java/com/musicd/sharecard/android/MainActivity.kt")
        assertFalse(
            "onResume is where a reload would be added; the page it is showing " +
                "is still the one the user left",
            shell.contains("onResume")
        )
        assertFalse("reload() throws the card away too", shell.contains(".reload()"))
        assertEquals(
            "the page is loaded once, when the server comes up",
            1, Regex("""\bloadUrl\(""").findAll(shell).count()
        )
    }
}
