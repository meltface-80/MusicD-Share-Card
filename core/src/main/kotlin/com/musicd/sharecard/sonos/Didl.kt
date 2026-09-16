package com.musicd.sharecard.sonos

import java.net.URI

/**
 * What a Sonos player says is on its transport right now.
 *
 * This is the one piece of Sonos that decides whether the app works at all,
 * and it is deliberately the most heavily tested thing in the repository —
 * because the answer arrives in a different shape for every source the user
 * asked this to cover. Roon, Spotify Connect, Apple Music through the Sonos
 * app, a line-in, a radio stream and the Sonos queue itself all populate
 * DIDL-Lite differently, and the card has to come out the same.
 */
data class NowPlaying(
    /** Track title — `dc:title`. */
    val track: String = "",
    /** `upnp:album`. Empty for radio and for most line-in sources. */
    val album: String = "",
    /** `upnp:artist` if present, else `dc:creator`. */
    val artist: String = "",
    /** `upnp:albumArtist`, when the source bothers to send one. */
    val albumArtist: String = "",
    /**
     * `upnp:albumArtURI`, resolved to an absolute URL against the player that
     * reported it. Sonos sends a player-relative `/getaa?...` for anything it
     * is transcoding or proxying, which is meaningless to a browser on another
     * device — see [absoluteArt].
     */
    val artUri: String = "",
    /** The transport URI. Its scheme is how a stream is told from a track. */
    val uri: String = "",
    /**
     * `r:streamContent` — what a radio station is announcing over the top of
     * the stream, usually "Artist - Title". The only metadata some stations
     * ever send.
     */
    val streamContent: String = ""
) {
    val isEmpty: Boolean
        get() = track.isEmpty() && album.isEmpty() && artist.isEmpty()

    /**
     * The artist to print on the card.
     *
     * The album artist wins when there is one: a compilation names a different
     * performer on every track, and a card headed with whoever happens to be
     * singing when you pressed the button is wrong about the record.
     */
    val displayArtist: String get() = albumArtist.ifEmpty { artist }

    /**
     * What the card calls the record.
     *
     * Falling back to the track title is not a nicety: radio and line-in carry
     * no album at all, and a card headed with the only name the source gave us
     * beats an empty one.
     *
     * But NOT when that name is an opaque identifier. Roon streaming to Sonos
     * reports a title of "Roon" followed by 32 hex characters — its session id —
     * and a card headed "Roon698eb0332b8c432d98294f5a377f3" is worse than a card
     * that admits it knows nothing, because it looks like the app working.
     */
    val displayAlbum: String
        get() = album.ifEmpty { if (Didl.looksLikeStreamId(track)) "" else track }
}

/**
 * DIDL-Lite as Sonos sends it.
 *
 * The document arrives XML-escaped inside a SOAP element, so it has been
 * unescaped exactly once by the time it gets here — pulling the text content of
 * `<TrackMetaData>` does that. Escaped twice and it would parse as one long
 * text node with no elements at all, which is why [parse] answering an empty
 * [NowPlaying] must stay distinguishable from it answering a populated one.
 */
object Didl {

    /**
     * Sonos's sentinel for "nothing is loaded". It appears as the whole of
     * TrackMetaData between tracks and while a group is regrouping, and parsing
     * it yields an item with every field empty — correct, but worth naming.
     */
    const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"

    fun parse(xml: String): NowPlaying {
        val trimmed = xml.trim()
        if (trimmed.isEmpty() || trimmed == NOT_IMPLEMENTED) return NowPlaying()
        val root = Xml.parse(trimmed) ?: return NowPlaying()

        // The first <item> in the document. A Sonos TrackMetaData carries
        // exactly one; a container (which is what CurrentURIMetaData holds for
        // a radio station) carries none, so the root itself is read instead.
        val item = Xml.descendants(root)
            .firstOrNull { Xml.localName(it) == "item" || Xml.localName(it) == "container" }
            ?: root

        var track = ""
        var album = ""
        var artist = ""
        var creator = ""
        var albumArtist = ""
        var art = ""
        var res = ""
        var stream = ""

        // Read the item's OWN children only. Descending would let a nested
        // <desc> or a service's own <item> inside the DIDL overwrite the
        // fields of the track actually playing.
        for (field in Xml.children(item)) {
            val value = Xml.text(field)
            if (value.isEmpty()) continue
            when (Xml.localName(field)) {
                "title" -> if (track.isEmpty()) track = value
                "album" -> if (album.isEmpty()) album = value
                "artist" -> if (artist.isEmpty()) artist = value
                // Kept apart from upnp:artist rather than folded into it,
                // because DOCUMENT ORDER would otherwise decide which wins.
                // Spotify writes <dc:creator> first and it is the broader
                // credit ("Frank Ocean, John Mayer") where <upnp:artist> is
                // the primary act — so a single field, filled by whichever
                // element came first, put the whole billing on the card.
                "creator" -> if (creator.isEmpty()) creator = value
                "albumArtist" -> if (albumArtist.isEmpty()) albumArtist = value
                "albumArtURI" -> if (art.isEmpty()) art = value
                "res" -> if (res.isEmpty()) res = value
                "streamContent" -> if (stream.isEmpty()) stream = value
            }
        }

        return NowPlaying(
            track = track,
            album = album,
            // creator is the fallback, consulted only when the source sent no
            // upnp:artist at all.
            artist = artist.ifEmpty { creator },
            albumArtist = albumArtist,
            artUri = art,
            uri = res,
            streamContent = stream
        )
    }

