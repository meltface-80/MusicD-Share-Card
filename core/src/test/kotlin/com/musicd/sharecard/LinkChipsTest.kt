package com.musicd.sharecard

import com.musicd.sharecard.meta.Reviews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE LINKS ROW IS A FOUR-COLUMN GRID, AND ONE CHIP MUST NOT SET ITS HEIGHT.
 *
 * `align-items: stretch` makes the one-line chips as tall as the two-line ones
 * so the grid stays a grid. It works the other way too: ONE tall chip makes
 * every chip on its row that tall. The artist review chips were labelled
 * `"AllMusic: $artist"`, a Roon card answered with "Stan Getz / Cal Tjader /
 * Alan Jay Lerner / Frederick Loewe", and the row drew as four circles with a
 * small pill orphaned underneath — reported as "button sizes completely off",
 * and reproduced in a real browser at 390px before any of this was changed
 * (heights 30..117px on one row).
 *
 * Two things have to hold and neither can be seen from the other end:
 *
 *   1. a chip label is a CONSTANT — never built from a record's artist, album
 *      or anything else that arrives off the network;
 *   2. the stylesheet states the chip height rather than growing into it, so
 *      a label that does slip through is clamped instead of pushing the row.
 *
 * There is no browser here, so the stylesheet and the page are scanned. CODE
 * LINES ONLY — this file's own prose quotes the broken expression on purpose,
 * and a scan that reads prose fails on the explanation of what it guards.
 */
class LinkChipsTest {

    private val css = File("../app/src/main/assets/web/style.css")
    private val cardApi =
        File("src/main/kotlin/com/musicd/sharecard/api/CardApi.kt")

    private fun code(file: File): List<String> {
        assertTrue("cannot find ${file.absolutePath}", file.isFile)
        return file.readLines()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") }
    }

    @Test
    fun `every review chip label is short and says which it is`() {
        // Four columns of a 390px phone is about 84px a chip, which holds two
        // lines of a dozen characters. Nothing here may need more.
        for (source in Reviews.ALL) {
            assertTrue(
                "the chip label ${source.chip} is too long for a quarter of a phone",
                source.chip.length <= 16
            )
        }
        assertEquals("Wikipedia", Reviews.chip(Reviews.WIKIPEDIA))
        assertEquals("AllMusic", Reviews.chip(Reviews.ALLMUSIC))
        // Both AllMusic chips can be on at once, so "AllMusic" twice would be
        // a coin toss for whoever taps one.
        assertEquals("Wikipedia artist", Reviews.chip(Reviews.WIKIPEDIA_ARTIST))
        assertEquals("AllMusic artist", Reviews.chip(Reviews.ALLMUSIC_ARTIST))
    }

    @Test
    fun `no chip label is built from the record`() {
        val built = code(cardApi)
            .filter { it.contains("reading.put(") || it.contains("Reviews.chip(") }
            .filter { it.contains("\$") }
        assertTrue(
            "a links-row chip label is being interpolated from the record: $built — " +
                "an artist string is whatever the speaker says it is, and the row " +
                "gives every chip a quarter of a phone",
            built.isEmpty()
        )
    }

    @Test
    fun `the stylesheet fixes one height for every chip`() {
        val rule = ruleFor(".links a")
        assertTrue(
            "`.links a` states no height, so the longest label on a row decides " +
                "how tall every chip on it is — which is the bug this guards",
            // NOT `\bheight:` — a word boundary sits between the hyphen and
            // the h of `line-height`, so that matched the rule as it was and
            // the test passed against the bug it was written for.
            rule.contains(Regex("""(?<![-\w])height:\s*\d"""))
        )
        assertTrue(
            "`.links a` must hide what will not fit in that height, or a third " +
                "line is sliced in half top and bottom",
            rule.contains(Regex("""\boverflow:\s*hidden"""))
        )
    }

    @Test
    fun `a label too long for the chip is clamped rather than sliced`() {
        val rule = ruleFor(".links a > span")
        assertTrue(
            "the label span must clamp to the two lines the chip is tall",
            rule.contains("line-clamp: 2")
        )
    }

    /** The declarations of one rule, with the comments taken out. */
    private fun ruleFor(selector: String): String {
        val text = code(css).joinToString("\n")
        val at = text.indexOf("\n$selector {")
        assertTrue("the stylesheet has no `$selector` rule at all", at >= 0)
        val open = text.indexOf('{', at)
        val close = text.indexOf('}', open)
        assertTrue("the `$selector` rule is never closed", close > open)
        return text.substring(open + 1, close)
    }

    // ------------------------------- a review chip and a service chip differ

    /*
     * ONE GRID HELD BOTH, so whatever the reviews left of a line was filled by
     * the first service: Qobuz on the end of the review row, Spotify and
     * Bandcamp starting a row of their own beneath it. Two chips that do
     * entirely different things shared a line, and WHICH ones did depended on
     * how many review sources happened to be switched on. Reported as exactly
     * that — keep them on different lines, and give either one two lines if it
     * needs them.
     */

    @Test
    fun `the two kinds of chip are built into separate rows`() {
        val lines = code(File("../app/src/main/assets/web/app.js"))
        assertTrue(
            "buildLinks no longer builds a row for the review chips",
            lines.any { it.contains("reviewRow") }
        )
        assertTrue(
            "buildLinks no longer builds a row for the service chips",
            lines.any { it.contains("serviceRow") }
        )
        // The failure this guards is a service appended where the reviews go.
        assertTrue(
            "a service chip is being appended to the review row, which is the " +
                "bug: the rows must hold one kind of thing each",
            lines.none { it.contains("reviewRow.appendChild") && it.contains("svc") }
        )
    }

    @Test
    fun `the four columns are on the row, not on the container`() {
        // `.links` being the grid is what made them share one. It is the column
        // now; each `.links-row` is the four-column grid it used to be.
        assertTrue(
            "`.links-row` is not a four-column grid, so the chips would stack",
            ruleFor(".links-row").contains("repeat(4")
        )
        assertFalse(
            "`.links` is a grid again, which puts both kinds of chip back on " +
                "one flow and lets a service finish the review line",
            ruleFor(".links").contains(Regex("""display:\s*grid"""))
        )
    }
}
