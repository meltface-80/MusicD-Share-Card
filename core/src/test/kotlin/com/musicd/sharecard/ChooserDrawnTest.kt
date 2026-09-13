package com.musicd.sharecard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every field the chooser SENDS is one the page actually draws.
 *
 * SAME MISS AS THE "similar" DIAGNOSTIC, one screen over: a field can be
 * correct, served, and invisible. The chooser is the whole of what the page
 * shows when two rooms are on, so a field dropped here is not a missing detail
 * — it is a room you cannot tell apart from the one beside it, or a tile that
 * leads nowhere.
 *
 * There is no browser here to open the page with, so the sources are scanned,
 * the same way FilePickerContractTest and DiagnosticsDrawnTest scan. app.js is
 * a declared test input of :core — see core/build.gradle.kts — or this would go
 * UP-TO-DATE and check nothing.
 */
class ChooserDrawnTest {

    private fun read(path: String): String {
        val file = File("../$path")
        assertTrue("cannot find $path at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private val page by lazy { read("app/src/main/assets/web/app.js") }

    @Test
    fun `the page reads every field chooserJson puts on a room`() {
        val server = read("core/src/main/kotlin/com/musicd/sharecard/api/CardApi.kt")
        val body = server.substringAfter("internal fun chooserJson")
            .substringBefore("internal fun cardJson")
        assertTrue("chooserJson not found — has CardApi been restructured?", body.isNotEmpty())

        val keys = Regex("""put(?:OrNull)?\s*\(\s*"([a-zA-Z]+)"""")
            .findAll(body).map { it.groupValues[1] }.toSet() -
            // Structural, not per-room: these name the response itself.
            setOf("choose", "rooms", "playing", "notices")

        assertTrue("no room fields found in chooserJson", keys.size >= 5)
        for (key in keys) {
            assertTrue(
                "chooserJson sends \"$key\" and app.js never reads it — " +
                    "correct, served and invisible is the worst of the three",
                page.contains("room.$key") || page.contains("\"$key\"") || page.contains("'$key'")
            )
        }
    }

    @Test
    fun `the page keys off choose rather than counting the rooms itself`() {
        // The rule for when a grid beats a card lives in :core, where it is
        // tested. A copy of it in app.js is a second place for it to drift,
        // and nothing here can test that copy.
        assertTrue(
            "app.js must branch on the server's `choose` flag",
            page.contains("playing.choose")
        )
        assertTrue(
            "app.js must not re-derive the grid rule by counting playing rooms",
            !Regex("""rooms\s*\.\s*filter\([^)]*\)\s*\.\s*length\s*>\s*1""").containsMatchIn(page)
        )
    }

    @Test
    fun `a tile leads to its own zone, through the picker`() {
        // Each zone is independent: the tile has to select that room, not hand
        // the card a room the server chose. Going around the dropdown would
        // also let the two disagree about what is being shown.
        val fn = page.substringAfter("function showChooser").substringBefore("function escapeHtml")
        assertTrue("showChooser not found", fn.isNotEmpty())
        assertTrue("a tile must carry its zone id", fn.contains("data-zone"))
        assertTrue("a tile must drive the picker", fn.contains("zoneSel.value"))
    }

    @Test
    fun `the grid stays reachable, because an empty picker is a CHOICE`() {
        // REPORTED FROM THE FIELD: every individual room could be reached and
        // the grid never could. loadZones took
        //     zoneSel.value || data.selected
        // and "Whatever's playing" IS the empty string, which is falsy — so
        // choosing it fell through to the room the server still remembered,
        // the picker snapped back, and load() then sent that room. The server
        // never saw a request without a zone, so it never cleared its memory,
        // and the state latched permanently.
        // The invariant is not "never mention data.selected" — the seed is
        // still wanted for a page that has just opened. It is that EVERY use
        // of it is gated, so a used picker wins outright.
        // CODE lines only. The comment above the fix quotes the broken
        // expression on purpose, and a scan that reads prose as code fails on
        // the very explanation of what it is guarding.
        val uses = page.lines()
            .filter { it.contains("data.selected") }
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        assertTrue("data.selected is no longer read at all — has loadZones changed?", uses.isNotEmpty())
        for (line in uses) {
            assertTrue(
                "data.selected must be gated by pickerUsed, or choosing " +
                    "Whatever's playing falls through to the remembered room: $line",
                line.contains("pickerUsed")
            )
        }
        // And the flag has to be SET, or it is decoration.
        val handler = page.substringAfter("zoneSel.addEventListener").substringBefore("}")
        assertTrue(
            "choosing in the picker must mark it used",
            handler.contains("pickerUsed = true")
        )
    }

    @Test
    fun `room names and albums are escaped, because they come off the network`() {
        val fn = page.substringAfter("function showChooser").substringBefore("function escapeHtml")
        // A speaker names its own room and a stream names its own track. Both
        // reach this as HTML, so neither may be interpolated raw.
        val raw = Regex("""\$\{(?!escapeHtml)\s*(room\.|line|art\b)""").findAll(fn)
            .map { it.value }.toList()
        assertTrue(
            "unescaped interpolation in showChooser: $raw",
            raw.isEmpty()
        )
    }
}
