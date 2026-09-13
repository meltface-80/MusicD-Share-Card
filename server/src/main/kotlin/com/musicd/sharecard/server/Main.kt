package com.musicd.sharecard.server

import com.musicd.sharecard.Log
import com.musicd.sharecard.SeedHosts
import com.musicd.sharecard.ShareCardApp
import com.musicd.sharecard.api.Assets
import com.musicd.sharecard.describe
import com.musicd.sharecard.meta.FileCacheStore
import com.musicd.sharecard.meta.ServerRelease
import com.musicd.sharecard.meta.Updater
import com.musicd.sharecard.roon.FileTokenStore
import com.musicd.sharecard.settings.FileSettingsStore
import com.musicd.sharecard.webhook.FileWebhookStore
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch

/**
 * The same card server, without Android around it.
 *
 * WHY THIS EXISTS AT ALL. The app runs on whatever Android device is in the
 * rack, and that device is doing this job badly: it sleeps, it is killed for
 * memory, its foreground service has to be argued into staying alive, and
 * updating it means an APK signed with a key that must never change. A machine
 * that is already always on — the NAS, the Pi, the box Lyrion is on — does none
 * of that.
 *
 * IT IS THE SAME PROGRAM, NOT A SECOND ONE. Everything that decides anything
 * lives in :core, which has no Android in it and never did; this file is what
 * `CardService` is, minus the notification and the multicast lock. The page is
 * the same bytes, taken off this module's classpath from the APK's own assets
 * directory rather than copied. Nothing about the card, the sources or the
 * routes is decided here.
 *
 * WHAT IS DELIBERATELY DIFFERENT, and why:
 *
 *  - NO UPDATER. The Android build downloads an APK and hands it to the package
 *    installer; a container replaces itself with `docker compose pull`, so the
 *    update routes answer "not available here" and the page hides the bar —
 *    which is what it already does for any host that passes no installer.
 *  - NO MULTICAST LOCK. That is an Android permission problem. It is replaced
 *    by a different one: SSDP, Roon's SOOD and Lyrion's broadcast are all
 *    multicast or broadcast, and NONE of them cross a Docker bridge. The image
 *    is documented to need host networking for that reason, and
 *    [SHARECARD_HOSTS] is the way out when host networking is not possible.
 *  - THE PIN CAN BE SET. See [webhookStore].
 */
fun main() {
    Log.sink = StdoutSink()
    // ON, not off — the same decision CardService makes, for the same reason:
    // with it off, a speaker that would not answer, a SOAP call that threw and
    // a cover that 404'd are all completely silent, and `docker logs` is the
    // only diagnostic window a container has.
    Log.debug = env("SHARECARD_DEBUG")?.lowercase() !in setOf("0", "false", "off", "no")

    val data = File(env("SHARECARD_DATA") ?: "/data")
    val version = env("SHARECARD_VERSION")
        ?: System.getProperty("sharecard.version")
        ?: "dev"

    Log.i(TAG, "MusicD Share Card $version, data in ${data.absolutePath}")
    checkWritable(data)

    val updates = File(data, "updates")
    val cache = FileCacheStore(File(data, "cache"))
    val webhooks = webhookStore(File(data, "webhooks.json"))

    /*
     * NO PIN UNLESS ONE IS ASKED FOR, AND THAT IS THE OPPOSITE OF ANDROID.
     *
     * The gate trusts loopback and challenges everything else, which on a
     * phone means the app's own WebView is free and a browser across the house
     * is not. In a container there is usually no browser on the machine at
     * all, so EVERY visit is a remote one and the gate applied to everybody —
     * for switching a room on, which is the first thing anyone does, with the
     * PIN only obtainable from `docker logs`. Asked for directly.
     *
     * Setting SHARECARD_PIN turns it back on, which is the answer for anyone
     * whose network is not one they trust.
     */
    val requirePin = env("SHARECARD_PIN") != null

    val app = try {
        ShareCardApp(
            assets = classpathAssets(),
            seedHosts = seedHosts(data),
            port = env("SHARECARD_PORT")?.toIntOrNull() ?: ShareCardApp.DEFAULT_PORT,
            bindAddress = env("SHARECARD_BIND") ?: "0.0.0.0",
            version = version,
            tokenStore = FileTokenStore(File(data, "roon.json")),
            webhookStore = webhooks,
            cacheStore = cache,
            // Which services are linked and which rooms may be shown. Opt-in,
            // so a first run here shows the empty state and points at
            // Settings rather than drawing a card nobody asked for.
            settingsStore = FileSettingsStore(File(data, "settings.json")),
            requirePin = requirePin,
            /*
             * UPDATING FROM THE APP, THE SAME WAY ANDROID DOES.
             *
             * The note above this used to say there was no updater here. There
             * is now, and it is the same class: check a manifest, download,
             * verify the sha256, hand the file over. Only the last step
             * differs, because a container cannot replace its own image
             * without the Docker socket and this app is deliberately not given
             * it — the socket is root on the host. So the CODE moves instead:
             * unpack beside the running build, mark it pending, and exit.
             * Docker's restart policy brings the container back and the
             * launcher runs the new one.
             */
            updateInstaller = ShareCardApp.UpdateInstaller(
                /*
                 * A SCRATCH DIRECTORY OF ITS OWN, BELOW the one holding the
                 * unpacked builds. The downloader empties its directory before
                 * every attempt — one file, replaced each time, so a device
                 * nobody opens does not accumulate every version ever offered
                 * — and pointing that at the directory that also holds the
                 * unpacked versions and the markers would have it deleting
                 * them.
                 */
                downloadDir = File(updates, "download"),
                variant = Updater.Variant.SERVER,
                install = { archive, version -> applyServerUpdate(updates, archive, version) }
            )
            // updateInstaller is deliberately absent: see the note above.
        ).also { it.start() }
    } catch (t: Throwable) {
        // Named, not wrapped. "ExceptionInInitializerError: null" is a real
        // message this app has shown, and it says nothing at all.
        Log.e(TAG, "the card server could not start: ${describe(t)}", t)
        return
    }

    /*
     * THIS BUILD SERVED, SO IT IS THE ONE TO KEEP.
     *
     * Reached only after the socket is bound and the app is up. A build that
     * crashes before here never clears the launcher's `trying` marker, and the
     * next boot reads that leftover as "the update was bad", throws the
     * version away and falls back. That is the entire rollback, and it hangs
     * on this line being late rather than early.
     */
    ServerRelease.promote(updates, version)

    for (url in app.lanUrls()) Log.i(TAG, "open $url on any device on this network")
    if (requirePin) announcePin(webhooks.pin())
    else Log.i(TAG, "no PIN needed on this network; set SHARECARD_PIN to require one")

    // Every thread the server owns is a daemon, so without this the process
    // would start the socket and immediately exit.
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread {
        Log.i(TAG, "stopping")
        // Anything looked up in the last few seconds is still only in memory,
        // and this is the last chance to keep it.
        runCatching { cache.flush() }
        runCatching { app.stop() }
        stopped.countDown()
    })
    stopped.await()
}

