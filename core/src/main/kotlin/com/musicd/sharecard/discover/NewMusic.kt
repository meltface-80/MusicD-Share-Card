package com.musicd.sharecard.discover

import com.musicd.sharecard.Log
import com.musicd.sharecard.library.Normalize
import com.musicd.sharecard.strOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * NEW RECORDS, AND THE ONES BY ACTS THIS APP HAS ACTUALLY HEARD COME FIRST.
 *
 * Asked for as "new music based on listening", drawn as COVER ART and nothing
 * else — tap a sleeve and the links open. That last part is not a style choice,
 * it is the whole legal position: a title and an artist are facts, a sleeve
 * identifies the record the same way the card already does, and everything
 * written about it stays a LINK to whoever wrote it. Nobody's prose is
 * reproduced here, and there is no route that could.
 *
 * TWO SOURCES, AND THEY ANSWER DIFFERENT QUESTIONS.
 *
 *  1. ListenBrainz's fresh-releases window. ONE request returns every release
 *     in a date range, so the filtering against [PlayHistory] happens HERE
 *     rather than as a request per act — sixty acts would otherwise be sixty
 *     rate-limited MusicBrainz browses, which is a minute of waiting for a
 *     screen. The data is MusicBrainz's and the sleeves are the Cover Art
 *     Archive's, so rendering them as our own page is not a question.
 *  2. Deezer's editorial releases, which is not personal at all. It is what
 *     fills the screen on a FIRST RUN, when the history is empty and the
 *     honest answer to "based on your listening" is "I do not know you yet".
 *
 * NEITHER ENDPOINT HAS BEEN REACHED FROM WHERE THIS WAS WRITTEN. The proxy
 * here refuses both hosts — 403 on the CONNECT, checked rather than assumed —
 * so the shapes below are the documented ones and the parsing is deliberately
 * lenient: every field is looked for in more than one place, and anything
 * missing costs that row and not the screen. The socket is kept out of the
 * parsing so that a wire which differs is one function to correct rather than
 * a protocol to re-derive, and [attempts] says what happened. Treat the first
 * real run as the verification — the same posture as Lyrion and Roon, and for
 * the same reason.
 */
