package com.musicd.sharecard.meta

import com.musicd.sharecard.library.Normalize
import java.net.URLEncoder
import java.util.Locale

/**
 * Links out to the streaming services, for the record on the card.
 *
 * Qobuz and TIDAL are ported verbatim from MusicD Remote Lite, including the
 * storefront table and the lesson in the comment below. The other four are new
 * here and are NOT verified against a device — see the note on each.
 *
 * A SEARCH, NOT THE ALBUM. Linking straight to an album page needs that
 * service's own id for it, and all that is known here is two strings off a
 * speaker. So what these build is honest: the service's own search, pre-filled,
 * which lands one tap from the record. Their APIs are not the way to do better;
 * they want credentials this project does not have. The page says "Find on",
 * never "Open in", because a search is all it is.
 *
 * ORDINARY HTTPS LINKS, DELIBERATELY, rather than spotify:// or qobuz://
 * custom schemes. Android hands an https link to whichever app claims that
 * domain, and iOS does the same through its Universal Links — so on a phone
 * with the app installed it opens there, and on one without it opens the web
 * player instead of failing. A custom scheme does the first and not the second,
 * and fails silently when it fails.
 *
 * WHICH HOST IS NOT COSMETIC, and MusicD Remote Lite's 0.4.19 got Qobuz wrong.
 * It used open.qobuz.com, and the Qobuz app swallowed the link and opened on
 * its Discover screen — the search never happened, on Android and on iOS
 * alike. The reason is published by Qobuz itself:
 *
 *   https://open.qobuz.com/.well-known/assetlinks.json   → com.qobuz.music
 *   https://open.qobuz.com/.well-known/apple-app-site-association
 *                                                        → paths ["*"]
 *
 * The app claims EVERY path on that host on both platforms, so the link never
 * reaches a browser, and the app has no screen for /search?q=. www.qobuz.com
 * publishes neither file, so it is not an app link on either platform and falls
 * through to the browser — which is what lands on the record.
 *
 * That is the failure mode to watch for in the four new ones: a host the app
 * claims wholesale, with no screen for the path being linked, opens the app on
 * its home screen and looks like the link simply did nothing.
 */
object StreamingLinks {

    /** One service, ready for the page to draw. */
    data class Link(val service: String, val name: String, val url: String)

    /** One service with no record in mind, for the settings screen to list. */
    data class Service(val service: String, val name: String)

    /**
     * Every service this app knows, in the order the page shows them.
     *
     * THE NAMES LIVE HERE AND NOWHERE ELSE, because the settings screen has to
     * list services when there is no album to link to — it is a list of what
     * CAN be shown, not of what is showing. [forAlbum] reads its labels out of
     * this map, and `StreamingLinksTest` asserts the two agree on the ids, so a
     * service added to one and not the other fails a test rather than appearing
     * in Settings with no chip behind it.
     */
    private val NAMES = linkedMapOf(
        "qobuz" to "Qobuz",
        "tidal" to "TIDAL",
        "spotify" to "Spotify",
        "apple" to "Apple Music",
        "amazon" to "Amazon Music",
        "deezer" to "Deezer",
        "bandcamp" to "Bandcamp"
    )

    fun services(): List<Service> = NAMES.map { (id, name) -> Service(id, name) }

    private fun named(id: String, url: String) = Link(id, NAMES.getValue(id), url)

    /**
     * Every service, in the order the page shows them.
     *
     * Empty when there is nothing worth searching for, which is the same
     * condition under which the card itself is not drawn.
     */
    fun forAlbum(
        artist: String?,
        album: String,
        locale: Locale = Locale.getDefault()
    ): List<Link> {
        val query = searchQuery(artist, album) ?: return emptyList()
        return listOf(
            named("qobuz", QOBUZ + storefront(locale) + "/search/?q=" + query),
            named("tidal", "https://tidal.com/search?q=$query"),
            named("spotify", "https://open.spotify.com/search/$query"),
            named("apple", "https://music.apple.com/search?term=$query"),
            named("amazon", "https://music.amazon.com/search/$query"),
            named("deezer", "https://www.deezer.com/search/$query"),
            named("bandcamp", "https://bandcamp.com/search?q=$query&item_type=a")
        )
    }

    /**
     * Qobuz's storefront search. The trailing slash on "/search/" is theirs:
     * without it they 301 to it, and the hop is free to skip.
     *
     * The path form is "/<storefront>/search/?q=", and the storefront segment
     * is not optional — https://www.qobuz.com/search/?q=… is a 404, as is any
     * country code Qobuz does not sell in. [storefront] picks a real one.
     */
    private const val QOBUZ = "https://www.qobuz.com/"

