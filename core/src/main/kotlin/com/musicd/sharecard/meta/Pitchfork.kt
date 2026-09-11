package com.musicd.sharecard.meta

import com.musicd.sharecard.Log
import com.musicd.sharecard.library.Normalize
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One album's Pitchfork score.
 *
 * This carries no review text and never will: the written review is
 * Pitchfork's, and what the card draws is the number and the Best New Music
 * flag. That is the same line MusicD Remote Lite draws, and it is deliberate —
 * a share card that reproduced somebody's review would be republishing it.
 *
 * Trimmed from that app's version to the single lookup a card needs. The
 * listing pages, the RSS feed and the search over them are all about browsing
 * reviews, and there is no browsing here.
 */
class Pitchfork(private val http: OkHttpClient, private val userAgent: String) {

    data class Review(
        val url: String,
        val score: Double?,
        val isBestNewMusic: Boolean
    )

    private val gate = RateGate(INTERVAL_MS)

    /**
     * One album's score, cached — INCLUDING the absence of one.
     *
     * A miss is the common case and costs a whole page download just the same,
     * so it is stored as an empty list rather than left to be asked again. A
     * list rather than a nullable because [TtlCache] cannot tell a cached null
     * from an absent key.
     */
    private val cache = TtlCache<String, List<Review>>(REVIEW_TTL_MS, 256)

    /**
     * The score for one album, from its own review page.
     *
     * Pitchfork's review URLs are built from slugs
     * (`/reviews/albums/<artist>-<album>/`), so one can be constructed rather
     * than searched for — which is the only way an album from 1994 is found at
     * all, since the listing endpoints carry only recent reviews.
     *
     * A constructed URL can land on a real page for the WRONG record when two
     * artists share an album title, so the artist named in the URL has to agree
     * with the one asked about before anything is returned.
     */
    fun reviewFor(title: String, artist: String): Review? {
        if (title.isBlank() || artist.isBlank()) return null
        val artistSlug = slugify(artist)
        val albumSlug = slugify(title)
        if (artistSlug.isEmpty() || albumSlug.isEmpty()) return null
        return cache.get(key(title, artist)) {
            val url = "$HOST/reviews/albums/$artistSlug-$albumSlug/"
            val html = gate.run { text(url) }
            listOfNotNull(html?.let { reviewFromPage(it, url, title, artist) })
        }.firstOrNull()
    }

    /**
     * The score already in hand, without a request. A cached miss and a name
     * never asked about both answer null, because both mean the same thing to
     * the caller: draw no score.
     */
    fun cachedReviewFor(title: String, artist: String): Review? =
        cache.peek(key(title, artist))?.firstOrNull()

    private fun key(title: String, artist: String): String =
        Normalize.text(title) + "||" + Normalize.text(artist)

    /**
     * Pulls the rating out of a review page's JSON-LD.
     *
     * Split out from the fetch so the parsing is testable without a network:
     * the shape of Pitchfork's markup is the part that can silently change.
     */
    internal fun reviewFromPage(html: String, url: String, title: String, artist: String): Review? {
        val score = jsonLdRating(html) ?: return null
        // A URL built from slugs can resolve to a different act's record of the
        // same name. The slug has to name the artist we asked about.
        val onPage = artistFromReviewUrl(url, title) ?: return null
        if (!Normalize.text(onPage).equals(Normalize.text(artist), ignoreCase = true) &&
            !Normalize.sortKey(Normalize.text(onPage))
                .contains(Normalize.sortKey(Normalize.text(artist)))
        ) {
            return null
        }
        return Review(url = url, score = score, isBestNewMusic = BNM.containsMatchIn(html))
    }

    /** `"ratingValue": 8.7` inside any of the page's JSON-LD blocks. */
    private fun jsonLdRating(html: String): Double? =
        RATING.find(html)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }

    /**
     * The artist half of a review slug. The slug is "<artist>-<album>", so the
     * known album slug is stripped and what remains is the artist.
     */
    internal fun artistFromReviewUrl(url: String, albumTitle: String): String? {
        val match = Regex("/reviews/albums/([^/?#]+)").find(url) ?: return null
        var slug = match.groupValues[1]
        val albumSlug = slugify(albumTitle)
        if (albumSlug.isNotEmpty() && slug.endsWith("-$albumSlug")) {
            slug = slug.dropLast(albumSlug.length + 1)
        }
        val words = slug.split("-").filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        return words.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    internal fun slugify(s: String): String = s.lowercase()
        .replace(Regex("['‘’]"), "")
        .replace(Regex("[^a-z0-9\\s-]"), " ")
        .replace(Regex("\\s+"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')

    private fun text(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
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

    private companion object {
        const val TAG = "Pitchfork"
        const val HOST = "https://pitchfork.com"

        /** The rating in a review page's JSON-LD. */
        val RATING = Regex("\"ratingValue\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9])?)\"?")

        /** Best New Music is a page-level flag, not part of the rating object. */
        val BNM = Regex("best[ -]?new[ -]?music", RegexOption.IGNORE_CASE)

        /**
         * A review is written once and its score does not move, so this could
         * be far longer; a day keeps a correction or a first review turning up
         * without the app having to be restarted.
         */
        const val REVIEW_TTL_MS = 24L * 60 * 60 * 1000

        /** Pitchfork throttles; one request at a time, spaced out. */
        const val INTERVAL_MS = 1500L
    }
}
