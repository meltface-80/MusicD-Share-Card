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
        val onByDefault: Boolean,
        /**
         * The label on the links-row chip, WHICH IS A CONSTANT AND MUST STAY
         * ONE.
         *
         * The artist chips were labelled `"AllMusic: $artist"`, and an artist
         * string is whatever the record says it is: "Stan Getz / Cal Tjader /
         * Alan Jay Lerner / Frederick Loewe" came back off a Roon card and
         * made a chip six lines deep. The links row is a four-column grid with
         * `align-items: stretch`, so that one chip set the height of the whole
         * row and the four on it came out as circles — reported as "button
         * sizes completely off". The row gives every chip a quarter of a phone
         * and one fixed height; a label built from a record cannot be held to
         * that, so none is.
         *
         * It says which of the two it is, because both AllMusic chips can be
         * on at once and "AllMusic" twice would be a coin toss.
         */
        val chip: String = if (kind == Kind.ARTIST) "$name artist" else name
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

    /** The chip label for a source, by id. A constant — see [Source.chip]. */
    fun chip(id: String): String? = ALL.firstOrNull { it.id == id }?.chip

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

    /**
     * AllMusic's artist search. Their artist ids are `mn…`, and equally opaque.
     *
     * THE ARTIST GOES IN THE ARTIST SLOT, which is not a tidy-up: that is the
     * argument [searchQuery] runs [com.musicd.sharecard.library.Normalize
     * .primaryArtist] over. Passed as the album it skipped that rule, and a
     * Roon card credited to four people searched AllMusic for all four names
     * at once — which is the failure this exists to avoid, reported with
     * AllMusic's own "No search results were found" page as the evidence.
     */
    fun artistUrl(artist: String?): String? =
        artist?.takeIf { it.isNotBlank() }
            ?.let { searchQuery(it, "") }
            ?.let { "https://www.allmusic.com/search/artists/$it" }
}