private const val TAG = "Server"

/**
 * Says so, loudly, when the data directory cannot be written.
 *
 * THE FAILURE THIS REPLACES IS A SILENT ONE. Every write in this app is
 * deliberately survivable — a cache that cannot be written costs one slow
 * lookup, a token that cannot be written costs one tap in Roon — so a
 * read-only mount produces an app that works perfectly and forgets everything
 * the moment it restarts, with nothing anywhere saying why. The container runs
 * as a non-root user, which makes a freshly created bind mount owned by
 * somebody else the NORMAL first-run mistake rather than an exotic one.
 *
 * It is a warning and not a refusal: the card still draws, and a server that
 * will not start is a worse answer than one that cannot remember.
 */
private fun checkWritable(data: File) {
    val probe = File(data, ".writable")
    val ok = try {
        data.mkdirs()
        probe.writeText("")
        probe.delete()
        true
    } catch (t: Throwable) {
        false
    }
    if (ok) return
    Log.w(TAG, "${data.absolutePath} is not writable by uid ${uid()} - the Roon pairing, " +
        "the Discord webhooks and the metadata cache will not survive a restart. " +
        "Fix it with: chown -R ${uid()} <the directory you mounted at ${data.absolutePath}>")
}

private fun uid(): String =
    runCatching { com.sun.security.auth.module.UnixSystem().uid.toString() }.getOrDefault("this user")

// ---------------------------------------------------------------- the page

/**
 * The web app, off this module's classpath.
 *
 * `app/src/main/assets` is a resource root for :server — see its build file —
 * so `web/app.js` is the same path the Android AssetManager is given and the
 * same bytes the APK carries. A missing asset is a 404, not a crash: the path
 * came off the network, and naming a file that is not there is ordinary.
 */
private fun classpathAssets(): Assets {
    val loader = object {}.javaClass.classLoader
    return Assets { path ->
        try {
            loader?.getResourceAsStream("web/$path")?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.d(TAG, "no bundled asset web/$path: ${e.message}")
            null
        }
    }
}

// --------------------------------------------------------------- the hosts

/**
 * Addresses to try before searching, from the file and from the environment.
 *
 * BOTH, because a container is configured two ways and people reach for the one
 * their setup already uses: a bind mount with a `hosts.txt` in it, or a line in
 * the compose file. The deciding is [SeedHosts.parse], in :core, where it is
 * tested — the same list Sonos and Lyrion both draw on, because from the user's
 * side "where are my players" is one question and not one per protocol.
 *
 * THIS MATTERS MORE HERE THAN ON ANDROID. SSDP, Roon's SOOD and Lyrion's
 * broadcast all die at a Docker bridge, so on a network where host networking
 * is not available these addresses are the whole of discovery.
 */
