package com.musicd.sharecard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A FUNCTION USED BARE AS AN EVENT HANDLER IS CALLED WITH THE EVENT.
 *
 * `tabNew.addEventListener("click", showNewMusic)` and
 * `back.onclick = showNewMusic` both look like "call this when tapped" and are
 * not: the browser passes a MouseEvent as the first argument. `showNewMusic`
 * takes `force`, and an event object is TRUTHY — so opening the Discover tab,
 * and coming back to it from a record, each asked the server for `?refresh=1`.
 *
 * That makes the server throw away the screen it is holding and go out to
 * ListenBrainz, two RSS feeds, Deezer and a sleeve lookup per record. Reported
 * from the field as Discover taking a while to populate and appearing to fully
 * reload on the way back — and the cache was working the whole time, being
 * discarded on every single tap. An hour of remembering, undone by two missing
 * brackets.
 *
 * THE INVARIANT IS ABOUT ARITY, NOT ABOUT THESE TWO LINES. Any parameterless
 * function is safe to hand over bare; any function with a parameter is not,
 * and the failure is silent because the extra argument is simply accepted.
 * `showSettings`, `addWebhook` and `startUpdate` are all passed bare today and
 * all take nothing, which is exactly why this scan passes rather than being
 * written to exclude them.
 */
class HandlerArityTest {

    private val page: String by lazy {
        val file = File("../app/src/main/assets/web/app.js")
        assertTrue("cannot find app.js at ${file.absolutePath}", file.isFile)
        file.readText()
    }

    /** Comments quote the broken form on purpose — a scan must read code. */
    private fun codeOnly(text: String): String {
        val withoutBlocks = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(text, "")
        return withoutBlocks.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
    }

    /** Every `f` in `addEventListener("x", f)` or `el.onclick = f;`. */
    private fun bareHandlers(code: String): List<String> {
        val listeners = Regex("""addEventListener\(\s*"[a-z]+"\s*,\s*([A-Za-z_$][\w$]*)\s*[,)]""")
            .findAll(code).map { it.groupValues[1] }
        val assigned = Regex("""\.\s*onclick\s*=\s*([A-Za-z_$][\w$]*)\s*;""")
            .findAll(code).map { it.groupValues[1] }
        return (listeners + assigned).toList()
    }

    /** The declared parameter list of a named function, or null if not found. */
    private fun paramsOf(code: String, name: String): String? =
        Regex("""function\s+${Regex.escape(name)}\s*\(([^)]*)\)""").find(code)?.groupValues?.get(1)

    @Test
    fun `nothing taking an argument is handed straight to an event`() {
        val code = codeOnly(page)
        val handlers = bareHandlers(code)
        assertTrue(
            "no bare handlers found at all — has app.js been restructured? " +
                "This scan is worthless if it matches nothing.",
            handlers.isNotEmpty()
        )
        for (name in handlers) {
            val params = paramsOf(code, name) ?: continue  // a local, not ours to judge
            assertTrue(
                "$name is passed straight to an event but declares ($params) — the " +
                    "browser will call it with the Event as that argument. Wrap it: " +
                    "() => $name(...)",
                params.isBlank()
            )
        }
    }

    @Test
    fun `and Discover is never opened with a forced refresh`() {
        // The specific cost, named: only the Refresh button may force, because
        // forcing means the server drops an hour of remembering and goes back
        // to the network.
        val code = codeOnly(page)
        val forced = Regex("""showNewMusic\(\s*true\s*\)""").findAll(code).count()
        assertTrue(
            "showNewMusic(true) should appear exactly once — under the Refresh " +
                "button — and appears $forced times",
            forced == 1
        )
        assertTrue(
            "showNewMusic must never be referenced without being called",
            !Regex("""showNewMusic(?!\s*\()""").containsMatchIn(
                code.replace(Regex("""(async\s+)?function\s+showNewMusic"""), "")
            )
        )
    }
}
