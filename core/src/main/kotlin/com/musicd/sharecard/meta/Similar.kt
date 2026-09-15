package com.musicd.sharecard.meta

import com.musicd.sharecard.Log
import com.musicd.sharecard.library.Normalize
import com.musicd.sharecard.str
import com.musicd.sharecard.strOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Acts worth hearing next, given the one that is playing.
 *
 * ARTISTS, NOT ALBUMS, AND THE NAME OF THIS FEATURE IS A COMPROMISE. Nothing
 * keyless does album-to-album similarity. Every route available without a
 * developer account answers "artists like this artist", so what this finds is
 * a handful of acts and then ONE record by each, and the row says "If you like
 * this" rather than promising a recommendation engine.
 *
 * NOTHING HERE IS ON THE CARD. The card is the whole message and what it says
 * is what is playing; a suggestion is the page's business. See CLAUDE.md.
 *
 * TWO SOURCES, IN THIS ORDER, AND THE SECOND IS WHY THE FIRST IS ALLOWED TO BE
 * FRAGILE:
 *
 *  1. ListenBrainz, keyed on the MusicBrainz artist id the metadata lookup
 *     already has for free. It answers in MBIDs, so naming a record by each
 *     act is a MusicBrainz browse and stays inside one vocabulary.
 *  2. Deezer's public API, keyed on the artist's NAME. Two requests to get
 *     started where ListenBrainz needs none, but the endpoints are plain and
 *     long-lived.
 *
 * THE ALGORITHM STRING BELOW IS NOT VERIFIED. ListenBrainz's similarity
 * endpoint takes the name of the dataset that produced it, those names carry
 * their parameters, and they change. It could not be checked from the machine
 * this was written on — the network there refuses both hosts — so it is
 * treated as a guess: any answer that is not a usable list falls through to
 * Deezer, and [attempts] records which one actually answered. The first real
 * run says which, instead of an empty row saying nothing.
 */