    /*
     * The four that are NOT inherited, and what is known about each.
     *
     * TIDAL — tidal.com/search?q=. Its apps claim tidal.com but only a listed
     * set of paths, and /search is not among them, so this reaches the browser.
     * Inherited and confirmed working in MusicD Remote Lite.
     *
     * SPOTIFY — open.spotify.com/search/<q>. The query is a PATH segment, not
     * a parameter, which is Spotify's own shape for it. Their apps claim
     * open.spotify.com on both platforms and do have a search screen, so this
     * should open in the app; a browser gets the web player's search.
     *
     * APPLE MUSIC — music.apple.com/search?term=. No storefront segment: Apple
     * redirects a storefront-less URL to the visitor's own, which avoids
     * keeping a table of ~175 country codes that would 404 when wrong. The
     * Qobuz lesson does not transfer — Qobuz has no storefront-less form at
     * all, Apple does.
     *
     * AMAZON MUSIC — music.amazon.com/search/<q>, again a path segment.
     *
     * DEEZER — www.deezer.com/search/<q>. Deezer also serves /<lang>/search/,
     * and drops the language segment when it is absent rather than 404ing.
     *
     * BANDCAMP — bandcamp.com/search?q=, with item_type=a to keep the results
     * to albums rather than mixing in tracks and artists. An unknown parameter
     * there is ignored rather than fatal, so the worst it costs is a wider
     * result page.
     *
     * NONE OF THE LAST FOUR HAS BEEN OPENED ON A PHONE. The URL building below
     * is tested; which app answers which host is not something a JVM test or
     * this build environment can establish, and the failure mode is a link that
     * opens an app on its home screen rather than an error. They need one tap
     * each on a device, on Android and on iOS.
     */

    /**
     * The Qobuz storefront to search, for a device set to [locale].
     *
     * Qobuz has exactly these thirty and answers anything else with a 404, so
     * this cannot be built by string-joining language and country and hoping.
     * An exact "country-language" match wins; failing that any storefront in
     * the same country does, which is what settles a device set to English in
     * Belgium; failing that, us-en, which is where qobuz.com itself sends a
     * visitor it cannot place.
     */
    internal fun storefront(locale: Locale): String {
        val country = locale.country.lowercase(Locale.ROOT)
        if (country.isEmpty()) return DEFAULT_STORE
        val exact = "$country-${locale.language.lowercase(Locale.ROOT)}"
        if (exact in STOREFRONTS) return exact
        return STOREFRONTS.firstOrNull { it.startsWith("$country-") } ?: DEFAULT_STORE
    }

    /**
     * "artist album", encoded, or null when there is nothing worth searching.
     *
     * PERCENT-ENCODED RATHER THAN FORM-ENCODED: URLEncoder writes a space as
     * "+", which is correct in a form body and merely conventional in a query
     * string — and a title with a real plus in it comes back wrong from
     * whichever end decodes it the other way. Half these services take the
     * query as a PATH segment, where "+" is not a space at all and would be
     * searched for literally (Spotify, Amazon and Deezer). Titles carrying "&", "#" or a quote are the
     * reason any of this is encoded to begin with.
     *
     * A SLASH IS THE EXCEPTION THAT MUST NOT MERELY BE ENCODED. Qobuz's search
     * redirects "?q=…" into a path segment and decodes the %2F on the way, so
     * "AC/DC" arrives as two segments and 404s — and for the four services that
     * take the query in the path, an encoded slash is a path traversal waiting
     * to be normalised by somebody's proxy. A slash carries no meaning to a
     * search box anyway, so it is spent as a space before anything else
     * happens, and "AC DC Back in Black" finds the record.
     *
     * ONLY THE FIRST CREDITED ACT GOES IN THE BOX. A Roon card credited "Stan
     * Getz / Cal Tjader / Alan Jay Lerner / Frederick Loewe" searched for all
     * four names at once, and AllMusic said so in as many words: no such act
     * exists, so nothing matched — on that chip and on every service chip
     * beside it. [Normalize.primaryArtist] is where the splitting rule and its
     * limits live; note that it runs BEFORE the slash is spent as a space,
     * because a spaced slash is the separator it reads.
     */
    internal fun searchQuery(artist: String?, album: String): String? {
        val act = Normalize.primaryArtist(artist)
        val words = "$act $album".replace(SLASH, " ").trim().replace(WHITESPACE, " ")
        if (words.isEmpty()) return null
        return URLEncoder.encode(words, "UTF-8").replace("+", "%20")
    }

    private const val DEFAULT_STORE = "us-en"

    /** Qobuz's own country switcher, in its order — see [storefront]. */
    private val STOREFRONTS = listOf(
        "ar-es", "at-de", "au-en", "be-fr", "be-nl", "br-pt", "ca-en", "ca-fr",
        "ch-de", "ch-fr", "cl-es", "co-es", "de-de", "dk-en", "es-es", "fi-en",
        "fr-fr", "gb-en", "ie-en", "it-it", "jp-ja", "lu-de", "lu-fr", "mx-es",
        "nl-nl", "no-en", "nz-en", "pt-pt", "se-en", "us-en"
    )

    private val SLASH = Regex("[/\\\\]")
    private val WHITESPACE = Regex("\\s+")
}
