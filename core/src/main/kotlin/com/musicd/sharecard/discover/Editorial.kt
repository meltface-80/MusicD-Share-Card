package com.musicd.sharecard.discover

import com.musicd.sharecard.Log
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * WHAT THE PRESS HAS BEEN REVIEWING, AS RECORDS RATHER THAN AS ARTICLES.
 *
 * Asked for as "what's good right now", drawn as cover art, tapping through to
 * the review. So what this reads out of a feed is an ARTIST AND AN ALBUM — the
 * headline is used to identify the record and is then thrown away. The article
 * itself is never fetched, never stored and never shown; the only thing that
 * survives is a link to the people who wrote it, with their name on it.
 *
 * THAT IS THE LINE, AND IT IS WORTH BEING EXACT ABOUT. An RSS feed is published
 * to be syndicated, so reading one is not scraping and rendering what it names
 * is not a reproduction. The prose is another matter entirely: it is theirs, in
 * any wrapper, and nothing here keeps a word of it. A feed's `description` is
 * deliberately not read at all — not to shorten, not to excerpt, not to show on
 * hover — because the safest way not to publish somebody's writing is not to
 * hold it.
 *
 * TWO FEEDS, AND THE OTHERS WERE TRIED AND LEFT OUT. A title only yields a
 * record when the publisher writes it to a shape: Pitchfork's album feed is
 * "Artist: Album" and NME's is "Artist - 'Album' review ...". The Quietus and
 * Bandcamp Daily mix features, lists and interviews into the same feed, so
 * their headlines are prose — "The Strange World of..." names no record, and
 * guessing one out of it puts a wrong sleeve under a right review. A confident
 * wrong answer is worse than none, which is the rule this repo keeps returning
 * to, so a shape that cannot be read is skipped and [attempts] says so.
 *
 * NOT ONE OF THESE HAS BEEN REACHED FROM WHERE THIS WAS WRITTEN — the proxy
 * here answers 403 to the CONNECT for both hosts, checked rather than assumed.
 * The parsing is separated from the socket and pinned to fixtures; treat the
 * first real run as the verification.
 */
