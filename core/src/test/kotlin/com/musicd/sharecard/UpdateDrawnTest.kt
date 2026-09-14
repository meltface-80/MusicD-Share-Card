package com.musicd.sharecard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The update bar says the same thing the build actually does.
 *
 * THE TWO BUILDS DO NOT SHARE A LAST STEP. Android writes an APK and hands it
 * to the system installer, which asks a human; the container unpacks a build
 * beside its own and exits so its launcher can start it. The page had one
 * message for both, so a Docker install sat under "Android is asking you to
 * confirm" for ever — reported from a machine with no Android anywhere near it.
 *
 * Scanned rather than run, because nothing here can open a browser.
 */
class UpdateDrawnTest {

    private val page = File("../app/src/main/assets/web/app.js")

    private fun code(): List<String> {
        assertTrue("cannot find ${page.absolutePath}", page.isFile)
        return page.readLines()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") }
    }

    @Test
    fun `the last step is told apart by which build is updating`() {
        val lines = code()
        val android = lines.filter { it.contains("asking you to confirm") }
        assertTrue("the Android wording has gone entirely — is the bar still drawn?",
            android.isNotEmpty())
        // Every use of it has to be a branch, not the only answer.
        assertTrue(
            "the Android confirm message is used unconditionally, so a " +
                "container would show it too",
            lines.any { it.contains("variant") && it.contains("server") }
        )
    }

    @Test
    fun `the watcher does not give up when the server restarts`() {
        /*
         * THE SERVER GOING AWAY IS THE UPDATE WORKING. The container exits so
         * its launcher can start the build it just unpacked, so the status
         * request fails for a few seconds by design — and the watcher used to
         * clearInterval on the first failure, freezing the bar on whatever it
         * had last read. That is exactly what the report showed.
         */
        val lines = code()
        val watcher = lines.dropWhile { !it.contains("function watchUpdate") }.take(45)
        assertTrue("watchUpdate has gone", watcher.isNotEmpty())
        assertTrue(
            "watchUpdate has no notion of the server restarting, so it will " +
                "stop on the first failed request",
            watcher.any { it.contains("restarting") }
        )
        assertTrue(
            "nothing bounds the wait, so a container that never comes back " +
                "would be polled for ever",
            lines.any { it.contains("MAX_UPDATE_POLLS") }
        )
    }
}
