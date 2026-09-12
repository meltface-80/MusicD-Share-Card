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
 * Trimmed from that app's version to the lookup a card needs — but NOT to the
 * constructed URL alone, which is what it was at first and why a just-released
 * album could come back with nothing. The listing pages and the search over
 * them really are about browsing, and there is no browsing here. The RSS feed
 * is not: it is how a review is found when the URL cannot be guessed.
 */
class Pitchfork(
    private val http: OkHttpClient,
    private val userAgent: String,
    /**
     * Where Pitchfork lives. A seam for the tests, which have to be able to
     * answer a 404 for one URL and a review for another to prove the fallbacks
     * run in the right order. Nothing in the app passes it.
     */
    private val host: String = HOST
) {

    private val feedUrl = "$host/feed/feed-album-reviews/rss"

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
     * The score for one album, cached under the name the caller asked with —
     * see [lookUp] for how it is actually found.
     */
    fun reviewFor(title: String, artist: String): Review? {
        if (title.isBlank() || artist.isBlank()) return null
        if (slugify(artist).isEmpty() || slugify(title).isEmpty()) return null
        return cache.get(key(title, artist)) {
            listOfNotNull(lookUp(title, artist))
        }.firstOrNull()
    }

    /**
     * Three tries, cheapest first.
     *
     * 1. THE CONSTRUCTED URL. `/reviews/albums/<artist>-<album>/` is one
     *    request and it is the only way an album from 1994 is found at all —
     *    nothing lists a review that old.
     * 2. THE SAME, WITH THE EDITION STRIPPED. A speaker reports what the
     *    service calls the record, and a service calls it "Album (Deluxe
     *    Edition)". Pitchfork reviewed "Album".
     * 3. THE RECENT-REVIEWS FEED. Which is the case this was missing: a record
     *    released this week, sitting in Pitchfork's feed, whose slug simply is
     *    not the one this would build. One fetch, cached, shared by every
     *    lookup that misses — see [recent].
     *
     * Each step only runs when the one before it found nothing, so the ordinary
     * hit still costs exactly one request.
     */
    private fun lookUp(title: String, artist: String): Review? {
        fromSlug(title, artist)?.let { return it }

        val stripped = stripEdition(title)
        if (stripped.isNotEmpty() && !stripped.equals(title, ignoreCase = true)) {
            fromSlug(stripped, artist)?.let { return it }
        }

        return fromRecent(if (stripped.isEmpty()) title else stripped, artist)
    }

    /**
     * The review at the URL this album's name builds, or null.
     *
     * A constructed URL can land on a real page for the WRONG record when two
     * artists share an album title, so the artist named in the URL has to agree
     * with the one asked about before anything is returned.
     */
    private fun fromSlug(title: String, artist: String): Review? {
        val artistSlug = slugify(artist)
        val albumSlug = slugify(title)
        if (artistSlug.isEmpty() || albumSlug.isEmpty()) return null
        val url = "$host/reviews/albums/$artistSlug-$albumSlug/"
        val html = gate.run { text(url) } ?: run { note("$url -> not found"); return null }
        val review = reviewFromPage(html, url, title, artist)
        note(if (review != null) "$url -> ${review.score}" else "$url -> not this artist")
        return review
    }

    /**
     * The review found by looking through what Pitchfork published recently.
     *
     * The album name here comes from PITCHFORK, not from the speaker, so the
     * verification in [reviewFromPage] strips Pitchfork's own album slug off
     * the URL and is left with Pitchfork's artist slug to compare — which is
     * the whole point, since the reason we are here is that the two names do
     * not slug the same way.
     */
    private fun fromRecent(title: String, artist: String): Review? {
        val want = Normalize.text(title)
        if (want.isEmpty()) return null
        val feed = recent()
        if (feed.isEmpty()) {
            note("recent reviews: nothing to search")
            return null
        }
        val hit = feed.firstOrNull { Normalize.text(it.album) == want }
        if (hit == null) {
            note("recent reviews (${feed.size}): no \"$title\"")
            return null
        }
        val html = gate.run { text(hit.url) } ?: run { note("${hit.url} -> unreadable"); return null }
        val review = reviewFromPage(html, hit.url, hit.album, artist)
        note(if (review != null) "${hit.url} -> ${review.score} (from the feed)"
        else "${hit.url} -> the feed's match is not this artist")
        return review
    }

    /** One entry of Pitchfork's album-review feed. */
    internal data class Listed(val url: String, val album: String)

    /**
     * What Pitchfork has published lately, from its RSS feed.
     *
     * THE FEED RATHER THAN THE LISTING PAGE. The listing is a Next.js page
     * whose reviews live in a `__PRELOADED_STATE__` blob — the app this was
     * ported from reads that, and needs it, because it draws a browsable list
     * with covers and dates. All that is wanted here is a URL for a title, and
     * an RSS feed is a contract where a preloaded state blob is an
     * implementation detail that can be reshaped without warning.
     *
     * Cached as a whole and shared: one fetch answers every album that misses,
     * rather than one per album.
     */
    internal fun recent(): List<Listed> = feedCache.get(FEED_KEY) {
        val xml = gate.run { text(feedUrl) } ?: return@get emptyList()
        parseFeed(xml)
    }

    /** Split from the fetch so the parsing is testable without a network. */
    internal fun parseFeed(xml: String): List<Listed> {
        val out = ArrayList<Listed>()
        for (block in ITEM.findAll(xml)) {
            val item = block.value
            val link = LINK.find(item)?.groupValues?.get(1)?.let(::unCdata)?.trim() ?: continue
            if (!link.contains("/reviews/albums/")) continue
            val album = TITLE.find(item)?.groupValues?.get(1)?.let(::unCdata)
                ?.let(::stripTags)?.trim().orEmpty()
            if (album.isEmpty()) continue
            out += Listed(link.substringBefore('?').substringBefore('#'), album)
        }
        return out
    }

    private fun unCdata(s: String): String =
        CDATA.find(s)?.groupValues?.get(1) ?: s

    private fun stripTags(s: String): String = s.replace(TAGS, "")
        .replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">")

    /**
     * A title as the streaming service spells it, without the edition it tacked
     * on. "Album (Deluxe Edition)" is the record Pitchfork reviewed as "Album".
     *
     * DELIBERATELY NARROW. Only a trailing bracketed group, and only one whose
     * words are in [EDITIONS] — because plenty of brackets are part of the
     * title, and "(Taylor's Version)" is a different record rather than a
     * dressed-up one. Stripping too eagerly finds the wrong review, which is
     * worse than finding none.
     */
    internal fun stripEdition(title: String): String {
        var out = title.trim()
        while (true) {
            val m = TRAILING_BRACKET.find(out) ?: break
            val inside = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (!EDITIONS.containsMatchIn(inside)) break
            val next = out.removeRange(m.range).trim().trimEnd('-', '\u2013').trim()
            if (next.isEmpty()) break
            out = next
        }
        return out
    }

    /** The last few lookups, for /api/debug. A miss is invisible otherwise. */
    fun attempts(): List<String> = synchronized(notes) { notes.toList() }

    private fun note(line: String) {
        synchronized(notes) {
            notes += line
            while (notes.size > MAX_NOTES) notes.removeAt(0)
        }
    }

    private val notes = ArrayList<String>()

    private val feedCache = TtlCache<String, List<Listed>>(FEED_TTL_MS, 2)

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
        // A URL can resolve to a different act's record of the same name —
        // whether it was constructed here or came out of the feed. The slug has
        // to name the artist we asked about.
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

    /**
     * A name as Pitchfork spells it in a URL.
     *
     * THE ACCENT FOLD IS NOT COSMETIC. Without it "Björk" became "bj-rk" —
     * every letter Pitchfork actually uses, minus the one it folds — so no
     * album by an artist with an accent in their name ever resolved, and the
     * card simply had no score. It went unnoticed because a missing score looks
     * exactly like a record nobody reviewed. [Normalize.text] is the app's one
     * folding rule and it already does this; this used to fold by hand and get
     * it wrong.
     *
     * The apostrophe goes FIRST and is deleted rather than folded: Pitchfork
     * writes "Don't" as "dont", and turning the apostrophe into a separator
     * first would give "don-t".
     */
    internal fun slugify(s: String): String =
        Normalize.text(s.replace(APOSTROPHE, ""))
            .replace(Regex("\\s+"), "-")
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

    companion object {
        private const val TAG = "Pitchfork"
        const val HOST = "https://pitchfork.com"

        /** The rating in a review page's JSON-LD. */
        val RATING = Regex("\"ratingValue\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9])?)\"?")

        val APOSTROPHE = Regex("['‘’]")

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

        const val FEED_KEY = "album-reviews"

        /**
         * Pitchfork publishes a handful of reviews a day, so an hour is fresh
         * enough for "was this reviewed this week" and keeps the feed to one
         * fetch however many albums miss.
         */
        const val FEED_TTL_MS = 60L * 60 * 1000

        val ITEM = Regex("<item\\b[\\s\\S]*?</item>", RegexOption.IGNORE_CASE)
        val LINK = Regex("<link>([\\s\\S]*?)</link>", RegexOption.IGNORE_CASE)
        val TITLE = Regex("<title>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
        val CDATA = Regex("<!\\[CDATA\\[([\\s\\S]*?)]]>")
        val TAGS = Regex("<[^>]+>")

        /** A trailing "(…)" or "[…]" — the only place an edition is ever added. */
        val TRAILING_BRACKET = Regex("\\s*(?:\\(([^()]*)\\)|\\[([^\\[\\]]*)])\\s*$")

        /**
         * What makes a bracketed suffix an edition rather than part of the
         * name. "Version" is absent on purpose: "(Taylor's Version)" is a
         * different record, not a dressed-up one.
         */
        val EDITIONS = Regex(
            "deluxe|remaster|expanded|anniversary|edition|explicit|clean|" +
                "bonus track|reissue|mono|stereo|special|super deluxe",
            RegexOption.IGNORE_CASE
        )

        const val MAX_NOTES = 12
    }
}
