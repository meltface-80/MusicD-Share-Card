package com.musicd.sharecard.meta

import com.musicd.sharecard.Log
import com.musicd.sharecard.bool
import com.musicd.sharecard.str
import com.musicd.sharecard.strOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * In-app updates: notice a newer APK, fetch it, hand it to Android to install.
 *
 * Ported from MusicD Remote Lite, which does exactly this and has been doing it
 * since its 0.1.8 — the same manifest shape, the same phase machine, the same
 * split between what can be tested here and what needs a device.
 *
 * AN APP CANNOT REPLACE ITSELF. Only the system installer may, and it always
 * asks the user first. So "apply" here means download, verify, and then present
 * the install — everything up to the point where Android takes over.
 *
 * THE WHOLE THING RESTS ON STABLE SIGNING. Android refuses to install an APK
 * over one signed with a different certificate, and all it says is "App not
 * installed" — no reason, nothing in a log anybody will see. CI mints a fresh
 * debug key on every runner, so an unsigned-release build can never be updated
 * over. That is what the manifest's `signed` flag is for: when it is false this
 * refuses to start a download that could only ever end in that dialog, and says
 * why instead. See [State.blocked].
 *
 * The download and the install are separated by an [install] callback so that
 * everything except the Android intent is a plain JVM class with tests: the
 * version compare, the manifest parsing, the host check, the phase machine and
 * the digest check all run without a device.
 */
