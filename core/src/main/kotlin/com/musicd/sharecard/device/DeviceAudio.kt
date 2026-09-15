package com.musicd.sharecard.device

/**
 * WHAT IS PLAYING ON THE DEVICE ITSELF — A REPORT, AND NOTHING ELSE.
 *
 * Every source this app has is a NETWORK source: Roon is asked over a socket,
 * Lyrion over JSON-RPC, Sonos and UPnP over SOAP. Not one of them can see the
 * phone's own audio, so Spotify or Qobuz or Roon ARC playing to headphones is
 * invisible to all four — while the same app CASTING to a speaker has always
 * worked, because then a speaker knows.
 *
 * Android can answer that question and iOS cannot: `MediaSessionManager` is
 * what drives the lock screen, the Bluetooth buttons and Android Auto, so any
 * app with those controls publishes a session.
 *
 * **THIS WAS A PROBE FIRST, AND THE PROBE IS WHY THE SOURCE IS SHORT.** It was
 * shipped reporting into `/api/debug` and nothing else, to settle what those
 * sessions really contain on one phone - three rounds of the Roon queue were
 * spent reasoning about a protocol nobody here could reach, and the round that
 * fixed it printed what the Core actually sent. One photograph answered it: two
 * apps, each with a title, an artist AND an album, one PLAYING and one PAUSED,
 * one of them offering a cover this app's proxy can already fetch. So
 * [DeviceSource] turns it into a room, and it needed no new Android code at all
 * - the reading was already right, and only the deciding was missing.
 *
 * This type is still the READING. It judges nothing; it reports.
 *
 * **THE PERMISSION IS THE TRAP, AND IT IS WHY THIS TYPE EXISTS AT ALL.**
 * `getActiveSessions` needs the caller to be an enabled notification listener,
 * and without it Android does not throw — it returns an EMPTY LIST. So "no app
 * is playing" and "you never granted access" arrive as the same value, which is
 * the [com.musicd.sharecard.meta.Pitchfork] lesson (a diagnostic that cannot
 * tell two failures apart is worse than none) waiting to be repeated. [Access]
 * is carried separately from the list for exactly that reason, and the three
 * states are three different things to do about it.
 *
 * NOTHING HERE TOUCHES ANDROID. The shell reads the sessions and hands over
 * plain data — the same seam [com.musicd.sharecard.lms.LmsSource] uses for
 * discovery, "injected so a test need not open a socket" — so every line below
 * is decided in the one module that has tests.
 */
class DeviceAudio(private val read: () -> Report = { Report.UNSUPPORTED }) {

    /** Whether this build can see the device's own audio, and whether it may. */
    enum class Access {
        /** Not an Android build. A container has no media sessions at all. */
        UNSUPPORTED,

        /**
         * Android can, but this app has not been allowed to.
         *
         * THE ONE THAT LOOKS LIKE SILENCE. Android answers an empty list rather
         * than refusing, so this state must be reported in its own words or the
         * next bug report says "it finds nothing" about a permission.
         */
        DENIED,

        GRANTED
    }

    /** One app holding a media session, as Android describes it. */
    data class Session(
        /** `com.spotify.music`. The one field that is certainly present. */
        val packageName: String,
        /** Android's own label for that app, where the shell could resolve one. */
        val label: String = "",
        /** PLAYING / PAUSED / STOPPED / NONE — whatever the shell read. */
        val state: String = "",
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        /** `METADATA_KEY_ALBUM_ART_URI`, or empty. */
        val artUri: String = "",
        /** `"640x640"` where a bitmap was attached, else empty. */
        val artBitmap: String = ""
    )

    /**
     * [detail] is WHAT THE SHELL ACTUALLY SAW while deciding [access].
     *
     * Added because the first real run said NOT GRANTED to somebody who had
     * just granted it, and the report stopped exactly there — three different
     * causes wearing one sentence: the grant did not take, the grant went to
     * something else, or this app's own reading of it is wrong. The same one
     * step short as the ListenBrainz 400 and the silent Pitchfork index, in the
     * one place that cannot be tested here.
     */
    data class Report(
        val access: Access,
        val sessions: List<Session> = emptyList(),
        val detail: String = ""
    ) {
        companion object {
            val UNSUPPORTED = Report(Access.UNSUPPORTED)
            val DENIED = Report(Access.DENIED)
        }
    }

