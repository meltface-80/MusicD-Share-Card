package com.musicd.sharecard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every section /api/debug reports is one the debug page actually draws.
 *
 * THIS IS WRITTEN FROM A MISS. The similar-artist lookup gained an `attempts()`
 * list and a "similar" key in the report, and the page was never taught to draw
 * it — so the diagnostic existed, was correct, was served, and was invisible to
 * the one person who needed it. That is worse than not adding it: the next
 * report says "there is nothing under Similar artists" and means "there is no
 * such heading".
 *
 * The device this runs on is normally in another room with no adb attached.
 * /api/debug read off its screen IS the bug report, and a section missing from
 * it is a question nobody can answer. There is no browser here to open the page
 * with, so the sources are scanned — the same way FilePickerContractTest scans
 * for a WebChromeClient, and for the same reason: silence.
 */
class DiagnosticsDrawnTest {

    private fun read(path: String): String {
        val file = File("../$path")
        assertTrue("cannot find $path at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /**
     * AND SOMEBODY HAS TO BE ABLE TO OPEN IT.
     *
     * Reported as "The device section - where what??", which is the right
     * question: there was no way in. `offerDiagnostics()` draws its button only
     * when discovery has failed outright, so on an app that is finding every
     * room perfectly the report existed, was correct, was served, was drawn by
     * code this very test class covers — and could be reached only by typing
     * /api/debug into a browser. That is the same complaint the version line at
     * the foot of the settings menu was added to fix, one screen further on.
     *
     * The test above made it HARDER to notice rather than easier: proving the
     * page can draw every key reads as "the report is fine". Drawn and
     * reachable are two different claims, so this asserts the second.
     */
    @Test
    fun `the report can be opened from an app that is working`() {
        val page = read("app/src/main/assets/web/app.js")
        val lines = page.lines()

        val opener = lines.indexOfFirst { it.contains("function showSettings") }
        assertTrue("showSettings has gone", opener >= 0)
        // Generous: the menu is a long call plus its bindings.
        val menu = lines.drop(opener).take(120).joinToString("\n")

        val handlers = Regex("""bind\(\s*"[^"]+"\s*,\s*([A-Za-z_]\w*)\s*\)""")
            .findAll(menu).map { it.groupValues[1] }.toSet()
        assertTrue("the settings menu binds no named handler at all", handlers.isNotEmpty())

        // One of them must actually go and get the report. Asserted as "a
        // screen the MENU leads to asks for it", not by naming a function,
        // because which screen does it is not the invariant.
        val reaches = handlers.any { name ->
            val at = lines.indexOfFirst { it.contains("function $name(") }
            at >= 0 && lines.drop(at).take(24).any { it.contains("/api/debug") }
        }
        assertTrue(
            "nothing in the settings menu opens /api/debug, so the only way in " +
                "is a failed discovery or typing the path by hand — which is " +
                "how this was reported",
            reaches
        )
    }

    @Test
    fun `nothing is reported that the page cannot show`() {
        val server = read("core/src/main/kotlin/com/musicd/sharecard/api/Diagnostics.kt")
        val page = read("app/src/main/assets/web/app.js")

        // Every top-level key of the report, however it is put there: some are
        // one-liners and some span an argument list.
        val keys = Regex("""report\s*\.\s*put\s*\(\s*"([a-zA-Z]+)"""")
            .findAll(server).map { it.groupValues[1] }.toMutableSet()
        keys += Regex("""report\s*\.\s*put\s*\(\s*\n\s*"([a-zA-Z]+)"""")
            .findAll(server).map { it.groupValues[1] }

        assertTrue("no keys found — has Diagnostics been restructured?", keys.size >= 5)

        for (key in keys) {
            assertTrue(
                "/api/debug reports \"$key\" and the debug page never reads d.$key — " +
                    "a diagnostic nobody can see is worse than none",
                Regex("""\bd\.$key\b""").containsMatchIn(page)
            )
        }
    }
}