class Updater(
    private val http: OkHttpClient,
    private val currentVersion: String,
    private val manifestUrl: String,
    private val downloadDir: File,
    /**
     * Hands the finished file, and the VERSION it is, to whatever installs it.
     *
     * THE VERSION IS PASSED BECAUSE THE FILENAME DOES NOT CARRY IT. The
     * download is written to one fixed name, replaced each time — so the
     * container's installer, which has to unpack into a directory named after
     * the version, was reading it out of a name that never had it and falling
     * back to a placeholder. That placeholder was the word "pending", which is
     * also the name of the marker file beside it, so the unpack created a
     * DIRECTORY called pending and the marker could not then be written:
     * "pending (Is a directory)". Found by actually applying a real published
     * update; every test until then had called the unpacker directly with a
     * version in hand, which is precisely the step that was broken.
     */
    private val install: (File, String) -> Unit,
    /** Which build this host runs, and therefore which half of the manifest. */
    val variant: Variant = Variant.ANDROID
) {

    /**
     * The two things this app ships as, out of ONE manifest.
     *
     * ONE FILE AND NOT TWO, because they are published by one run from one
     * version number and a second manifest is a second thing to fall out of
     * step — which this repo has already watched happen once, when a branch
     * build left main's manifest naming an APK on a branch url.
     *
     * What differs is only which half is read and which rules apply:
     *
     *  - ANDROID reads the top level and is subject to the SIGNING rule. The
     *    system installer refuses an APK signed with a different certificate
     *    and says only "App not installed", so an unsigned build is refused
     *    here with a reason instead.
     *  - SERVER reads the `server` block and has no signing rule to apply,
     *    because nothing is being installed over anything: the container
     *    unpacks a build beside its own and restarts into it. Its guard is the
     *    sha256 and the same-host rule, which both variants share.
     */
    enum class Variant(val wire: String) { ANDROID("android"), SERVER("server") }

    /**
     * What the page shows while this is running.
     *
     * Unlike the app this was ported from, the names here are the honest ones.
     * That app inherited "extracting" from a Docker build where it really did
     * unpack a tarball, and was stuck with it because its banner matched on the
     * string. This front-end is being written alongside this class, so there is
     * nothing to be compatible with.
     */
    enum class Phase(val wire: String) {
        IDLE("idle"),
        CHECKING("checking"),
        DOWNLOADING("downloading"),
        VERIFYING("verifying"),
        AWAITING_INSTALL("installing"),
        ERROR("error")
    }

    data class Release(
        val version: String,
        val url: String,
        val sha256: String?,
        val notes: String?,
        /**
         * Whether CI signed this build with the release key.
         *
         * False means a throwaway debug key, which Android will not accept over
         * an existing install however correct everything else is.
         */
        val signed: Boolean
    )

    data class State(
        val phase: Phase = Phase.IDLE,
        val error: String? = null,
        val latest: Release? = null
    )

    private val state = AtomicReference(State())
    private val busy = AtomicBoolean(false)

    /** Newer than what is running, and installable over it. */
    private fun isNewer(release: Release?): Boolean =
        release != null && compareVersions(release.version, currentVersion) > 0

    /**
     * Why an available update cannot be installed in place, or null.
     *
     * This is the one thing worth saying out loud rather than letting Android
     * say "App not installed" and leaving somebody to work it out.
     */
    private fun blockedReason(release: Release?): String? = when {
        release == null -> null
        // Android's alone: there is no certificate to match on a server build.
        variant == Variant.SERVER -> null
        !release.signed ->
            "This build isn't signed with a release key, so Android can't install " +
                "it over the top — uninstall the app first, then install ${release.version}."
        else -> null
    }

    fun status(): JSONObject {
        val s = state.get()
        val latest = s.latest
        return JSONObject()
            .put("current", currentVersion)
            .put("available", isNewer(latest))
            .put("latest", latest?.version ?: JSONObject.NULL)
            .put("notes", latest?.notes ?: JSONObject.NULL)
            .put("url", latest?.url ?: JSONObject.NULL)
            // False means "downloading it would end in App not installed".
            .put("installable", latest != null && latest.signed)
            /*
             * WHO MAY PRESS UPDATE, and the two builds genuinely differ.
             *
             * The APK installs on THIS device, so a page open on an iPad across
             * the house is looking at software it cannot replace — that bar was
             * reported as "shows the update button, does nothing" and is hidden
             * off the socket address. A server update replaces the machine
             * serving the page, which is the same machine whichever browser
             * asked, so there is nothing device-specific to hide. The gate
             * still applies: from anywhere but loopback it wants the PIN.
             */
            .put("fromAnyDevice", variant == Variant.SERVER)
            .put("variant", variant.wire)
            .put("blocked", blockedReason(latest) ?: JSONObject.NULL)
            .put(
                "phase", JSONObject()
                    .put("name", s.phase.wire)
                    .put("error", s.error ?: JSONObject.NULL)
            )
    }

    /** Re-reads the manifest. Synchronous: the page asks, and reads the answer. */
    fun check(): JSONObject {
        val phase = state.get().phase
        if (phase == Phase.DOWNLOADING || phase == Phase.AWAITING_INSTALL) return status()
        return accept(runCatching { fetchManifest() }.getOrNull())
    }

    /** What [check] does with whatever the manifest turned out to be. */
    internal fun accept(release: Release?): JSONObject {
        state.set(
            if (release == null) State(Phase.ERROR, "Couldn't read the update manifest", null)
            else State(Phase.IDLE, null, release)
        )
        return status()
    }

    internal fun fetchManifest(): Release? {
        val body = get(manifestUrl) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return parseManifest(json)
    }

    /**
     * Read one manifest, and refuse anything this app should not fetch.
     *
     * THE HOST CHECK IS THE POINT, not the https check. The URL in here names a
     * file this app will download and hand to Android's installer, so a
     * manifest that could point anywhere is a manifest that could install
     * anything. It must name the same host the manifest itself came from.
     *
     * The app this was ported from carries a comment saying exactly that and
     * then only checks the scheme — so a manifest served from GitHub could name
     * an APK on any https host in the world. Fixed here rather than copied.
     */
    internal fun parseManifest(json: JSONObject): Release? {
        val version = json.strOrNull("version") ?: return null

        // WHICH HALF. A manifest written before the server build existed has no
        // `server` block at all, and the honest reading of that is "there is no
        // server update here" rather than an error or, worse, the APK's URL.
        val part = when (variant) {
            Variant.ANDROID -> json
            Variant.SERVER -> json.optJSONObject("server") ?: return null
        }

        val url = part.strOrNull("url") ?: return null
        if (!url.startsWith("https://")) return null
        if (!sameHost(url, manifestUrl)) {
            Log.w(TAG, "manifest names a download on another host: $url")
            return null
        }
        return Release(
            version = version,
            url = url,
            sha256 = part.strOrNull("sha256"),
            notes = json.strOrNull("notes"),
            // Absent means "an older manifest that predates the flag", and the
            // safe reading of that is unsigned. The SERVER build has no
            // certificate to match, so the flag does not apply to it and is
            // not allowed to block it.
            signed = variant == Variant.SERVER || json.bool("signed")
        )
    }

    /**
     * Download the newest APK and hand it to the installer.
     *
     * Returns immediately; the page polls [status]. One at a time — a second
     * tap while a download runs must not start another writing the same file.
     */
    fun apply(runner: (Runnable) -> Unit): JSONObject {
        val release = state.get().latest ?: run { check(); state.get().latest }
        if (release == null) {
            state.set(State(Phase.ERROR, "There is no update to install", null))
            return status()
        }
        if (!isNewer(release)) {
            state.set(State(Phase.ERROR, "This is already the newest version", release))
            return status()
        }
        blockedReason(release)?.let { why ->
            // Refused here rather than after a two-megabyte download that ends
            // in a dialog saying nothing.
            state.set(State(Phase.ERROR, why, release))
            return status()
        }
        if (!busy.compareAndSet(false, true)) return status()
        state.set(State(Phase.DOWNLOADING, null, release))
        runner(Runnable { download(release) })
        return status()
    }

    private fun download(release: Release) {
        try {
            downloadDir.mkdirs()
            // One file, replaced each time. A cache of every version ever
            // offered would grow without bound on a device nobody ever opens
            // the settings of.
            downloadDir.listFiles()?.forEach { it.delete() }
            // Named for what it IS. The server variant downloads a zip, and
            // a file called update.apk is a small lie that would eventually
            // cost somebody an hour.
            val target = File(
                downloadDir,
                if (variant == Variant.SERVER) "update.zip" else "update.apk"
            )

            http.newCall(Request.Builder().url(release.url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    fail("The download failed (HTTP ${response.code})", release)
                    return
                }
                val body = response.body ?: run { fail("The download was empty", release); return }
                if (body.contentLength() > MAX_BYTES) {
                    fail("That download is far too large to be this app", release)
                    return
                }
                target.outputStream().use { out -> body.byteStream().copyTo(out) }
            }

            state.set(State(Phase.VERIFYING, null, release))
            if (release.sha256 != null) {
                val actual = sha256(target)
                if (!actual.equals(release.sha256, ignoreCase = true)) {
                    target.delete()
                    fail("The download didn't match its checksum", release)
                    return
                }
            }

            // Android takes it from here: the system installer asks, and on
            // confirmation this process is replaced. If the user declines,
            // nothing happens and the phase stays where it is.
            state.set(State(Phase.AWAITING_INSTALL, null, release))
            install(target, release.version)
        } catch (e: Throwable) {
            // Throwable, not Exception: this runs on a background thread and a
            // class that fails to initialise throws an Error, which would kill
            // it silently and leave the page on "downloading" for ever.
            Log.w(TAG, "update failed", e)
            fail(e.message ?: "The update failed", release)
        } finally {
            busy.set(false)
        }
    }

    private fun fail(message: String, release: Release?) {
        state.set(State(Phase.ERROR, message, release))
    }

    // --------------------------------------------------------- what happened

    /**
     * WHY THE UPDATE BUTTON DID NOTHING, IN THE PLACE PEOPLE ARE TOLD TO LOOK.
     *
     * Reported as exactly that from a container, and there was nowhere to
     * read the answer. A failure sets [Phase.ERROR] with its reason, which the
     * bar does draw — but only while the update is still offered, and the
     * container EXITS mid-update by design, so the state is in memory and the
     * page reloads over it. What was left was `docker logs`, which is the same
     * complaint the version line at the foot of the settings menu was added to
     * fix: a fact that exists and cannot be reached.
     *
     * THE DIRECTORY IS THE LINE WORTH HAVING. The container is not root, and a
     * bind mount made by hand belongs to somebody else — so every write this
     * app does is refused while the card, the page and discovery all work
     * perfectly. Pressing Update then fails with "Permission denied" from the
     * one route that has to write, and nothing on the screen connects the two.
     * The README's chown is printed here beside the reason.
     *
     * IT IS A READ, and that is not a detail. `/api/debug` is a GET and no
     * route in this app writes to disk; probing with a temporary file would
     * break that rule for a diagnostic. [File.canWrite] is a permission check
     * and nothing else, which is why the wording is "looks writable" rather
     * than a promise.
     */
    fun diagnostics(): List<String> = diagnostics { it.canWrite() }

    /**
     * [writable] is injected for one reason: these tests run as ROOT here and
     * as somebody else in CI, and root's [File.canWrite] is true whatever the
     * mode says — so the branch that matters most could never be exercised by
     * making a directory read-only. Same seam the sources take for a socket.
     */
    internal fun diagnostics(writable: (File) -> Boolean): List<String> {
        val out = ArrayList<String>()
        val s = state.get()
        out += "running $currentVersion (${variant.name.lowercase()} build)"

        val latest = s.latest
        out += when {
            latest == null -> "nothing checked for yet this run"
            isNewer(latest) -> "${latest.version} is offered"
            else -> "${latest.version} is the newest published, so there is nothing to install"
        }

        // The phase and its reason, which is the whole answer when there is one.
        out += when (s.phase) {
            Phase.ERROR -> "LAST ATTEMPT FAILED: ${s.error ?: "no reason recorded"}"
            Phase.IDLE -> "idle"
            else -> "in progress: ${s.phase.wire}"
        }

        // Where the download has to land. Named rather than described, because
        // this line gets typed out of a screen in another room.
        val dir = downloadDir
        val existing = generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() }
        out += when {
            existing == null -> "${dir.path} does not exist and neither does anything above it"
            writable(existing) -> "${dir.path} looks writable (checked ${existing.path})"
            else -> "${existing.path} IS NOT WRITABLE by " +
                (System.getProperty("user.name") ?: "this process") +
                " — an update cannot be downloaded. In Docker: chown -R 10001 " +
                "<the directory you mounted at /data>"
        }
        return out
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun get(url: String): String? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (e: Exception) {
        Log.d(TAG, "$url failed: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "Updater"

        /** Anything bigger than this is not this app. */
        private const val MAX_BYTES = 200L * 1024 * 1024

        internal fun sameHost(a: String, b: String): Boolean {
            val ha = runCatching { URI(a).host }.getOrNull()?.lowercase() ?: return false
            val hb = runCatching { URI(b).host }.getOrNull()?.lowercase() ?: return false
            return ha.isNotEmpty() && ha == hb
        }

        /**
         * Compares dotted versions numerically: 0.1.10 is newer than 0.1.9,
         * which a string compare gets backwards — and a tenth release that
         * never offers itself is a bug nobody notices for ten releases.
         */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.trim().split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val pb = b.trim().split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = (pa.getOrNull(i) ?: 0).compareTo(pb.getOrNull(i) ?: 0)
                if (d != 0) return d
            }
            return 0
        }
    }
}
