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

    /**
     * [artistMbid] is MusicBrainz's id for the act, and it is FREE: the release
     * search that finds the year already answers with the artist credited on
     * every match, and this used to read the date and throw the rest away. It
     * is what the similar-artist lookup is keyed on, so having it here is the
     * difference between that costing nothing and costing a search of its own.
     */
    data class AlbumExtras(
        val year: Int?,
        val album: Bio?,
        val artist: Bio?,
        val artistMbid: String? = null
    )

    fun extras(title: String, artist: String): AlbumExtras {
        val key = cacheKey(title, artist) ?: return AlbumExtras(null, null, null)
        return cache.get(key) {
            val release = runCatching { musicBrainzRelease(title, artist) }
                .getOrNull() ?: Release(null, null)
            AlbumExtras(
                year = release.year,
                album = runCatching { wikipediaAlbum(title, artist) }.getOrNull(),
                artist = runCatching { wikipediaArtist(artist, title) }.getOrNull(),
                artistMbid = release.artistMbid
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

    /** What one MusicBrainz release search is worth, which is two things. */
    data class Release(val year: Int?, val artistMbid: String?)

    fun musicBrainzYear(title: String, artist: String): Int? =
        musicBrainzRelease(title, artist).year

    fun musicBrainzRelease(title: String, artist: String): Release {
        if (title.isBlank()) return Release(null, null)
        val query = buildString {
            append("release:\"").append(mbQuote(title)).append('"')
            if (artist.isNotBlank()) append(" AND artist:\"").append(mbQuote(artist)).append('"')
        }
        val url = "https://musicbrainz.org/ws/2/release/?query=" +
            urlEncode(query) + "&fmt=json&limit=5"
        val json = mbGate.run { getJson(url) } ?: return Release(null, null)
        return readReleases(json, artist)
    }

    /**
     * Split out so it can be tested without MusicBrainz being up — the shape
     * of this response is the part that can quietly change, not the fetch.
     */
    internal fun readReleases(json: JSONObject, artist: String): Release {
        val releases = json.optJSONArray("releases") ?: return Release(null, null)

        // The earliest dated release is the release YEAR; a later reissue is a
        // different pressing of the same record, not a different album.
        var best = Int.MAX_VALUE
        var mbid: String? = null
        for (i in 0 until releases.length()) {
            val r = releases.optJSONObject(i) ?: continue
            // Below ~70 the match is a different record that shares a word.
            if (r.optInt("score", 0) < 70) continue
            val year = yearOf(r.str("date"))
            if (year != null && year < best) best = year
            // The FIRST credited act on the best-scoring match whose name we
            // recognise. Taking it off any match would hand back the featured
            // guest on a compilation, and taking it without the name check
            // would hand back whoever MusicBrainz scored highest for a title
            // two records share.
            if (mbid == null) mbid = artistMbidOf(r, artist)
        }
        return Release(best.takeIf { it != Int.MAX_VALUE }, mbid)
    }

    /**
     * The credited artist's MusicBrainz id, but only when it is the artist the
     * speaker named. A wrong id here is worse than none: it seeds a row of
     * "similar artists" for somebody else entirely, and nothing downstream
     * could tell.
     */
    private fun artistMbidOf(release: JSONObject, artist: String): String? {
        val credits = release.optJSONArray("artist-credit") ?: return null
        for (i in 0 until credits.length()) {
            val act = credits.optJSONObject(i)?.optJSONObject("artist") ?: continue
            val id = act.strOrNull("id") ?: continue
            if (artist.isBlank() || Normalize.namesOverlap(act.str("name"), artist)) return id
        }
        return null
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
            // Cheap half of [albumArticleFits], applied first only so a page
            // that cannot be the record costs no summary request.
            if (!Normalize.namesOverlap(page, title)) continue
            val summary = wikiSummary(page) ?: continue
            if (!albumArticleFits(page, summary.extract, title, artist)) continue
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
                if (!Normalize.namesOverlap(page, artist)) continue
                val summary = wikiSummary(page) ?: continue
                return Bio(summary.extract, "Wikipedia", "https://en.wikipedia.org/wiki/" +
                    urlEncode(page.replace(' ', '_')), summary.image)
            }
        }
        return null
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
        .put("amb", extras.artistMbid ?: JSONObject.NULL)
        .toString()

    private fun decodeExtras(text: String): AlbumExtras? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val description = json.strOrNull("bio")
        return AlbumExtras(
            year = json.optInt("year", 0).takeIf { it > 0 },
            album = description?.let {
                Bio(it, json.str("src"), json.strOrNull("url"))
            },
            artist = null,
            // Absent from every entry written before the similar-artist
            // lookup existed, which decodes to null and costs one search to
            // fill again. A shelf that fails to load is the one thing a cache
            // may never do.
            artistMbid = json.strOrNull("amb")
        )
    }
}

/**
 * Is this Wikipedia article about THIS record, by THIS act.
 *
 * THE PAGE TITLE ALONE WAS THE WHOLE GUARD, AND THE PAGE TITLE IS THE HALF
 * THAT CANNOT ANSWER THE QUESTION. "Cult" by To/Die/For drew the blurb for
 * "Cult of Static" — a Static-X record — because [Normalize.namesOverlap]
 * anchors at the front and deliberately accepts a name qualified on the
 * RIGHT, which is the same rule that makes "Spiderland" match "Spiderland
 * (Slint album)" and is wanted there. The artist was used to build the search
 * QUERY and then never checked against the answer, so Wikipedia's own ranking
 * was the only thing deciding, and it put a stranger's record on the card in
 * confident prose. Reported from the field as the wrong blurb.
 *
 * An album page rarely names the artist in its TITLE and almost always does
 * in its first sentence — "…is the sixth studio album by American industrial
 * metal band Static-X" — so the extract is what gets read, with the title
 * taken as well for the "(To/Die/For album)" shape. A spelling the article
 * does not carry costs the blurb, which is this app's standing trade: a
 * missing blurb is honest and a confident wrong one is not.
 *
 * What it CANNOT do is tell "Eagles" from "Eagles of Death Metal" — the
 * article for either names a run of words the other is inside — and
 * [AlbumArticleTest] asserts that limit rather than pretending otherwise.
 */
internal fun albumArticleFits(
    page: String,
    extract: String,
    title: String,
    artist: String
): Boolean {
    if (!Normalize.namesOverlap(page, title)) return false
    // The speaker named no artist, so there is nothing to disagree with. The
    // title match is all this can ever be, exactly as it was before.
    if (artist.isBlank()) return true
    return Normalize.mentions(extract, artist) || Normalize.mentions(page, artist)
}