private fun seedHosts(data: File): List<String> {
    val file = File(data, SeedHosts.FILE_NAME)
    val fromFile = try {
        if (file.isFile) SeedHosts.parse(file.readText()) else emptyList()
    } catch (e: Exception) {
        // Unreadable is the same as absent: discovery is still the normal path.
        Log.w(TAG, "could not read ${file.path}: ${e.message}")
        emptyList()
    }
    val fromEnv = SeedHosts.parse(env("SHARECARD_HOSTS").orEmpty())
    val hosts = (fromFile + fromEnv).distinct()
    if (hosts.isNotEmpty()) Log.i(TAG, "starting with hosts from the file: $hosts")
    return hosts
}

// ----------------------------------------------------------------- the PIN

/**
 * Webhooks, with the PIN settable from the environment.
 *
 * THE DEADLOCK THIS UNDOES. Adding a webhook is gated by [Access], which trusts
 * loopback without a PIN and otherwise wants one — and the PIN is served ONLY
 * to loopback, because on the Android build loopback means the app's own WebView
 * on the device somebody is holding. In a container there is frequently no such
 * device: the server is a box in a cupboard with no browser on it, so nobody can
 * ever reach the one page that would show them the PIN, and webhooks cannot be
 * configured at all.
 *
 * Two ways out, and this is both of them. SHARECARD_PIN sets it, so it is known
 * before the container starts; and failing that the minted one is printed to the
 * log, which is the container's equivalent of standing in front of the device —
 * whoever can run `docker logs` already owns the process and its data directory.
 *
 * A PIN is refused rather than quietly accepted if it is not six digits: the
 * page's own field takes six, so a four-character one would be a PIN nobody
 * could type, and silently ignoring it would leave the operator believing they
 * had set one.
 */
private fun webhookStore(file: File): FileWebhookStore {
    val wanted = env("SHARECARD_PIN")?.trim().orEmpty()
    if (wanted.isEmpty()) return FileWebhookStore(file)
    if (wanted.length != PIN_LENGTH || !wanted.all { it in '0'..'9' }) {
        Log.w(TAG, "SHARECARD_PIN must be $PIN_LENGTH digits - ignoring it")
        return FileWebhookStore(file)
    }
    return object : FileWebhookStore(file) {
        override fun pin(): String = wanted
    }
}

private const val PIN_LENGTH = 6

private fun announcePin(pin: String) {
    if (env("SHARECARD_PIN")?.trim()?.length == PIN_LENGTH) {
        Log.i(TAG, "setup PIN: set by SHARECARD_PIN")
        return
    }
    // Printed because there may be no browser on this machine to show it to —
    // see [webhookStore]. It gates adding and removing a Discord webhook, and
    // nothing else.
    Log.i(TAG, "setup PIN for adding a webhook from another device: $pin")
}

// --------------------------------------------------------------- plumbing

private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

/**
 * One line per event on stdout, which is what `docker logs` reads.
 *
 * No log file and no rotation: the container runtime owns that, and a second
 * place for logs to fill a disk is a second thing to go wrong on a machine
 * nobody is looking at.
 */
private class StdoutSink : Log.Sink {
    override fun write(level: Char, tag: String, message: String, error: Throwable?) {
        val line = "${Instant.now()} $level ${tag.padEnd(12)} $message"
        if (level == 'E' || level == 'W') System.err.println(line) else println(line)
        error?.printStackTrace(if (level == 'E' || level == 'W') System.err else System.out)
    }
}

/**
 * Unpack a downloaded build and stand aside for it.
 *
 * EXITING IS THE INSTALL. There is nothing else a process can do to replace
 * itself: the new code is on disk, the launcher will prefer it, and the only
 * remaining step is to stop being the thing that is running. `restart:
 * unless-stopped` — which the compose file and the README's `docker run` both
 * set — is what brings the container back a second later.
 *
 * The exit is on its own thread and slightly delayed for one reason: this is
 * called from inside the request that pressed Update, and a process that dies
 * mid-response leaves the page with a dropped connection instead of an answer.
 */
private fun applyServerUpdate(updates: File, archive: File, version: String) {
    ServerRelease.unpack(archive, updates, version)
    ServerRelease.markPending(updates, version)
    runCatching { archive.delete() }
    Log.i(TAG, "unpacked $version; restarting into it")

    Thread({
        runCatching { Thread.sleep(1_500) }
        // Not a crash: the launcher and the restart policy between them are
        // what make this an update rather than a stop.
        Runtime.getRuntime().exit(0)
    }, "sharecard-restart").apply { isDaemon = false }.start()
}
