package com.musicd.sharecard.meta

import com.musicd.sharecard.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * Unpacking a downloaded build beside the running one.
 *
 * A CONTAINER CANNOT REPLACE ITS OWN IMAGE, and this app is deliberately not
 * given the means to try. Pulling an image means the Docker socket, and the
 * socket is root on the host — handed to a process that answers the whole LAN
 * and whose every route is a read precisely so that it holds nothing worth
 * attacking. So the image stays put and the CODE moves: a build is unpacked
 * into the data directory, marked pending, and the process exits. Docker's own
 * restart policy brings it back, and the launcher runs the new one.
 *
 * The cost is stated plainly in the README rather than hidden: the JRE and the
 * OS packages underneath only change when somebody pulls a new image by hand.
 *
 * A ZIP AND NOT A TAR, for one dull reason: the JDK reads zip and has never
 * read tar, and adding an archive library to a module whose whole dependency
 * list is two lines would be a strange price for a file format. The executable
 * bit a zip cannot carry is put back below.
 */
object ServerRelease {

    private const val TAG = "ServerRelease"

    /** Where the launcher looks. Kept together so nothing else must agree. */
    const val PENDING = "pending"
    const val TRYING = "trying"
    const val ACTIVE = "active"

    /**
     * Unpacked builds live BELOW the markers, never beside them.
     *
     * A VERSION IS A NAME FROM THE NETWORK, and the markers are names this app
     * chose — so in one flat directory a build called "pending" makes a
     * DIRECTORY where the marker file has to go, and the update dies with
     * "pending (Is a directory)". That is not hypothetical: it is exactly what
     * happened when the installer could not work out the version and fell back
     * to a placeholder, which it had picked from this very list. One level of
     * nesting means the two namespaces cannot touch, whatever a manifest says
     * a version is called.
     */
    const val VERSIONS = "versions"

    /**
     * Unpack [archive] into `<dir>/<version>` and mark it pending.
     *
     * EVERY ENTRY IS CHECKED AGAINST THE DESTINATION, because this file came
     * off the network. A zip may name `../../etc/whatever` or an absolute path,
     * and a loop that blindly resolves entry names against a directory will
     * write exactly there — the bug is old enough to have a name (Zip Slip) and
     * common enough to keep being shipped. Nothing outside the target directory
     * is written, and an archive that tries ends the whole unpack rather than
     * being quietly skipped: a build that lies about its contents is not one to
     * install the rest of.
     */
    fun unpack(archive: File, dir: File, version: String): File {
        val target = File(File(dir, VERSIONS), version)
        if (target.exists()) target.deleteRecursively()
        if (!target.mkdirs()) throw IllegalStateException("could not make ${target.path}")
        val root = target.canonicalFile

        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                // The distribution zip wraps everything in one directory named
                // after the build. It is stripped, so the layout below is the
                // same whatever the archive calls itself.
                val name = entry.name.substringAfter('/', "")
                if (name.isEmpty()) continue

                val out = File(root, name).canonicalFile
                if (out != root && !out.path.startsWith(root.path + File.separator)) {
                    throw SecurityException("archive entry escapes the target: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }

        // A zip carries no permissions, so the start script comes out
        // unexecutable and the launcher would silently fall back for ever.
        val launcher = File(target, "bin/server")
        if (!launcher.isFile) throw IllegalStateException("no bin/server in the archive")
        if (!launcher.setExecutable(true)) {
            Log.w(TAG, "could not mark ${launcher.path} executable")
        }
        return target
    }

    /**
     * Hand the unpacked build to the launcher and say the process should go.
     *
     * Writing the marker is the LAST thing: a half-unpacked directory with no
     * marker is ignored and cleaned up, where a marker written first would
     * point the launcher at a build that may never have finished arriving.
     */
    fun markPending(dir: File, version: String) {
        File(dir, PENDING).writeText(version)
    }

    /**
     * This build started and served, so it is the one to keep.
     *
     * THE ROLLBACK TURNS ON THIS LINE. The launcher records what it is TRYING
     * before it runs it and never clears that itself; only a build that gets
     * far enough to serve clears it, by calling this. So a build that crashes
     * on startup — a bad download, a JRE that will not run it, a file the
     * unpack got wrong — leaves `trying` behind, and the next boot sees it,
     * throws that version away and falls back. A container cannot be bricked
     * by an update it could not run.
     */
    fun promote(dir: File) {
        val trying = File(dir, TRYING)
        if (!trying.isFile) return
        runCatching {
            /*
             * WHAT THE LAUNCHER LAUNCHED, NOT WHAT THIS BUILD CALLS ITSELF.
             *
             * These are two names for one thing and they came from different
             * places: the DIRECTORY is named after the manifest's version, and
             * the running process reports whatever was baked into it. Promote
             * used to write the second into a file the launcher reads as the
             * first — so the moment they disagreed, `active` named a directory
             * that does not exist, the next boot found nothing there and fell
             * back to the build in the image. An update that appeared to work
             * and then quietly undid itself on the next restart, which is
             * close to undiagnosable from the outside.
             *
             * Caught by watching a real update restart with the two
             * deliberately different. The launcher is the one that knows, so
             * its own marker is what gets promoted.
             */
            val started = trying.readText().trim()
            if (started.isEmpty()) {
                Log.w(TAG, "the trying marker was empty; not promoting anything")
                return
            }
            File(dir, ACTIVE).writeText(started)
            trying.delete()
            Log.i(TAG, "running $started, kept")
        }.onFailure { Log.w(TAG, "could not record the running build as good: $it") }
    }
}
