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
 * app with those controls publishes a session. THIS IS A PROBE, NOT A SOURCE.
 * The question it exists to settle is what those sessions actually contain on
 * one real phone — which apps publish one, what they call the record, and
 * whether there is a picture worth putting on a card. Three rounds of the Roon
 * queue were spent reasoning about a protocol nobody could reach, and the round
 * that fixed it printed what the Core really sent. Print first.
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

    data class Report(val access: Access, val sessions: List<Session> = emptyList()) {
        companion object {
            val UNSUPPORTED = Report(Access.UNSUPPORTED)
            val DENIED = Report(Access.DENIED)
        }
    }

    fun diagnostics(): List<String> =
        describe(runCatching { read() }.getOrElse { Report.UNSUPPORTED })

    internal companion object {

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

            Access.DENIED -> listOf(
                "NOTIFICATION ACCESS IS NOT GRANTED, so Android answers with an empty",
                "  list rather than an error — this is NOT the same as nothing playing.",
                "  Settings -> Apps -> Special app access -> Notification access"
            )

            Access.GRANTED -> {
                val out = ArrayList<String>()
                val sessions = report.sessions
                if (sessions.isEmpty()) {
                    // The distinction the whole type exists for, stated so that
                    // a screenshot of this line settles it on its own.
                    out += "notification access granted, and no app is holding a media session"
                    out += "  (so nothing on this phone is playing — the permission is fine)"
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
