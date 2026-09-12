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
        val body = lbGate.run { text(url) }
        if (body == null) {
            note("listenbrainz($mbid) -> no answer, falling through to Deezer")
            return emptyList()
        }
        val acts = readListenBrainz(body)
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

    private fun viaDeezer(artist: String): List<Act> {
        val search = "$deezer/search/artist?limit=1&q=" + urlEncode(artist)
        val found = dzGate.run { getJson(search) }
            ?.optJSONArray("data")?.optJSONObject(0)
        val id = found?.let { it.strOrNull("id") ?: idOf(it) }
        if (id == null) {
            note("deezer($artist) -> Deezer has never heard of that artist")
            return emptyList()
        }
        // The name check is the same guard the Pitchfork lookup needs: a search
        // that returns SOMETHING is not evidence it returned this act.
        if (!Normalize.namesOverlap(found.str("name"), artist)) {
            note("deezer($artist) -> top hit is ${found.str("name")}, not this artist")
            return emptyList()
        }
        val related = dzGate.run { getJson("$deezer/artist/$id/related?limit=$WANTED") }
        val acts = readDeezerRelated(related)
        note("deezer($artist) -> ${acts.size} acts")
        return acts.map { deezerAlbum(it.first, it.second) }
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

    private fun deezerAlbum(id: String, name: String): Act {
        val json = dzGate.run { getJson("$deezer/artist/$id/albums?limit=50") }
        val data = json?.optJSONArray("data") ?: return Act(name, null, null, null)
        var bestTitle: String? = null
        var bestYear = Int.MAX_VALUE
        for (i in 0 until data.length()) {
            val album = data.optJSONObject(i) ?: continue
            val title = album.strOrNull("title")?.takeIf { it.isNotBlank() } ?: continue
            val year = yearOf(album.str("release_date")) ?: continue
            if (year < bestYear) {
                bestYear = year
                bestTitle = title
            }
        }
        return Act(name, null, bestTitle, bestYear.takeIf { it != Int.MAX_VALUE })
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

    private fun text(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "$url -> ${response.code}")
                    null
                } else {
                    response.body?.string()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "$url failed: ${e.message}")
            null
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

        /** How many acts the row shows. Five chips is one phone width. */
        const val WANTED = 5

        /**
         * UNVERIFIED — see the class comment. ListenBrainz names its
         * similarity datasets after the parameters that produced them, and the
         * names change with the dataset. A wrong one is a 400, which is why
         * Deezer is behind it rather than beside it.
         */
        const val ALGORITHM =
            "session_based_days_7500_session_300_contribution_5_threshold_15_limit_50_skip_30"

        /** A week. Who sounds like whom does not change by Tuesday. */
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        const val LB_INTERVAL_MS = 250L

        /** MusicBrainz asks for one request a second and enforces it. */
        const val MB_INTERVAL_MS = 1100L
        const val DZ_INTERVAL_MS = 250L

        private const val MAX_NOTES = 12
    }
}
