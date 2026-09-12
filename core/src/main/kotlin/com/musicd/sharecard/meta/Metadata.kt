package com.musicd.sharecard.meta

import com.musicd.sharecard.str
import com.musicd.sharecard.strOrNull
import com.musicd.sharecard.Log
import com.musicd.sharecard.library.Normalize
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The facts the card carries that a Sonos player does not know.
 *
 * A player answers with what its source handed it — a title, an artist, an
 * album and a cover — and nothing else. No release year and no words. Both
 * come from outside, and both sources here need no API key and no account:
 *
 *   - release year, from MusicBrainz
 *   - album blurb, from Wikipedia
 *
 * Ported from MusicD Remote Lite, where this same pair feeds the same card.
 * The artist biography it also fetches is kept because the lookup is one
 * request either way, but the card never draws it: this is a card about a
 * record.
 */
class Metadata(
    private val http: OkHttpClient,
    private val userAgent: String,
    /** Where an answer is written down so the next play does not pay for it. */
    private val store: CacheStore = CacheStore.NONE
) {

    private companion object {
        const val TAG = "Meta"
        const val CACHE_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * MusicBrainz asks for at most one request per second from a single
         * client, and enforces it. Wikipedia is more relaxed but gets the same
         * courtesy.
         */
        const val MB_INTERVAL_MS = 1100L
        const val WIKI_INTERVAL_MS = 200L
    }

    /**
     * THE ONE WORTH KEEPING ACROSS A RESTART. Filling this costs five requests
     * to two outside hosts behind a rate gate — seconds, with a spinner in
     * front of somebody — and the answer for a record released in 1977 does not
     * change. A miss is written down too: "MusicBrainz has never heard of it"
     * is an answer, and re-asking for it on every play is the same seconds.
     */
    private val cache = TtlCache<String, AlbumExtras>(
        CACHE_MS, 512,
        TtlCache.Persist(store, "extras", ::encodeExtras, ::decodeExtras)
    )
    private val mbGate = RateGate(MB_INTERVAL_MS)
    private val wikiGate = RateGate(WIKI_INTERVAL_MS)

    /**
     * [image] is the article's lead thumbnail, which the artist view draws as a
     * round portrait. Absent for most album articles and plenty of artists.
     */
    data class Bio(
        val description: String,
        val source: String,
        val url: String?,
        val image: String? = null
    )

    data class AlbumExtras(val year: Int?, val album: Bio?, val artist: Bio?)

    fun extras(title: String, artist: String): AlbumExtras {
        val key = cacheKey(title, artist) ?: return AlbumExtras(null, null, null)
        return cache.get(key) {
            AlbumExtras(
                year = runCatching { musicBrainzYear(title, artist) }.getOrNull(),
                album = runCatching { wikipediaAlbum(title, artist) }.getOrNull(),
                artist = runCatching { wikipediaArtist(artist, title) }.getOrNull()
            )
        }
    }

    /**
     * What [extras] would answer without going near the network, or null when
     * this album has not been looked up recently enough to say. A name with
     * nothing in it answers empty rather than null: there is nothing to find,
     * as opposed to nothing found yet.
     *
     * The card is the only caller, and it is holding a spinner in front of
     * somebody while it waits, where [extras] is five sequential requests to
     * two outside hosts behind a rate gate. "What do we already know" is a
     * different question from "go and find out", and only the second one is
     * worth seconds — so the card draws on the cached answer and redraws if
     * the slow one turns up while it is still on screen.
     */
    fun cachedExtras(title: String, artist: String): AlbumExtras? {
        val key = cacheKey(title, artist) ?: return AlbumExtras(null, null, null)
        return cache.peek(key)
    }

    /** Null when there is not enough of a name here to key anything on. */
    private fun cacheKey(title: String, artist: String): String? =
        (Normalize.text(title) + "||" + Normalize.text(artist)).takeIf { it != "||" }

    // ---------------------------------------------------------- MusicBrainz

    /** MusicBrainz's Lucene syntax needs quotes escaped, not stripped. */
    private fun mbQuote(s: String): String = s.replace("\"", "\\\"")

    fun musicBrainzYear(title: String, artist: String): Int? {
        if (title.isBlank()) return null
        val query = buildString {
            append("release:\"").append(mbQuote(title)).append('"')
            if (artist.isNotBlank()) append(" AND artist:\"").append(mbQuote(artist)).append('"')
        }
        val url = "https://musicbrainz.org/ws/2/release/?query=" +
            urlEncode(query) + "&fmt=json&limit=5"
        val json = mbGate.run { getJson(url) } ?: return null
        val releases = json.optJSONArray("releases") ?: return null

        // The earliest dated release is the release YEAR; a later reissue is a
        // different pressing of the same record, not a different album.
        var best = Int.MAX_VALUE
        for (i in 0 until releases.length()) {
            val r = releases.optJSONObject(i) ?: continue
            // Below ~70 the match is a different record that shares a word.
            if (r.optInt("score", 0) < 70) continue
            val year = yearOf(r.str("date")) ?: continue
            if (year < best) best = year
        }
        return best.takeIf { it != Int.MAX_VALUE }
    }

    private fun yearOf(date: String?): Int? {
        if (date.isNullOrBlank()) return null
        val y = date.take(4).toIntOrNull() ?: return null
        return if (y in 1880..2100) y else null
    }

    // ------------------------------------------------------------ Wikipedia

    private fun wikiSearch(query: String, limit: Int = 5): List<String> {
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" +
            urlEncode(query) + "&srlimit=$limit&format=json"
        val json = wikiGate.run { getJson(url) } ?: return emptyList()
        val hits = json.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { hits.optJSONObject(it)?.str("title") }
            .filter { it.isNotEmpty() }
    }

    /** An article summary: the lead paragraph and, when it has one, a portrait. */
    private data class Summary(val extract: String, val image: String?)

    private fun wikiSummary(pageTitle: String): Summary? {
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/" +
            urlEncode(pageTitle.replace(' ', '_'))
        val json = wikiGate.run { getJson(url) } ?: return null
        if (json.str("type") == "disambiguation") return null
        val extract = json.str("extract").takeIf { it.length > 40 } ?: return null
        // The thumbnail is a few hundred pixels wide; originalimage can be a
        // multi-megabyte scan, which is not what a 96px avatar wants.
        val image = json.optJSONObject("thumbnail")?.strOrNull("source")
        return Summary(extract, image)
    }

    fun wikipediaAlbum(title: String, artist: String): Bio? {
        if (title.isBlank()) return null
        val candidates = wikiSearch("$title $artist album")
        for (page in candidates) {
            // The guard that stops a review being attached to the wrong record:
            // the page title must actually mention the album.
            if (!namesOverlap(page, title)) continue
            val summary = wikiSummary(page) ?: continue
            return Bio(summary.extract, "Wikipedia", "https://en.wikipedia.org/wiki/" +
                urlEncode(page.replace(' ', '_')), summary.image)
        }
        return null
    }

    /**
     * [albumTitle] is not decoration — it is what tells two acts of the same
     * name apart. The upstream UI dropped its artist bio precisely because the
     * lookup "was prone to returning wrong articles for less-famous artists",
     * and the album the user is actually looking at is the strongest available
     * disambiguator, so the search leads with it when there is one.
     */
    fun wikipediaArtist(artist: String, albumTitle: String): Bio? {
        if (artist.isBlank()) return null
        val queries = if (albumTitle.isBlank()) listOf("$artist band musician")
        else listOf("$artist $albumTitle album", "$artist band musician")
        for (query in queries) {
            for (page in wikiSearch(query)) {
                // "The Who" vs "The Guess Who" is exactly the mismatch this
                // rejects: matching on a first token alone puts a stranger's
                // biography on the page. Fail safe — drop the bio rather than
                // show the wrong one.
                if (!namesOverlap(page, artist)) continue
                val summary = wikiSummary(page) ?: continue
                return Bio(summary.extract, "Wikipedia", "https://en.wikipedia.org/wiki/" +
                    urlEncode(page.replace(' ', '_')), summary.image)
            }
        }
        return null
    }

    /**
     * Whole-phrase overlap in either direction, tolerant of a leading article.
     * "the who" vs "the guess who" -> false (correctly rejected);
     * "jay z" vs "jay z feat alicia keys" -> true (correctly kept).
     */
    fun namesOverlap(a: String, b: String): Boolean {
        val na = Normalize.sortKey(Normalize.text(a))
        val nb = Normalize.sortKey(Normalize.text(b))
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        // A wikipedia page is often "Title (album)" or "Artist (band)".
        val strippedA = na.replace(Regex("\\b(album|band|musician|singer|song)\\b"), "").trim()
        val strippedB = nb.replace(Regex("\\b(album|band|musician|singer|song)\\b"), "").trim()
        if (strippedA == strippedB) return true
        return containsWholeWords(strippedA, strippedB) || containsWholeWords(strippedB, strippedA)
    }

    /** [needle] appears in [hay] on word boundaries, never mid-word. */
    private fun containsWholeWords(hay: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        val h = hay.split(" ").filter(String::isNotEmpty)
        val n = needle.split(" ").filter(String::isNotEmpty)
        if (n.isEmpty() || n.size > h.size) return false
        for (i in 0..(h.size - n.size)) {
            if ((0 until n.size).all { h[i + it] == n[it] }) return true
        }
        return false
    }

    // ------------------------------------------------------------- plumbing

    private fun getJson(url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "$url -> ${response.code}")
                    return null
                }
                response.body?.string()?.takeIf { it.isNotEmpty() }?.let { JSONObject(it) }
            }
        } catch (e: Exception) {
            Log.d(TAG, "$url failed: ${e.message}")
            null
        }
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // --------------------------------------------------------------- on disk

    /**
     * An album's extras as one line of JSON.
     *
     * Only what the card draws is kept — the year, the album blurb and where it
     * came from. The artist Bio and the lead images are not written: nothing
     * draws them here, and a cache file is not a place to accumulate things
     * nobody reads.
     */
    private fun encodeExtras(extras: AlbumExtras): String = JSONObject()
        .put("year", extras.year ?: JSONObject.NULL)
        .put("bio", extras.album?.description ?: JSONObject.NULL)
        .put("src", extras.album?.source ?: JSONObject.NULL)
        .put("url", extras.album?.url ?: JSONObject.NULL)
        .toString()

    private fun decodeExtras(text: String): AlbumExtras? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val description = json.strOrNull("bio")
        return AlbumExtras(
            year = json.optInt("year", 0).takeIf { it > 0 },
            album = description?.let {
                Bio(it, json.str("src"), json.strOrNull("url"))
            },
            artist = null
        )
    }
}