    /**
     * Fill the gaps in a track's metadata from the transport's.
     *
     * Field by field rather than "use one or the other": a radio stream sends
     * a title per song and a station name on the transport, and the card wants
     * the song with the station as its context — not one of the two.
     *
     * IT LIVES HERE, BESIDE THE PARSER, BECAUSE IT HAS A SECOND CALLER. It was
     * private to `Household` while Sonos was the only source making the second
     * call; [com.musicd.sharecard.upnp.UpnpSource] makes it too now. Same move
     * `Normalize.namesOverlap`, `Normalize.stripEdition` and `quality` each
     * made on their second caller, and for the same reason: two copies is how
     * one source learns a station's name and the next does not.
     */
    fun merge(track: NowPlaying, media: NowPlaying): NowPlaying {
        if (media.isEmpty && media.artUri.isEmpty()) return track
        return NowPlaying(
            track = track.track.ifEmpty { media.track },
            album = track.album.ifEmpty { media.album },
            artist = track.artist.ifEmpty { media.artist },
            albumArtist = track.albumArtist.ifEmpty { media.albumArtist },
            artUri = track.artUri.ifEmpty { media.artUri },
            uri = track.uri.ifEmpty { media.uri },
            streamContent = track.streamContent.ifEmpty { media.streamContent }
        )
    }

    /**
     * Resolve an album-art URI against the player that reported it.
     *
     * Sonos answers with a player-relative path — `/getaa?s=1&u=...` — for
     * anything it proxies, which is most streaming services. That path means
     * nothing to a browser on a phone across the room, and nothing to the
     * canvas that has to draw it, so it is made absolute against the player's
     * own address here. An already-absolute URI (some services hand over a
     * public CDN link) is returned untouched.
     */
    fun absoluteArt(artUri: String, playerBaseUrl: String): String {
        val raw = artUri.trim()
        if (raw.isEmpty()) return ""
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return raw
        return try {
            URI(playerBaseUrl.trimEnd('/') + "/").resolve(raw).toString()
        } catch (e: Exception) {
            // A path Sonos sent that java.net.URI will not take is far more
            // likely to be an unescaped character in the query than an attack;
            // either way the card draws without a cover rather than throwing.
            ""
        }
    }

    /**
     * An opaque machine identifier rather than something a person named.
     *
     * Roon hands Sonos a title of "Roon" + 32 hex characters, which is its
     * session id and not a record. The test is deliberately narrow — one
     * unbroken token whose tail is a long run of hex — because the cost of
     * getting it wrong is discarding a real album title. No record is called
     * "698eb0332b8c432d98294f5a377f3a11", and every real one either contains a
     * space or is not sixteen-plus hex characters long.
     */
    fun looksLikeStreamId(text: String): Boolean {
        val t = text.trim()
        if (t.length < 16 || t.any { it.isWhitespace() }) return false
        val hexTail = t.takeLastWhile { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        return hexTail.length >= 16
    }

    /**
     * Split a radio station's "Artist - Title" announcement.
     *
     * Returns null when the string carries no separator, because a station
     * that announces only a track name must not have half of it read as an
     * artist. The first separator wins: "Bruce Springsteen - Born to Run -
     * Live" is one artist and a title that contains a dash.
     */
    fun splitStreamContent(raw: String): Pair<String, String>? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // The three dashes a station might use, each surrounded by whitespace.
        // An unspaced hyphen is far more often part of a name ("Jay-Z").
        val match = Regex("\\s+[-–—]\\s+").find(text) ?: return null
        val artist = text.substring(0, match.range.first).trim()
        val title = text.substring(match.range.last + 1).trim()
        if (artist.isEmpty() || title.isEmpty()) return null
        return artist to title
    }
}
