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