class Editorial(
    private val http: OkHttpClient,
    private val userAgent: String,
    /** A seam for the tests, which want no socket. */
    private val fetchText: (String) -> String? = { null }
) {

    /** One record somebody has just reviewed, and where to read it. */
    data class Reviewed(
        val artist: String,
        val album: String,
        val art: String?,
        val source: String,
        val url: String
    )

    fun recent(): List<Reviewed> {
        val out = ArrayList<Reviewed>()
        for (feed in FEEDS) {
            val xml = get(feed.url)
            if (xml == null) {
                note("${feed.name}: no answer")
                continue
            }
            val rows = runCatching { parse(xml, feed) }
                .onFailure { note("${feed.name}: unreadable (${it.javaClass.simpleName})") }
                .getOrDefault(emptyList())
            note("${feed.name} -> ${rows.size} record(s)")
            out += rows
        }
        return out
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

    /** The last few feed reads, for /api/debug. */
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
        private const val TAG = "Editorial"
        private const val MAX_NOTES = 8

        /**
         * How a publisher writes a review's headline.
         *
         * Declared per feed rather than guessed, because guessing is how a
         * wrong sleeve ends up under a right review.
         */
        enum class Shape {
            /** "Artist: Album" — Pitchfork's album feed. */
            COLON,

            /** "Artist - 'Album' review: something" — NME's. */
            QUOTED
        }

        data class Feed(val name: String, val url: String, val shape: Shape, val mustContain: String)

        /**
         * THE FEEDS, AND EACH ONE IS AN ALBUM-REVIEW FEED SPECIFICALLY.
         *
         * `mustContain` is the second guard: a publisher putting a news item in
         * a review feed is ordinary, and the link's own path is what tells them
         * apart — the same check Pitchfork's own lookup already makes.
         */
        val FEEDS = listOf(
            Feed(
                "Pitchfork", "https://pitchfork.com/feed/feed-album-reviews/rss",
                Shape.COLON, "/reviews/albums/"
            ),
            Feed(
                "NME", "https://www.nme.com/reviews/album/feed",
                Shape.QUOTED, "/reviews/"
            )
        )

        /** Split from the fetch, so every shape above is testable with no network. */
        internal fun parse(xml: String, feed: Feed): List<Reviewed> {
            val out = ArrayList<Reviewed>()
            for (block in ITEM.findAll(xml)) {
                val item = block.value
                val link = tag(item, LINK)?.substringBefore('?')?.substringBefore('#') ?: continue
                if (!link.contains(feed.mustContain)) continue
                val title = tag(item, TITLE) ?: continue
                val (artist, album) = split(title, feed.shape) ?: continue
                out += Reviewed(artist, album, imageIn(item), feed.name, link)
            }
            return out
        }

        /**
         * THE ARTIST AND THE ALBUM OUT OF A HEADLINE, OR NOTHING.
         *
         * Null is a real answer and the common one: a feature, a list, an
         * interview, a headline the publisher wrote freehand. A record the
         * shape cannot name is skipped rather than guessed at, because the
         * cost of guessing is a sleeve and a set of links belonging to
         * somebody else's record sitting under somebody's review.
         */
        internal fun split(title: String, shape: Shape): Pair<String, String>? {
            val clean = title.trim()
            if (clean.isEmpty()) return null
            return when (shape) {
                Shape.COLON -> {
                    // "Artist: Album", split at the FIRST colon.
                    //
                    // An ALBUM carrying one is ordinary — a subtitle, a
                    // reissue, a deluxe edition named after itself — and an
                    // ARTIST carrying one is rare enough that nobody here can
                    // name an example. Splitting at the last put the album's
                    // own subtitle on the end of the artist, which is a name
                    // that matches nothing and a set of links about nobody.
                    val at = clean.indexOf(':')
                    if (at <= 0 || at >= clean.length - 1) return null
                    val artist = clean.substring(0, at).trim()
                    val album = clean.substring(at + 1).trim()
                    if (artist.isEmpty() || album.isEmpty()) null else artist to album
                }

                Shape.QUOTED -> {
                    // "Artist - 'Album' review: ...". The album is whatever is
                    // quoted; the artist is what precedes the dash before it.
                    val quoted = QUOTED.find(clean) ?: return null
                    val album = quoted.groupValues.drop(1).firstOrNull { it.isNotBlank() }
                        ?.trim() ?: return null
                    val before = clean.substring(0, quoted.range.first)
                    val artist = before.trimEnd().trimEnd('-', '–', '—').trim()
                    if (artist.isEmpty() || album.isEmpty()) null else artist to album
                }
            }
        }

        /**
         * A sleeve out of the item, if the feed carries one.
         *
         * `media:content`, `media:thumbnail` and `enclosure` are the three
         * places a feed puts an image, and publishers use all of them. No
         * image is not a reason to drop the record — the grid draws a
         * placeholder and the review is still worth reaching.
         */
        internal fun imageIn(item: String): String? {
            for (pattern in IMAGE_ATTRS) {
                val url = pattern.find(item)?.groupValues?.get(1)?.trim()
                if (!url.isNullOrEmpty() && url.startsWith("https://")) return url
            }
            return null
        }

        private fun tag(item: String, pattern: Regex): String? =
            pattern.find(item)?.groupValues?.get(1)
                ?.let { CDATA.find(it)?.groupValues?.get(1) ?: it }
                ?.replace(TAGS, "")
                ?.replace("&amp;", "&")
                ?.replace("&#8216;", "'")
                ?.replace("&#8217;", "'")
                ?.replace("&#039;", "'")
                ?.replace("&#39;", "'")
                ?.replace("&quot;", "\"")
                ?.replace("&lt;", "<")
                ?.replace("&gt;", ">")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        /*
         * EVERY LITERAL BRACE AND BRACKET IS ESCAPED. A Regex in a companion
         * object is a static initialiser, and Android's ICU engine refuses a
         * dangling one where this JVM treats it as a literal — which took the
         * whole app down once. See RegexPortabilityTest.
         */
        private val ITEM = Regex("<item[\\s>][\\s\\S]*?</item>", RegexOption.IGNORE_CASE)
        private val LINK = Regex("<link[^>]*>([\\s\\S]*?)</link>", RegexOption.IGNORE_CASE)
        private val TITLE = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
        private val CDATA = Regex("<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>")
        private val TAGS = Regex("<[^>]+>")

        /** Straight and curly quotes both, because publishers use both. */
        private val QUOTED = Regex("[‘'\"]([^’'\"]{1,120})[’'\"]")

        private val IMAGE_ATTRS = listOf(
            Regex("<media:content[^>]*url=\"([^\"]+)\"", RegexOption.IGNORE_CASE),
            Regex("<media:thumbnail[^>]*url=\"([^\"]+)\"", RegexOption.IGNORE_CASE),
            Regex("<enclosure[^>]*url=\"([^\"]+)\"[^>]*type=\"image", RegexOption.IGNORE_CASE)
        )
    }
}
