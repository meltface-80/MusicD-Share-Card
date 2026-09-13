package com.musicd.sharecard

import com.musicd.sharecard.meta.ServerRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Unpacking a build that arrived over the internet.
 *
 * The first test here is the ordinary case and the second is the reason this
 * file exists: an archive naming `../` writes wherever it likes, and a loop
 * that resolves entry names against a directory without checking does exactly
 * what it is told. It is a bug old enough to have a name and common enough to
 * still be shipped, and the file in question is fetched from the network and
 * then EXECUTED, which is as bad as it gets.
 */
class ServerReleaseTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A distribution zip: one wrapper directory, a launcher and a jar. */
    private fun distZip(name: String, entries: Map<String, String>): File {
        val file = File(temp.root, "$name.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, body) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun ordinary(version: String = "9.9.9") = distZip(
        "dist",
        mapOf(
            "server-$version/bin/server" to "#!/bin/sh\necho hello\n",
            "server-$version/lib/server.jar" to "not really a jar",
            "server-$version/lib/core.jar" to "nor this"
        )
    )

    @Test
    fun `a build unpacks with its wrapper directory stripped`() {
        val dir = File(temp.root, "updates")
        val out = ServerRelease.unpack(ordinary(), dir, "9.9.9")

        assertEquals(File(dir, "9.9.9"), out)
        // bin/server, not server-9.9.9/bin/server: the launcher looks at a
        // fixed path and must not have to know what the archive called itself.
        assertTrue(File(out, "bin/server").isFile)
        assertTrue(File(out, "lib/core.jar").isFile)
        assertEquals("#!/bin/sh\necho hello\n", File(out, "bin/server").readText())
    }

    @Test
    fun `the launcher comes out executable`() {
        // A zip carries no permission bits, so without this the start script
        // arrives unrunnable and the container falls back for ever, silently.
        val out = ServerRelease.unpack(ordinary(), File(temp.root, "updates"), "9.9.9")
        assertTrue(File(out, "bin/server").canExecute())
    }

    @Test
    fun `an archive that escapes its directory is refused outright`() {
        val dir = File(temp.root, "updates")
        // ESCAPES ONLY AS FAR AS THE TEMP FOLDER, ON PURPOSE. An earlier
        // version of this aimed three levels up, and when the guard was
        // removed to prove the test could fail it wrote a real file into /tmp
        // — which then failed the NEXT run for the wrong reason. A test that
        // proves an escape must not leave the blast radius outside its own
        // fixture.
        val escapee = distZip(
            "evil",
            mapOf(
                "server-9.9.9/bin/server" to "#!/bin/sh\n",
                "server-9.9.9/../../escaped" to "owned"
            )
        )
        val threw = try {
            ServerRelease.unpack(escapee, dir, "9.9.9")
            false
        } catch (e: SecurityException) {
            true
        }
        assertTrue("an entry walking out of the target must be refused", threw)
        // And it must not have landed on the way to being refused.
        assertFalse(File(dir, "escaped").exists())
        assertFalse(File(temp.root, "escaped").exists())
    }

    @Test
    fun `an archive with no launcher in it is not a build`() {
        val dir = File(temp.root, "updates")
        val useless = distZip("useless", mapOf("server-9.9.9/lib/server.jar" to "x"))
        val threw = try {
            ServerRelease.unpack(useless, dir, "9.9.9")
            false
        } catch (e: IllegalStateException) {
            true
        }
        assertTrue("an archive with no bin/server must not be marked pending", threw)
    }

    @Test
    fun `unpacking twice replaces rather than merges`() {
        val dir = File(temp.root, "updates")
        ServerRelease.unpack(
            distZip("first", mapOf("s/bin/server" to "#!/bin/sh\n", "s/lib/old.jar" to "old")),
            dir, "9.9.9"
        )
        val out = ServerRelease.unpack(
            distZip("second", mapOf("s/bin/server" to "#!/bin/sh\n", "s/lib/new.jar" to "new")),
            dir, "9.9.9"
        )
        // A half-retried download must not leave the previous attempt's jars
        // beside the new ones, which is a classpath nobody chose.
        assertFalse(File(out, "lib/old.jar").exists())
        assertTrue(File(out, "lib/new.jar").isFile)
    }

    // ------------------------------------------------------- the rollback

    @Test
    fun `a build that serves is kept`() {
        val dir = temp.newFolder("updates")
        File(dir, ServerRelease.TRYING).writeText("9.9.9")
        ServerRelease.promote(dir, "9.9.9")

        assertEquals("9.9.9", File(dir, ServerRelease.ACTIVE).readText())
        // Cleared, so the NEXT boot does not read this as a failed attempt.
        assertFalse(File(dir, ServerRelease.TRYING).exists())
    }

    @Test
    fun `a build that was not being tried changes nothing`() {
        // The ordinary boot: running the active build, nothing on trial. It
        // must not rewrite `active` behind the launcher's back.
        val dir = temp.newFolder("updates")
        File(dir, ServerRelease.ACTIVE).writeText("1.0.0")
        ServerRelease.promote(dir, "1.0.0")
        assertEquals("1.0.0", File(dir, ServerRelease.ACTIVE).readText())
        assertFalse(File(dir, ServerRelease.TRYING).exists())
    }

    @Test
    fun `a build that never serves leaves its trying marker behind`() {
        /*
         * THE WHOLE ROLLBACK TURNS ON THIS. `promote` is called only once a
         * build has started and is serving; a build that crashes first never
         * reaches it, so `trying` survives — and that leftover marker is what
         * the launcher reads on the next boot to know the update was bad.
         */
        val dir = temp.newFolder("updates")
        File(dir, ServerRelease.TRYING).writeText("9.9.9")
        // ...crash: promote is never called.
        assertTrue(File(dir, ServerRelease.TRYING).isFile)
        assertFalse(File(dir, ServerRelease.ACTIVE).exists())
    }

    @Test
    fun `pending is written last`() {
        val dir = temp.newFolder("updates")
        ServerRelease.markPending(dir, "9.9.9")
        assertEquals("9.9.9", File(dir, ServerRelease.PENDING).readText())
    }
}