class NewMusic(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val history: PlayHistory,
    /** What the press has been reviewing. Null where the feeds are not wanted. */
    private val editorial: Editorial? = null,
    /** Seams for the tests: neither wants a socket or a real calendar. */
    private val fetchText: (String) -> String? = { null },
    private val today: () -> String = { isoToday() }
) {

    /**
     * One record worth knowing about.
     *
     * [why] is what the screen prints under the sleeve, and it is the reason
     * this is not just a new-releases list: "because you played Slint" is a
     * different promise from "out this week", and a screen that cannot tell
     * you which it is has not earned the word "listening".
     */
    data class Pick(
        val artist: String,
        val album: String,
        val released: String,
        val art: String?,
        val why: String,
        val heard: Boolean,
        /**
         * The review this record came from, when it came from one.
         *
         * A LINK AND NOTHING ELSE — no headline, no excerpt, no snippet of the
         * piece. The record is identified out of the feed and the writing stays
         * with whoever wrote it. See [Editorial] for why that line is where it
         * is.
         */
        val readAt: String? = null,
        val readAtName: String? = null
    )

    // ------------------------------------------------------------ the wire

    fun picks(limit: Int = WANTED): List<Pick> {
        val heard = history.recent()
        val out = LinkedHashMap<String, Pick>()

        for (pick in fromListenBrainz(heard)) out.putIfAbsent(key(pick), pick)
        note("listenbrainz -> ${out.size} by acts you have heard")

        /*
         * THE ORDER IS THE FEATURE, AND IT RUNS FROM MOST PERSONAL TO LEAST.
         *
         * 1. New records by acts this house actually plays. Nothing displaces
         *    these — they are the only part that earns the word "listening".
         * 2. What the press has just reviewed. Not personal, but it is
         *    somebody's judgement rather than a release calendar, which is
         *    what "what's good right now" was asking for.
         * 3. Deezer's new-release list, which is the same for everybody and is
         *    there so a first run is not an empty screen.
         *
         * Each only fills what the one above it left, so a week with plenty of
         * (1) never shows (3) at all.
         */
        if (out.size < limit) {
            var added = 0
            for (pick in fromEditorial()) {
                if (out.size >= limit) break
                if (out.putIfAbsent(key(pick), pick) == null) added++
            }
            note("editorial -> $added reviewed lately")
        }

        if (out.size < limit) {
            var added = 0
            for (pick in fromDeezer()) {
                if (out.size >= limit) break
                if (out.putIfAbsent(key(pick), pick) == null) added++
            }
            note("deezer -> $added new this week")
        }
        return out.values.take(limit)
    }

    private fun fromListenBrainz(heard: List<PlayHistory.Heard>): List<Pick> {
        val url = "$LB/1/explore/fresh-releases/?release_date=${today()}" +
            "&days=$WINDOW_DAYS&past=true&future=false"
        val body = get(url) ?: run { note("listenbrainz: no answer"); return emptyList() }
        return runCatching { parseFreshReleases(body, heard) }
            .onFailure { note("listenbrainz: unreadable answer (${it.javaClass.simpleName})") }
            .getOrDefault(emptyList())
    }

    private fun fromEditorial(): List<Pick> {
        val source = editorial ?: return emptyList()
        return runCatching {
            source.recent().map {
                Pick(
                    artist = it.artist,
                    album = it.album,
                    released = "",
                    art = it.art,
                    why = "Reviewed by ${it.source}",
                    heard = false,
                    readAt = it.url,
                    readAtName = it.source
                )
            }
        }.onFailure { note("editorial: ${it.javaClass.simpleName}") }.getOrDefault(emptyList())
    }

    private fun fromDeezer(): List<Pick> {
        val body = get("$DZ/editorial/0/releases?limit=$WANTED")
            ?: run { note("deezer: no answer"); return emptyList() }
        return runCatching { parseDeezerReleases(body) }
            .onFailure { note("deezer: unreadable answer (${it.javaClass.simpleName})") }
            .getOrDefault(emptyList())
    }

    private fun get(url: String): String? {
        fetchText(url)?.let { return it }
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    note("$url -> HTTP ${response.code}")
                    null
                } else {
                    response.body?.string()
                }
            }
        } catch (e: Throwable) {
            note("$url -> no answer (${e.javaClass.simpleName})")
            null
        }
    }

    // --------------------------------------------------------- diagnostics

    /** The last few lookups, for /api/debug. An empty screen says nothing. */
    fun attempts(): List<String> = synchronized(notes) { notes.toList() }

    private val notes = ArrayList<String>()

    private fun note(line: String) {
        Log.i(TAG, line)
        synchronized(notes) {
            notes += line
            while (notes.size > MAX_NOTES) notes.removeAt(0)
        }
    }

    companion object {
        private const val TAG = "NewMusic"
        private const val MAX_NOTES = 8

        private const val LB = "https://api.listenbrainz.org"
        private const val DZ = "https://api.deezer.com"

        /** The Cover Art Archive, where a MusicBrainz release's sleeve lives. */
        private const val CAA = "https://archive.org/download"

        /** How many sleeves the screen holds. Three columns, four rows. */
        const val WANTED = 12

        /**
         * How far back "new" reaches.
         *
         * Long enough that a household which opens this once a fortnight still
         * sees something, short enough that it is not a back catalogue. The
         * window is asked for in one request either way, so the length costs
         * nothing.
         */
        const val WINDOW_DAYS = 21

        private fun key(p: Pick) =
            Normalize.text(p.artist) + "|" + Normalize.text(p.album)

        internal fun isoToday(): String = java.time.LocalDate.now().toString()

        /**
         * ListenBrainz's fresh-releases payload, filtered to acts in [heard].
         *
         * THE FILTER IS THE FEATURE. The endpoint answers with every release in
         * the window — thousands — and what makes this "based on your
         * listening" is keeping the ones whose act this app has drawn a card
         * for. [Normalize.namesOverlap] is the same rule every other name check
         * here uses, so "Bjork" matches "Björk" and a stranger does not.
         *
         * LENIENT ON EVERY FIELD, because none of this has been seen on the
         * wire: the rows are looked for under `payload.releases` and then at
         * the root, and each name is looked for under more than one key. A row
         * missing what it needs is dropped; it never takes the screen with it.
         */
        internal fun parseFreshReleases(body: String, heard: List<PlayHistory.Heard>): List<Pick> {
            val root = JSONObject(body)
            val rows = root.optJSONObject("payload")?.optJSONArray("releases")
                ?: root.optJSONArray("releases")
                ?: JSONArray()
            val wanted = heard.filter { it.artist.isNotBlank() }
            val out = ArrayList<Pick>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val artist = row.strOrNull("artist_credit_name")
                    ?: row.strOrNull("artist_name") ?: continue
                val album = row.strOrNull("release_name")
                    ?: row.strOrNull("title") ?: continue
                if (artist.isBlank() || album.isBlank()) continue
                val match = wanted.firstOrNull { Normalize.namesOverlap(it.artist, artist) }
                    ?: continue
                out += Pick(
                    artist = artist,
                    album = album,
                    released = row.strOrNull("release_date").orEmpty(),
                    art = coverArtUrl(row),
                    why = "Because you played ${match.artist}",
                    heard = true
                )
            }
            return out
        }

        /**
         * The sleeve, from the Cover Art Archive.
         *
         * `caa_id` names the image and `caa_release_mbid` names the release it
         * belongs to, and BOTH are needed — the id alone builds a URL that
         * 404s. A release with no art is kept WITHOUT a sleeve rather than
         * dropped: the screen draws a placeholder, and a record worth knowing
         * about does not stop being one because nobody has uploaded the cover.
         */
        internal fun coverArtUrl(row: JSONObject): String? {
            val mbid = row.strOrNull("caa_release_mbid") ?: return null
            val id = row.opt("caa_id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                ?: return null
            // Plain letters, digits and dashes only: both halves go straight
            // into a path, and a slash or a dot walking out of it is the same
            // hazard the Lyrion cover id has a rule about.
            if (!SAFE.matches(mbid) || !SAFE.matches(id)) return null
            return "$CAA/mbid-$mbid/mbid-$mbid-${id}_thumb500.jpg"
        }

        private val SAFE = Regex("[A-Za-z0-9-]{1,64}")

        /**
         * Deezer's editorial releases — new this week, the same for everybody.
         *
         * `cover_xl` down to `cover` because the bigger ones are not always
         * there, and a sleeve at any size beats a blank tile.
         */
        internal fun parseDeezerReleases(body: String): List<Pick> {
            val rows = JSONObject(body).optJSONArray("data") ?: JSONArray()
            val out = ArrayList<Pick>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val album = row.strOrNull("title") ?: continue
                val artist = row.optJSONObject("artist")?.strOrNull("name") ?: continue
                if (artist.isBlank() || album.isBlank()) continue
                out += Pick(
                    artist = artist,
                    album = album,
                    released = row.strOrNull("release_date").orEmpty(),
                    art = row.strOrNull("cover_xl")
                        ?: row.strOrNull("cover_big")
                        ?: row.strOrNull("cover_medium")
                        ?: row.strOrNull("cover"),
                    why = "New this week",
                    heard = false
                )
            }
            return out
        }
    }
}
