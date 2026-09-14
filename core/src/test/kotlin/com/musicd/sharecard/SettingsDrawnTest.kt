package com.musicd.sharecard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The settings screens exist in the PAGE, and nothing else here can check that.
 *
 * This is the same guard as [DiagnosticsDrawnTest] and for the same reason: a
 * field can be correct, served and invisible. `/api/settings` answers with
 * services and zones, and either one could be served perfectly while the page
 * never drew it — which is exactly how the `similar` diagnostic shipped, and
 * how the source name was left off the chooser tiles.
 *
 * There is no browser here, so the source is scanned. CODE LINES ONLY: a scan
 * that reads prose fails on the explanation of what it is guarding, which this
 * repo has now done twice.
 */
class SettingsDrawnTest {

    private val page = File("../app/src/main/assets/web/app.js")
    private val html = File("../app/src/main/assets/web/index.html")

    private fun code(file: File): List<String> {
        assertTrue("cannot find ${file.absolutePath}", file.isFile)
        return file.readLines()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") }
    }

    @Test
    fun `the settings button is in the header`() {
        val markup = html.readText()
        assertTrue("cannot find index.html", html.isFile)
        assertTrue(
            "the header must carry a settings button for the page to open Settings at all",
            markup.contains("""id="settings"""")
        )
    }

    @Test
    fun `the brand dot is gone rather than left behind the cog`() {
        // It was decoration and it is what the cog replaced. Leaving the
        // element in place would put a dead yellow circle beside the button.
        assertFalse(
            "the decorative brand dot is back in the header",
            html.readText().contains("""class="dot"""")
        )
        assertFalse(
            "the .dot rule outlived the element it styled",
            File("../app/src/main/assets/web/style.css").readText().contains(".dot {")
        )
    }

    @Test
    fun `every group the settings route returns is drawn`() {
        val lines = code(page)
        // The lists /api/settings can answer with. One added later and not
        // drawn is the failure this test exists to make loud.
        for (group in listOf("services", "zones", "reviews")) {
            assertTrue(
                "the page never reads settings.$group, so the settings route " +
                    "would be serving it to nobody",
                lines.any { it.contains("settings.$group") }
            )
        }
    }

    @Test
    fun `all four sub-menus are reachable`() {
        val lines = code(page)
        for (id in listOf("settings-services", "settings-reviews", "settings-zones",
                          "settings-webhooks")) {
            assertTrue(
                "$id is never bound, so that row would be a button that does nothing",
                lines.any { it.contains(id) }
            )
        }
    }

    @Test
    fun `the webhook setup no longer sits in the action row`() {
        // It moved into Settings. A cog left in buildActions would be a second
        // settings button that looks like the first.
        // From the declaration to the NEXT top-level declaration. Sliced on
        // the two-space indent every function in this IIFE is written at,
        // because a looser slice read half the file and failed on a mention
        // in another function entirely.
        val lines = code(page)
        val start = lines.indexOfFirst { it.startsWith("  function buildActions") }
        assertTrue("buildActions has gone", start >= 0)
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { Regex("^  (async )?function ").containsMatchIn(it) }
        val body = if (end < 0) rest else rest.take(end)
        assertFalse(
            "buildActions still opens the webhook settings",
            body.any { it.contains("showWebhookSettings") }
        )
    }

    @Test
    fun `a settings screen claims the stage before it awaits`() {
        /*
         * THE BUG THIS PINS. Every screen renders into the same stage the card
         * does, and a load() already in flight will paint its own answer over
         * whatever is there. Claiming AFTER an await is not enough: measured in
         * a real browser during a first-run discovery sweep, the first await
         * queued behind it and the screen never drew at all.
         */
        val lines = code(page)
        val opener = lines.indexOfFirst { it.contains("function showSettings") }
        assertTrue("showSettings has gone", opener >= 0)
        val body = lines.drop(opener).take(12)
        val claims = body.indexOfFirst { it.contains("claimStage()") }
        val awaits = body.indexOfFirst { it.contains("await ") }
        assertTrue("showSettings never claims the stage", claims >= 0)
        assertTrue(
            "showSettings awaits before claiming the stage, so a load() in " +
                "flight can paint over it",
            awaits < 0 || claims < awaits
        )
    }

    @Test
    fun `the extra reading chips are drawn`() {
        /*
         * `/api/extras` answers with a `reading` array — AllMusic, an artist's
         * article — and the page has to draw whatever is in it rather than
         * knowing the names. Served and undrawn is exactly how the `similar`
         * diagnostic shipped, and how the source name was left off the chooser
         * tiles.
         */
        assertTrue(
            "the page never reads extras.reading, so those chips would be " +
                "served to nobody",
            code(page).any { it.contains("extras.reading") }
        )
    }

    // ----------------------------------------- what the settings screens say

    @Test
    fun `the menu says which build this is and links to the project page`() {
        val lines = code(page)
        assertTrue(
            "the settings menu never reads setup.version, so the server would " +
                "be answering with a version nobody can see — which is how the " +
                "similar diagnostic shipped",
            lines.any { it.contains("setup.version") }
        )
        assertTrue(
            "the menu must link somewhere for the release notes and the Docker " +
                "commands",
            lines.any { it.contains("meltface-80.github.io") }
        )
    }

    @Test
    fun `both halves of the Reviews screen are headed`() {
        val lines = code(page)
        // The artist half had a heading and the album half opened straight
        // into a sentence, which read as a caption for the screen rather than
        // as the name of the group above the switches.
        for (heading in listOf("About the albums", "About the artist")) {
            assertTrue(
                "the Reviews screen has no \"$heading\" heading",
                lines.any { it.contains(heading) }
            )
        }
    }

    @Test
    fun `a suggestion offers somewhere to read about it`() {
        val lines = code(page)
        // Served and not drawn is the failure this family of scans exists for:
        // /api/similar carries a review link per act, and a page that never
        // reads it is correct, served and invisible.
        assertTrue(
            "the page never reads act.review, so the review link on every " +
                "suggestion would be served to nobody",
            lines.any { it.contains("act.review") }
        )
    }
}
