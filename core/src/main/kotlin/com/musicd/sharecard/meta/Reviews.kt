package com.musicd.sharecard.meta

import com.musicd.sharecard.meta.StreamingLinks.searchQuery

/**
 * Where the words about a record — or about the act that made it — come from.
 *
 * THE ALBUM ONES DEFAULT ON AND THE ARTIST ONES DEFAULT OFF, which is not the
 * same rule as services and is deliberate. Wikipedia and Pitchfork are what the
 * card has always drawn: the blurb under the cover and the score in the corner.
 * Switching them off by default would empty every card in the house to make a
 * settings screen consistent, and nobody asked for that. The ARTIST sources are
 * new and are about somebody's biography rather than the record in front of
 * you, so they are asked for — which is what was requested.
 *
 * ALLMUSIC IS A SEARCH LINK, NOT A RESOLVED ONE. Their album URLs end in an
 * opaque id (`…-mw0000190771`) that cannot be built from a name, so a direct
 * link would mean reading it off their search page the way [QobuzAlbum] reads
 * Qobuz's. That is a real option later; it is not this change, and a search
 * that lands on the record is worth more than no chip at all. NOTHING HERE WAS
 * TESTED AGAINST THE LIVE SITE — allmusic.com is not reachable from where this
 * was written, so the shapes are the documented ones and the first real run is
 * the verification.
 */
object Reviews {

    enum class Kind { ALBUM, ARTIST }

    data class Source(
        val id: String,
        val name: String,
        val kind: Kind,
        /** On unless somebody says otherwise — see the note above. */
        val onByDefault: Boolean
    )

    const val WIKIPEDIA = "wikipedia"
    const val PITCHFORK = "pitchfork"
    const val ALLMUSIC = "allmusic"
    const val WIKIPEDIA_ARTIST = "wikipedia-artist"
    const val ALLMUSIC_ARTIST = "allmusic-artist"

    val ALL = listOf(
        Source(WIKIPEDIA, "Wikipedia", Kind.ALBUM, true),
        Source(PITCHFORK, "Pitchfork", Kind.ALBUM, true),
        Source(ALLMUSIC, "AllMusic", Kind.ALBUM, true),
        Source(WIKIPEDIA_ARTIST, "Wikipedia", Kind.ARTIST, false),
        Source(ALLMUSIC_ARTIST, "AllMusic", Kind.ARTIST, false)
    )

    fun onByDefault(id: String): Boolean = ALL.firstOrNull { it.id == id }?.onByDefault ?: false

    /** The ids this app knows, for a stored set to be checked against. */
    val IDS: Set<String> = ALL.mapTo(LinkedHashSet()) { it.id }

    /**
     * AllMusic's album search, built the way the streaming links are.
     *
     * Through [searchQuery] and not by hand, because that is the one place the
     * encoding rules live — a space is `%20` rather than `+`, and a slash is
     * spent as a space so "AC/DC" does not 404. Null when there is nothing
     * worth searching for, which is the same condition under which the card is
     * not drawn.
     */
    fun albumUrl(artist: String?, album: String): String? =
        searchQuery(artist, album)?.let { "https://www.allmusic.com/search/albums/$it" }

    /** AllMusic's artist search. Their artist ids are `mn…`, and equally opaque. */
    fun artistUrl(artist: String?): String? =
        artist?.takeIf { it.isNotBlank() }
            ?.let { searchQuery(null, it) }
            ?.let { "https://www.allmusic.com/search/artists/$it" }
}
