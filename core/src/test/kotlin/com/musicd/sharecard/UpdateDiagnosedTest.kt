package com.musicd.sharecard

import com.musicd.sharecard.meta.Updater
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WHY THE UPDATE BUTTON DID NOTHING, SOMEWHERE IT CAN BE READ.
 *
 * Reported from a container as exactly that, and `/api/debug` had NOTHING to
 * say about updates — no section, no line. The reasons exist: a failure sets
 * the error phase and the bar draws it. But the bar draws it only while the
 * update is still offered, and a container EXITS mid-update by design, so the
 * page reloads over the one place that said why. What was left was
 * `docker logs`, which is the same fault as the diagnostics report nobody
 * could open and the version number that lived only in /api/debug.
 *
 * THE DIRECTORY IS THE LINE WORTH HAVING. The container is not root, and a
 * bind mount made by hand belongs to somebody else — at which point the card,
 * the page and discovery all work perfectly and the one route that must WRITE
 * fails with "Permission denied". Nothing on the screen connected those two
 * facts. Driven against the real server as uid 10001 with a root-owned data
 * directory, where the report now reads:
 *
 *   running 1.0.20 (server build)
 *   1.0.27 is offered
 *   LAST ATTEMPT FAILED: /var/lib/…/updates/download/update.zip (…)
 *   /var/lib/… IS NOT WRITABLE by sharecard — an update cannot be
 *   downloaded. In Docker: chown -R 10001 <the directory you mounted at /data>
 */
class UpdateDiagnosedTest {

    private fun updater(dir: File, version: String = "1.0.20") = Updater(
        OkHttpClient(),
        version,
        "https://example.invalid/latest.json",
        dir,
        { _, _ -> },
        Updater.Variant.SERVER
    )

    /**
     * THE BRANCH THAT MATTERS MOST, AND IT CANNOT BE REACHED BY CHMOD. These
     * tests run as root here and as somebody else in CI, and root's canWrite
     * is true whatever the mode says — so the probe is injected rather than
     * the directory being made read-only, which would pass for the wrong
     * reason on one machine and fail on the other.
     */
    @Test
    fun `a directory the process cannot write names the uid and the chown`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "sharecard-diag-refused")
        val lines = updater(dir).diagnostics { false }

        val said = lines.joinToString("\n")
        assertTrue(said, lines.any { it.contains("NOT WRITABLE") })
        // The fix, not just the fault: this line is read off a screen in
        // another room and typed into a terminal.
        assertTrue(said, lines.any { it.contains("chown -R 10001") })
        assertTrue(said, lines.any { it.contains("an update cannot be downloaded") })
    }

    @Test
    fun `a writable directory says so and does not shout about a chown`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "sharecard-diag-ok")
        val said = updater(dir).diagnostics { true }.joinToString("\n")

        assertTrue(said, said.contains("looks writable"))
        assertTrue(said, !said.contains("chown"))
    }

    /**
     * The version is the first thing any bug report needs and the reason the
     * settings menu grew a version line. A report about updates that does not
     * say what is running says nothing.
     */
    @Test
    fun `the running version and the variant are always named`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "sharecard-diag-ver")
        val said = updater(dir, "1.0.26").diagnostics { true }.joinToString("\n")

        assertTrue(said, said.contains("running 1.0.26"))
        assertTrue(said, said.contains("server"))
        // Nothing has been checked for on a freshly started host, and that is
        // a different statement from "you are up to date".
        assertTrue(said, said.contains("nothing checked for yet"))
    }
}