class Similar(
    private val http: OkHttpClient,
    private val userAgent: String,
    /** Seams for the tests, which need both hosts answerable. Nothing passes them. */
    private val listenBrainz: String = LISTENBRAINZ,
    private val musicBrainz: String = MUSICBRAINZ,
    private val deezer: String = DEEZER,
    /** Where an answer is written down so the next play does not pay for it. */
    private val store: CacheStore = CacheStore.NONE
) {

    /**
     * One suggestion. [album] and [year] are best-effort: an act whose records
     * could not be named is still worth showing, so the row degrades to names
     * rather than disappearing.
     */
    data class Act(
        val name: String,
        val mbid: String?,
        val album: String?,
        val year: Int?
    )

    /**
     * Cached INCLUDING the empty answer. A record with no similar acts costs
     * the same requests to find that out as one with plenty, and an artist
     * nobody has listened to next to anything else is a permanent no.
     */
    private val cache = TtlCache<String, List<Act>>(
        TTL_MS, 128,
        TtlCache.Persist(store, "similar", ::encode, ::decode)
    )

    private val lbGate = RateGate(LB_INTERVAL_MS)
    private val mbGate = RateGate(MB_INTERVAL_MS)
    private val dzGate = RateGate(DZ_INTERVAL_MS)

    /**
     * Up to [WANTED] acts like [artist], or empty.
     *
     * [mbid] is MusicBrainz's id for that artist when the metadata lookup
     * found one. Without it the ListenBrainz path is skipped entirely rather
     * than guessed at — an id resolved by searching a name is how a row of
     * suggestions ends up being about a different act with the same name.
     */
    fun forArtist(artist: String, mbid: String?): List<Act> {
        if (artist.isBlank()) return emptyList()
        return cache.get(key(artist)) {
            val fromListenBrainz = if (mbid.isNullOrBlank()) {
                note("$artist -> no MusicBrainz id, so ListenBrainz was not asked")
                emptyList()
            } else {
                runCatching { viaListenBrainz(mbid) }.getOrElse { emptyList() }
            }
            fromListenBrainz.ifEmpty {
                runCatching { viaDeezer(artist) }.getOrElse { emptyList() }
            }
        }
    }

    /** What [forArtist] would answer without going near the network. */
    fun cachedForArtist(artist: String): List<Act>? =
        if (artist.isBlank()) emptyList() else cache.peek(key(artist))

    // ------------------------------------------------------------ ListenBrainz

    private fun viaListenBrainz(mbid: String): List<Act> {
        val url = "$listenBrainz/similar-artists/json?artist_mbids=" + urlEncode(mbid) +
            "&algorithm=" + urlEncode(ALGORITHM)
        val answer = lbGate.run { fetch(url) }
        if (!answer.ok) {
            // THE STATUS, NOT JUST "no answer". This has never once answered in
            // the field, and "no answer" cannot tell a rejected dataset name
            // (400) from a moved endpoint (404) from a host that is simply not
            // reachable (0) — which are three different fixes.
            //
            // AND THE STATUS ALONE STOPPED ONE WORD SHORT. 400 came back from a
            // real network and says a parameter was refused without saying
            // which; the reason is in the body, so the body is in the note.
            note(
                "listenbrainz($mbid) -> HTTP ${answer.status}" + reason(answer.body) +
                    ", falling through to Deezer"
            )
            return emptyList()
        }
        val acts = readListenBrainz(answer.body.orEmpty())
        if (acts.isEmpty()) {
            note("listenbrainz($mbid) -> answered, NOTHING USABLE IN IT, falling through")
            return emptyList()
        }
        note("listenbrainz($mbid) -> ${acts.size} acts")
        return acts.map { it.copy(album = null, year = null) }.map(::withAlbum)
    }

    /**
     * ListenBrainz answers with a bare array of objects, and has answered with
     * an array whose first element is the query echoed back. Both are walked
     * the same way: anything carrying a name and an id that is not the artist
     * asked about is a result, and anything else is skipped.
     */
    internal fun readListenBrainz(body: String): List<Act> {
        val root = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<String, Act>()
        walk(root) { obj ->
            val id = obj.strOrNull("artist_mbid") ?: return@walk
            val name = obj.strOrNull("name") ?: obj.strOrNull("artist_name") ?: return@walk
            if (name.isBlank()) return@walk
            if (out.size < WANTED) out.putIfAbsent(id, Act(name, id, null, null))
        }
        return out.values.toList()
    }

    /** Depth-first over arrays of arrays, which is the shape that has changed. */
    private fun walk(array: JSONArray, each: (JSONObject) -> Unit) {
        for (i in 0 until array.length()) {
            when (val item = array.opt(i)) {
                is JSONObject -> each(item)
                is JSONArray -> walk(item, each)
                else -> {}
            }
        }
    }

    /**
     * One record by [act], from MusicBrainz's release groups for it.
     *
     * The EARLIEST studio album, which is a choice and not the only one: it is
     * usually the one people mean when they name an act, and "most popular"
     * is not something MusicBrainz knows. An act whose groups cannot be read
     * keeps its name and loses its record.
     */
    private fun withAlbum(act: Act): Act {
        val id = act.mbid ?: return act
        val url = "$musicBrainz/ws/2/release-group?artist=" + urlEncode(id) +
            "&type=album&fmt=json&limit=100"
        val json = mbGate.run { getJson(url) } ?: return act
        val groups = json.optJSONArray("release-groups") ?: return act
        var bestTitle: String? = null
        var bestYear = Int.MAX_VALUE
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            // ASKED FOR AND CHECKED. `type=album` narrows the browse, but a
            // filter the server applies is not evidence the server applied it,
            // and the cost of it being ignored is a single in the row — the
            // exact fault Deezer shipped. Check the answer.
            if (!g.str("primary-type").equals("Album", ignoreCase = true)) continue
            // A compilation or a live record carries "Album" as its primary
            // type with the rest in secondary-types; those are not the record
            // to name somebody's first listen after.
            if ((g.optJSONArray("secondary-types")?.length() ?: 0) > 0) continue
            val title = g.strOrNull("title")?.takeIf { it.isNotBlank() } ?: continue
            val year = yearOf(g.str("first-release-date")) ?: continue
            if (year < bestYear) {
                bestYear = year
                bestTitle = title
            }
        }
        return if (bestTitle == null) act else act.copy(album = bestTitle, year = bestYear)
    }

    // ------------------------------------------------------------------ Deezer

    /**
     * NOT THE TOP HIT. THE RIGHT ONE.
     *
     * This asked for `limit=1` and used whatever came back. Deezer answers a
     * name with every act that carries it, and plenty of famous names are also
     * carried by somebody with four followers and no related artists — so the
     * search "succeeded", the name check passed, and the related lookup came
     * back empty. Reported from the field as Sting getting no suggestions while
     * The Police, Calexico and The Sea Within all got three.
     *
     * It is the same lesson as [QobuzAlbum.pick] and the Pitchfork listing: a
     * search that returns SOMETHING is not evidence it returned the thing you
     * asked for, and the first row is not the answer. So: take a page of
     * candidates, keep the ones actually carrying this name, and try them in
     * order of how many people follow them — which is what separates Sting from
     * somebody who named themselves after him.
     *
     * A candidate with no related acts is not the end either. The next one is
     * tried, up to [CANDIDATES], because an empty answer from the wrong Sting
     * says nothing about the right one.
     */
    private fun viaDeezer(artist: String): List<Act> {
        val search = "$deezer/search/artist?limit=$SEARCH_ROWS&q=" + urlEncode(artist)
        val candidates = readDeezerArtists(dzGate.run { getJson(search) }, artist)
        if (candidates.isEmpty()) {
            note("deezer($artist) -> nobody on Deezer carries that name")
            return emptyList()
        }

        for (candidate in candidates.take(CANDIDATES)) {
            val related = dzGate.run {
                getJson("$deezer/artist/${candidate.id}/related?limit=$WANTED")
            }
            val acts = readDeezerRelated(related)
            if (acts.isEmpty()) continue
            note("deezer($artist) -> ${candidate.name} (id ${candidate.id}," +
                " ${candidate.fans} fans) -> ${acts.size} acts")
            return acts.map { deezerAlbum(it.first, it.second) }
        }
        note("deezer($artist) -> ${candidates.size} candidate(s), none with related acts")
        return emptyList()
    }

    /** One Deezer artist worth trying. [fans] is the tie-break, not the filter. */
    internal data class Candidate(val id: String, val name: String, val fans: Int)

    /**
     * The artists in a Deezer search response that really carry [artist]'s
     * name, most-followed first.
     *
     * `nb_fan` is what tells two acts of the same name apart, and it is a
     * RANKING rather than a threshold: a small artist with the name to
     * themselves must still be found. Sorting is stable, so where Deezer
     * reports no follower count at all the order it chose is kept.
     */
    internal fun readDeezerArtists(json: JSONObject?, artist: String): List<Candidate> {
        val data = json?.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (i in 0 until data.length()) {
            val a = data.optJSONObject(i) ?: continue
            val id = a.strOrNull("id") ?: idOf(a) ?: continue
            val name = a.strOrNull("name")?.takeIf { it.isNotBlank() } ?: continue
            // The same guard as before, applied to every row rather than only
            // the first: a search that returns SOMETHING is not evidence it
            // returned this act.
            if (!Normalize.namesOverlap(name, artist)) continue
            out += Candidate(id, name, a.optInt("nb_fan", 0))
        }
        return out.sortedByDescending { it.fans }
    }

    /** Related acts as (deezer id, name), in Deezer's own order. */
    internal fun readDeezerRelated(json: JSONObject?): List<Pair<String, String>> {
        val data = json?.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until data.length()) {
            if (out.size >= WANTED) break
            val a = data.optJSONObject(i) ?: continue
            val id = a.strOrNull("id") ?: idOf(a) ?: continue
            val name = a.strOrNull("name")?.takeIf { it.isNotBlank() } ?: continue
            out += id to name
        }
        return out
    }

    /** Deezer writes an id as a number; org.json will not hand that back as a string. */
    private fun idOf(obj: JSONObject): String? =
        obj.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }

    /**
     * One ALBUM by this act, and nothing else.
     *
     * `/artist/{id}/albums` is named for albums and is not one: it returns
     * every release Deezer files under the act, singles and EPs included, and
     * this took the earliest of them. For a house act that is almost always a
     * twelve-inch, which is how the row came to suggest "Gat Decor · Passion"
     * and "Hyper Go Go · High" — both singles, reported from the field as
     * "some are just tracks".
     *
     * `record_type` is the field that separates them, and the filter is a
     * WHITELIST rather than a list of things to skip: album, and that is all.
     * A compilation is not a record to start somebody on, and an unfamiliar
     * value a year from now should be excluded by default rather than
     * suggested by default.
     *
     * An act with no album to its name keeps the name and loses the record —
     * see [readDeezerAlbums]. Naming a single would be the wrong answer; the
     * act on its own is an honest one.
     */
    private fun deezerAlbum(id: String, name: String): Act {
        val json = dzGate.run { getJson("$deezer/artist/$id/albums?limit=50") }
        val (title, year) = readDeezerAlbums(json)
        return Act(name, null, title, year)
    }

    /** Earliest full album in a Deezer artist-albums response, or nothing. */
    internal fun readDeezerAlbums(json: JSONObject?): Pair<String?, Int?> {
        val data = json?.optJSONArray("data") ?: return null to null
        var bestTitle: String? = null
        var bestYear = Int.MAX_VALUE
        for (i in 0 until data.length()) {
            val album = data.optJSONObject(i) ?: continue
            if (!album.str("record_type").equals("album", ignoreCase = true)) continue
            val title = album.strOrNull("title")?.takeIf { it.isNotBlank() } ?: continue
            val year = yearOf(album.str("release_date")) ?: continue
            if (year < bestYear) {
                bestYear = year
                bestTitle = title
            }
        }
        return bestTitle to bestYear.takeIf { it != Int.MAX_VALUE }
    }

    // ----------------------------------------------------------- diagnostics

    /**
     * The last few lookups, for /api/debug.
     *
     * An empty row is silent, and it has three quite different causes: no
     * MusicBrainz id to ask with, ListenBrainz answering something this cannot
     * read, and Deezer genuinely not knowing the act. They look identical from
     * the page — which is exactly the mistake the Pitchfork note made once.
     */
    fun attempts(): List<String> = synchronized(notes) { notes.toList() }

    private fun note(line: String) {
        synchronized(notes) {
            notes += line
            while (notes.size > MAX_NOTES) notes.removeAt(0)
        }
    }

    private val notes = ArrayList<String>()

    // ------------------------------------------------------------- plumbing

    private fun key(artist: String): String = Normalize.text(artist)

    private fun yearOf(date: String?): Int? {
        if (date.isNullOrBlank()) return null
        val y = date.take(4).toIntOrNull() ?: return null
        return if (y in 1880..2100) y else null
    }

    private fun getJson(url: String): JSONObject? =
        text(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /**
     * The body of a GOOD answer, or null.
     *
     * Gated on [Answer.ok] rather than on the body being null, now that a
     * refusal carries one: MusicBrainz's 404 page handed to a JSON parser is
     * not an answer, and must not read as one.
     */
    private fun text(url: String): String? = fetch(url).let { if (it.ok) it.body else null }

    /** The status and the body, so a diagnostic can say which failure it was. */
    /**
     * One HTTP answer, INCLUDING THE BODY OF A REFUSAL.
     *
     * [ok] is what separates an answer from a refusal — never the body being
     * null, which is what this used to mean and what cost the reason for a
     * 400 that a real network finally produced.
     */
    private data class Answer(val status: Int, val body: String?, val ok: Boolean)

    private fun fetch(url: String): Answer {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "$url -> ${response.code}")
                    /*
                     * THE BODY OF A REFUSAL IS THE THING THAT SAYS WHY, AND IT
                     * WAS BEING THROWN AWAY. ListenBrainz answered 400 in the
                     * field — the status alone says "a parameter was rejected"
                     * and stops exactly one word short of WHICH, which is the
                     * difference between a fix and another guess at a dataset
                     * name nobody here can reach. Same lesson as the Roon
                     * queue carrying `reply.toString()` rather than this app's
                     * reading of it.
                     *
                     * PEEKED AND BOUNDED, because an error page is not
                     * necessarily small and this runs on a phone.
                     */
                    val why = runCatching {
                        response.peekBody(MAX_ERROR_BYTES).string()
                    }.getOrNull()
                    Answer(response.code, why, ok = false)
                } else {
                    Answer(response.code, response.body?.string(), ok = true)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "$url failed: ${e.message}")
            // 0 is not a status. It is "the request never got an answer", which
            // is a different thing from one that came back refused.
            Answer(0, null, ok = false)
        }
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun encode(acts: List<Act>): String = JSONArray(
        acts.map {
            JSONObject()
                .put("n", it.name)
                .put("m", it.mbid ?: JSONObject.NULL)
                .put("a", it.album ?: JSONObject.NULL)
                .put("y", it.year ?: JSONObject.NULL)
        }
    ).toString()

    private fun decode(text: String): List<Act>? {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return null
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.strOrNull("n") ?: return@mapNotNull null
            Act(name, o.strOrNull("m"), o.strOrNull("a"), o.optInt("y", 0).takeIf { it > 0 })
        }
    }

    companion object {
        private const val TAG = "Similar"

        const val LISTENBRAINZ = "https://labs.api.listenbrainz.org"
        const val MUSICBRAINZ = "https://musicbrainz.org"
        const val DEEZER = "https://api.deezer.com"

        /**
         * How many acts the row shows.
         *
         * Three, chosen by the owner over five. It is also what keeps the page
         * to one screen: each row is a full-width chip, so two fewer is about
         * seventy pixels back.
         */
        const val WANTED = 3

        /**
         * UNVERIFIED — see the class comment. ListenBrainz names its
         * similarity datasets after the parameters that produced them, and the
         * names change with the dataset. A wrong one is a 400, which is why
         * Deezer is behind it rather than beside it.
         */
        const val ALGORITHM =
            "session_based_days_7500_session_300_contribution_5_threshold_15_limit_50_skip_30"

        /**
         * How much of a refused answer's body to read.
         *
         * Bounded because an error page is not necessarily small and this runs
         * on a phone — and PEEKED, so a refusal never pulls a whole document
         * into memory to print one sentence of it.
         */
        const val MAX_ERROR_BYTES = 2048L

        /** As much of it as fits a diagnostics line read off a phone screen. */
        const val MAX_REASON = 200

        /**
         * A refusal's body, flattened to one line for the diagnostics.
         *
         * NO REGEX, DELIBERATELY. A `Regex` in a companion object is a static
         * initialiser, and Android's ICU engine is stricter than this JVM's —
         * that has taken this app down three times. A loop cannot fail to
         * compile on a device that this JVM accepted.
         *
         * Empty in, empty out, so a refusal with no body reads exactly as it
         * did before any of this: "HTTP 404, falling through to Deezer".
         */
        internal fun reason(body: String?): String {
            val flat = StringBuilder()
            var pending = false
            for (c in body.orEmpty()) {
                if (c.isWhitespace()) {
                    pending = flat.isNotEmpty()
                } else {
                    if (pending) flat.append(' ')
                    pending = false
                    flat.append(c)
                }
            }
            val text = flat.toString()
            if (text.isEmpty()) return ""
            val cut =
                if (text.length > MAX_REASON) text.take(MAX_REASON).trimEnd() + "\u2026" else text
            return " ($cut)"
        }

        /** A week. Who sounds like whom does not change by Tuesday. */
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        const val LB_INTERVAL_MS = 250L

        /** MusicBrainz asks for one request a second and enforces it. */
        const val MB_INTERVAL_MS = 1100L
        const val DZ_INTERVAL_MS = 250L

        /** How many search rows to consider before giving up on a name. */
        const val SEARCH_ROWS = 10

        /**
         * How many of those to actually ask for related artists. Each one is a
         * request, and by the third the name is either shared by a crowd or
         * Deezer has nothing filed for any of them.
         */
        const val CANDIDATES = 3

        private const val MAX_NOTES = 12
    }
}
