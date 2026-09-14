package com.musicd.sharecard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * EVERY FIELD /api/new SENDS IS ONE THE PAGE ACTUALLY DRAWS.
 *
 * The third time this scan has had to exist. `similar` shipped correct, served
 * and invisible; the chooser nearly shipped a tile that never named its source;
 * and this screen sends a field — the review a record came out of — that is the
 * only thing on it linking to whoever did the writing. A Discover screen that
 * quietly stops crediting them is not a missing detail, it is the whole reason
 * the screen is allowed to exist.
 *
 * There is no browser here, so the sources are scanned. app.js is a declared
 * test input of :core — see core/build.gradle.kts — or this goes UP-TO-DATE and
 * checks nothing, which is how this family of tests once lied for a week.
 */
class NewMusicDrawnTest {

    private fun read(path: String): String {
        val file = File("../$path")
        assertTrue("cannot find $path at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private val page by lazy { read("app/src/main/assets/web/app.js") }

    /** newMusicRoute's own body, and nothing of whatever follows it. */
    private fun route(server: String): String =
        server.substringAfter("private fun newMusicRoute").let {
            it.substringBefore("\n    private fun ", it)
        }

    @Test
    fun `the page reads every field a pick carries`() {
        val server = read("core/src/main/kotlin/com/musicd/sharecard/api/CardApi.kt")
        val body = route(server)
        assertTrue("newMusicRoute not found — has CardApi been restructured?", body.isNotEmpty())

        val keys = Regex("""put(?:OrNull)?\s*\(\s*"([a-zA-Z]+)"""")
            .findAll(body).map { it.groupValues[1] }.toSet() - setOf("picks")

        assertTrue("no pick fields found in newMusicRoute", keys.size >= 6)
        for (key in keys) {
            assertTrue(
                "/api/new sends \"$key\" and app.js never reads it — correct, " +
                    "served and invisible is the worst of the three",
                page.contains("pick.$key") || page.contains("p.$key") ||
                    page.contains("\"$key\"") || page.contains("'$key'")
            )
        }
    }

    @Test
    fun `the review chip is named by whoever wrote the review`() {
        /*
         * FOUND IN A RENDER, NOT BY READING. The review chip's label was the
         * literal "Pitchfork review", which was true for as long as a review
         * link could only come from the score lookup. A record out of NME's
         * feed then drew NME's review under Pitchfork's name — the same
         * mistake the blurb chip has had a rule about since it gained
         * `bioSource`, made in the one place that rule had not reached.
         *
         * Same shape as the two chips the render also caught: the publisher's
         * own link and a URL built from a slug, side by side, both labelled
         * "Pitchfork review" with nothing to tell them apart.
         */
        val code = page.lines()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") }
            .joinToString("\n")

        assertTrue(
            "the review chip must take its name from the data — a hard-coded " +
                "one puts a second publisher's review under Pitchfork's byline",
            code.contains("extras.reviewName")
        )
        assertTrue(
            "a record that came from a review must supply that review's name",
            code.contains("reviewName: pick.readAtName")
        )
        assertTrue(
            "the publisher's own link must take the review slot rather than " +
                "sitting beside the lookup's guess at the same URL",
            code.contains("reviewUrl: pick.readAt")
        )
    }

    @Test
    fun `not one word of an article may reach the page`() {
        // The line the whole screen rests on: a headline identifies a record
        // and is thrown away, the feed's description is never read, and what
        // survives is a link with the publisher's name on it. There is no
        // route that could carry the prose, and this asserts that rather than
        // trusting it.
        val server = read("core/src/main/kotlin/com/musicd/sharecard/api/CardApi.kt")
        for (word in listOf("\"excerpt\"", "\"summary\"", "\"description\"", "\"body\"")) {
            assertTrue(
                "/api/new must never carry a word of somebody's writing, and " +
                    "$word is a field that would",
                !route(server).contains(word)
            )
        }
        val editorial = read("core/src/main/kotlin/com/musicd/sharecard/discover/Editorial.kt")
        val code = editorial.lines()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
        assertTrue(
            "the feed's description is not read at all — not to shorten, not " +
                "to excerpt, not for a hover. The safest way not to publish " +
                "somebody's writing is not to hold it.",
            !code.contains("description")
        )
    }

    @Test
    fun `the sleeve gives way, so a record always names itself`() {
        /*
         * MEASURED IN A BROWSER, NOT REASONED ABOUT. `.newone-art` was
         * `width: 100%` with an aspect ratio, which derives the HEIGHT from
         * the width — so the picture was 240px tall whatever room the stage
         * had. At 320x700 the stage was 245px, the panel 223, and the
         * record's own name and artist were laid out at y=390 inside a stage
         * that ends at 368 and clips: a detail screen that never said which
         * record it was about.
         *
         * Driving it from the height inverts that, and the two lines of text
         * then keep theirs while the picture shrinks — the same trade the
         * card makes on a short screen. Re-measured at 320, 360, 390 and 430
         * with both lines inside the stage every time.
         */
        val css = read("app/src/main/assets/web/style.css")
        val block = css.substringAfter(".newone-art {").substringBefore("}")
        assertTrue(".newone-art not found in the stylesheet", block.isNotEmpty())
        // Per declaration, not per substring: `max-width: 100%` is wanted and
        // carries the other one inside it.
        val declared = block.split(";").map { it.trim() }
        assertTrue(
            "the sleeve must be sized by its HEIGHT — `width: 100%` with an " +
                "aspect ratio cannot shrink, and what gets clipped is the " +
                "record's own name",
            declared.none { it == "width: 100%" } && declared.any { it.startsWith("height:") }
        )
        assertTrue(
            "and it must be allowed to shrink: a flex item will not go below " +
                "its content without min-height: 0",
            block.contains("min-height: 0") && block.contains("flex: 0 1 auto")
        )
    }

    @Test
    fun `the wall of sleeves is full-bleed, and only the wall`() {
        /*
         * Reported on the first real run: "not using the full screen, doesn't
         * need to be inside a window". Measured at 390x780 before the change —
         * 16px of .wrap padding, a 1px border and 10px of stage padding EACH
         * side, so 336px of a 390px phone reached the grid and a tile was
         * 104px wide. After: 0px gutter, 370px of grid, a 115px tile.
         *
         * A CARD still gets the frame — it is one picture and the border is
         * its edge — and so does one record opened from a tile, because that
         * is the same kind of object as the card on the other tab. The rule is
         * therefore keyed on the GRID alone.
         */
        val css = read("app/src/main/assets/web/style.css")
        val full = css.substringAfter(".stage.grid-full {").substringBefore("}")
        assertTrue(".stage.grid-full not found in the stylesheet", full.isNotEmpty())
        val declared = full.split(";").map { it.trim() }
        assertTrue(
            "the sleeves are the screen, so the panel's frame comes off",
            declared.any { it == "border: 0" } && declared.any { it.startsWith("background:") }
        )
        assertTrue(
            "and it grows into the space rather than hugging its contents",
            declared.any { it == "flex: 1 1 auto" }
        )
        assertTrue(
            "the gutter is the other half, and it lives on .wrap",
            css.contains(".wrap.edge {")
        )

        val code = page.lines()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") }
            .joinToString("\n")
        assertTrue(
            "restage() decides every stage class from what went into the stage, " +
                "because a caller that has to remember to CLEAR one is a caller " +
                "that will leave the diagnostics' scroll on a card",
            code.contains("classList.toggle(\"grid-full\"") &&
                code.contains("classList.toggle(\"edge\"")
        )
        assertTrue(
            "keyed on the GRID, so one record keeps the frame the card has",
            code.contains("const wall = !!stage.querySelector(\".newgrid\")")
        )
    }

    @Test
    fun `Refresh refreshes the screen you are looking at`() {
        // It called load(true) unconditionally, so pressing it on Discover
        // threw away the sleeves and drew the card — which reads as the button
        // navigating rather than refreshing, and left Discover with no way to
        // ask again at all now that it remembers its answer.
        val code = page.lines()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") }
            .joinToString("\n")
        val handler = code.substringAfter("refresh.addEventListener").substringBefore("});")
        assertTrue("the Refresh handler was not found", handler.isNotEmpty())
        assertTrue(
            "on Discover it must ask Discover again, forcing past the shelf",
            handler.contains("showNewMusic(true)")
        )
        assertTrue(
            "and the forced look has to reach the server as a forced one",
            code.contains("\"/api/new\" + (force ? \"?refresh=1\" : \"\")")
        )
    }
}