    /**
     * What the shell can see, or the honest nothing.
     *
     * THROWABLE, NOT EXCEPTION, AND THAT IS NOT DECORATION: this is now on the
     * request path as well as in the report, because [DeviceSource] answers
     * cards from it. A class that fails to initialise throws an Error, which
     * sails straight through a runCatching-for-Exception and takes the thread
     * with it — the rule this repository learned three releases in a row.
     *
     * Reading is one in-process call with no network on it, so there is nothing
     * to cache. It must not BE cached either: this is "what is playing right
     * now", and a remembered answer is exactly what Refresh exists to defeat.
     */
    fun report(): Report = runCatching { read() }.getOrElse { Report.UNSUPPORTED }

    fun diagnostics(): List<String> = describe(report())

    /*
     * THE COMPANION IS PUBLIC AND ITS MEMBERS MOSTLY ARE NOT.
     *
     * `internal companion object` was the SECOND half of the same mistake: an
     * internal companion cannot be resolved from another module at all, so
     * `DeviceAudio.listenerNote(...)` in `:app` would still not compile even
     * with the function itself made public. Caught by reading the declaration
     * rather than assuming the one-word fix was the whole of it.
     *
     * Everything in here that `:core` alone uses keeps `internal`; only what
     * the shell calls is public.
     */
    companion object {

        /** More than a screenful is not a report anybody reads off a phone. */
        const val MAX_SESSIONS = 8

        /**
         * The report, in the words somebody in another room has to act on.
         *
         * Every branch says what to DO, because this exists to be read once and
         * answered — not to be correct and inert.
         */
        internal fun describe(report: Report): List<String> = when (report.access) {
            // Said plainly rather than left out: the container and the phone
            // run the same page, and a section that silently vanishes on one of
            // them reads as a broken build rather than a different machine.
            Access.UNSUPPORTED -> listOf(
                "not an Android build, so there are no media sessions to read",
                "  (this is the container; only the app on a phone can see its own audio)"
            )

            /*
             * THE SECOND LINE IS THE ONE THAT MATTERS, AND IT COST A ROUND.
             *
             * Photographed from a real phone: the toggle was tapped and Android
             * answered "App was denied access — access to this permission can
             * put your personal and financial info at risk". That is RESTRICTED
             * SETTINGS, which since Android 13 refuses notification-listener
             * access to anything installed outside the Play Store. The grant
             * never lands, the setting never changes, and the report was
             * sending somebody back to the same screen to do the same thing.
             *
             * IT IS NOT A ONE-OFF SIDELOAD EITHER. `ApkInstaller` hands the APK
             * to `ACTION_VIEW`, the legacy install flow, so every self-update
             * this app performs is marked restricted the same way — the block
             * comes back after each one.
             *
             * A path somebody has already walked and been refused on is worse
             * than no path: it reads as the app being wrong about the state.
             */
            Access.DENIED -> listOf(
                "NOTIFICATION ACCESS IS NOT GRANTED — this is NOT the same as",
                "  nothing playing.",
                "  Settings -> Apps -> Special app access -> Notification access",
                "  IF THAT TOGGLE REFUSES (\"App was denied access\"), Android is",
                "  blocking it because this app was not installed from the Play",
                "  Store. Settings -> Apps -> this app -> the three dots at the",
                "  top -> Allow restricted settings, then grant it. It comes back",
                "  after every update, because the updater sideloads too."
            ) + detailLines(report)

            Access.GRANTED -> {
                val out = ArrayList<String>()
                val sessions = report.sessions
                if (sessions.isEmpty()) {
                    // The distinction the whole type exists for, stated so that
                    // a screenshot of this line settles it on its own.
                    out += "notification access granted, and no app is holding a media session"
                    out += "  (so nothing on this phone is playing — the permission is fine)"
                    // Carried here too: "the permission is fine" is a claim, and
                    // the evidence for it belongs beside it.
                    out += detailLines(report)
                } else {
                    out += "notification access granted, ${sessions.size} session(s)"
                    for (session in sessions.take(MAX_SESSIONS)) out += line(session)
                    if (sessions.size > MAX_SESSIONS) {
                        out += "  (${sessions.size - MAX_SESSIONS} more not listed)"
                    }
                }
                out
            }
        }

        private fun detailLines(report: Report): List<String> =
            report.detail.split("\n").filter { it.isNotBlank() }.map { "  $it" }

        /**
         * WHAT WAS LOOKED FOR, AND WHAT THE SYSTEM HOLDS — IN COUNTS, NOT NAMES.
         *
         * Three causes read as one "not granted": the grant did not take, it
         * went to a different app, or this app's reading of the setting is
         * wrong. [listed] separates the last from the first two, and [refused]
         * says whether the SYSTEM turned the call down or merely answered with
         * nothing — which is the difference between a permission problem and a
         * quiet phone.
         *
         * **NO OTHER APP IS NAMED, DELIBERATELY.** That setting is the list of
         * every app somebody has given notification access to, and this report
         * is pasted into chat windows and bug reports. A count answers the
         * question; the list would be somebody's installed software.
         */
        /*
         * PUBLIC, BECAUSE THE SHELL CALLS IT — AND `internal` IS PER MODULE.
         *
         * This shipped as `internal` and CI refused it: ":app" is a different
         * Gradle module from ":core", so an internal member is invisible there.
         * The local check list CANNOT catch that — `DeviceAudioTest` is in
         * :core's own test source set, where internal IS visible, so the test
         * passed while the app would not build. Every other helper in this
         * companion is genuinely core-only and stays internal; this one is the
         * seam, and the seam is public by definition.
         */
        fun listenerNote(
            component: String,
            listed: Boolean,
            total: Int,
            refused: Boolean
        ): String {
            val where = if (listed) {
                "this app IS one of the $total the system lists"
            } else {
                "this app is NOT among the $total the system lists"
            }
            val what = when {
                refused && listed ->
                    "the system REFUSED the call even so — the grant is there and " +
                        "something else is wrong"
                refused -> "the system refused the call"
                listed -> "and no session came back, so nothing is playing"
                else -> "and no session came back"
            }
            return "looked for $component\n$where, $what"
        }

        /** One session, as one line plus what it offers for a cover. */
        internal fun line(session: Session): String {
            val who = session.packageName +
                if (session.label.isNotBlank()) " (${session.label})" else ""
            val state = session.state.ifBlank { "no state" }
            val record = listOf(session.title, session.artist, session.album)
                .filter { it.isNotBlank() }
                .joinToString(" / ")
                .ifEmpty { "nothing described" }
            return "  $who - $state: $record - art: ${artNote(session)}"
        }

        /**
         * WHAT A CARD COULD ACTUALLY DRAW, WHICH IS THE OPEN QUESTION.
         *
         * The whole art pipeline here is a URL fetched by
         * [com.musicd.sharecard.art.ArtProxy], and a media session offers
         * neither half of that reliably: `ART_URI` is usually a `content://`
         * belonging to the OTHER app, which this process has no grant to read
         * and which no proxy can fetch, and the bitmap is already in memory
         * with no URL at all. So the three cases are three different amounts of
         * work, and which one a real phone gives is the thing worth knowing
         * before any of it is built.
         */
        internal fun artNote(session: Session): String {
            val parts = ArrayList<String>()
            val uri = session.artUri.trim()
            if (uri.isNotEmpty()) {
                parts += when {
                    // The only shape the existing proxy could fetch unchanged.
                    uri.startsWith("https://") -> "https uri, PROXYABLE AS IS ($uri)"
                    uri.startsWith("http://") -> "http uri ($uri)"
                    // Another app's private storage. Readable only with a grant
                    // this app is never given.
                    uri.startsWith("content://") -> "content uri, NOT FETCHABLE ($uri)"
                    else -> "uri ($uri)"
                }
            }
            if (session.artBitmap.isNotBlank()) {
                // No URL, so the shell would have to serve the bytes itself.
                parts += "bitmap ${session.artBitmap}, no url"
            }
            // Neither: the record's own sleeve would have to be looked up the
            // way Discover already resolves one from an artist and an album.
            return parts.joinToString(" + ").ifEmpty { "NONE" }
        }
    }
}
