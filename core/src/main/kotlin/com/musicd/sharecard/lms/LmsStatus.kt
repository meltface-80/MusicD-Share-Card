package com.musicd.sharecard.lms

import com.musicd.sharecard.source.PlayState
import com.musicd.sharecard.str
import org.json.JSONObject

/**
 * A `status` answer, turned into the four things a card needs.
 *
 * Kept as pure functions over a [JSONObject] for the same reason `Didl` is:
 * this is the part that can be wrong, it cannot be exercised without a real
 * server, and a test can hand it a response by hand.
 */
object LmsStatus {

    /** What one player is playing, already flattened. */
    data class Now(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val artUrl: String = "",
        val state: PlayState = PlayState.UNKNOWN,
        val isStream: Boolean = false
    ) {
        val isEmpty: Boolean get() = title.isEmpty() && artist.isEmpty() && album.isEmpty()
    }

    /**
     * `mode` is the transport, and LMS spells it in words.
     *
     * Anything unrecognised is UNKNOWN rather than STOPPED: the two are treated
     * differently by the chooser, and guessing "stopped" for a word nobody has
     * seen would quietly hide a room that is playing.
     */
    internal fun stateOf(mode: String): PlayState = when (mode.lowercase()) {
        "play" -> PlayState.PLAYING
        "pause" -> PlayState.PAUSED
        "stop" -> PlayState.STOPPED
        else -> PlayState.UNKNOWN
    }

    /**
     * The track object, wherever this server put it.
     *
     * A local file lands in `playlist_loop[0]` and that is the whole of it.
     * A radio stream is the awkward one: the loop entry holds what was QUEUED,
     * which is the station, and `remoteMeta` holds what is PLAYING, which is
     * the song. So remoteMeta wins every field it has, and the loop entry fills
     * the gaps behind it.
     *
     * THAT ORDER IS THE WHOLE POINT. The other way round makes a card headed
     * "BBC Radio 6 Music" while the server knows perfectly well it is playing
     * Aphex Twin — the same class of mistake as reading Roon's three_line in
     * the wrong order, and it looks almost right, which is what makes it
     * expensive. `LmsStatusTest` pins it.
     */
    internal fun trackOf(result: JSONObject): JSONObject {
        val loop = result.optJSONArray("playlist_loop")?.optJSONObject(0)
        val remote = result.optJSONObject("remoteMeta")
        if (loop == null) return remote ?: JSONObject()
        if (remote == null) return loop
        val merged = JSONObject()
        for (key in loop.keys()) merged.put(key, loop.get(key))
        for (key in remote.keys()) {
            // Empty is not an answer: a remoteMeta that carries the key with
            // nothing in it must not erase the station's own artwork.
            if (remote.str(key).isNotEmpty() || !merged.has(key)) merged.put(key, remote.get(key))
        }
        return merged
    }

    /**
     * The first of these fields that actually says something.
     *
     * LMS names the artist differently depending on the tag set, the plugin and
     * whether the track is local — and an absent `artist` beside a populated
     * `albumartist` is a card headed with no artist at all.
     */
    private fun firstOf(track: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = track.str(key).trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    /**
     * Where the cover lives, as an absolute URL this app's proxy can fetch.
     *
     * THREE SHAPES, IN THIS ORDER. `artwork_url` is what a remote stream gives
     * and it may be absolute (a station's CDN) or a path on the server itself.
     * Everything local is addressed by an id under `/music/<id>/cover.jpg`, and
     * `coverid` is the one to prefer — `artwork_track_id` and the track's own
     * `id` are the older spellings of the same thing.
     *
     * An id is accepted only when it is DIGITS. LMS uses a negative id for
     * anything remote, and pasting one of those into a path builds a URL that
     * 404s on every card.
     */
    internal fun artUrl(base: String, track: JSONObject): String {
        val declared = track.str("artwork_url").trim()
        if (declared.isNotEmpty()) {
            if (declared.startsWith("http://") || declared.startsWith("https://")) return declared
            return base.trimEnd('/') + "/" + declared.removePrefix("/")
        }
        // THE FIRST USABLE ONE, NOT THE FIRST PRESENT ONE. This asked
        // firstOf() for the first non-empty of the three and then checked
        // whether it was any good — so a `coverid` that was present but not a
        // plain number ended the search THERE, and artwork_track_id sitting
        // right beside it was never looked at. Every local track came back
        // with no cover. A fallback chain that stops at the first candidate is
        // not a fallback chain.
        for (key in COVER_KEYS) {
            val id = track.str(key).trim()
            if (usableCoverId(id)) return base.trimEnd('/') + "/music/" + id + "/cover.jpg"
        }
        return ""
    }

    /**
     * An id that may be pasted into `/music/<id>/cover.jpg`.
     *
     * NOT "digits", which was the first rule here and was too narrow: a
     * coverid is an OPAQUE token and current Lyrion writes it as hex, so
     * digits-only threw away a perfectly good cover on every local track. What
     * actually has to be refused is a NEGATIVE id — Lyrion numbers everything
     * remote that way, and a minus sign in a path builds a URL that 404s on
     * every card. Requiring plain ASCII letters and digits refuses that, and
     * refuses a slash or a dot walking out of the path with it.
     */
    internal fun usableCoverId(id: String): Boolean =
        id.isNotEmpty() && id.length <= MAX_ID &&
            id.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }

    /** Where a cover id hides, best first. */
    private val COVER_KEYS = listOf("coverid", "artwork_track_id", "id")

    /** Long enough for any token Lyrion writes, short enough to be a sanity check. */
    private const val MAX_ID = 64

    /** A whole `status` result, flattened. */
    fun read(result: JSONObject?, base: String): Now {
        if (result == null) return Now()
        val track = trackOf(result)
        return Now(
            title = firstOf(track, "title", "track", "name"),
            artist = firstOf(track, "artist", "trackartist", "albumartist", "artist_name"),
            album = firstOf(track, "album", "album_name"),
            artUrl = artUrl(base, track),
            state = stateOf(result.str("mode")),
            isStream = LmsClient.truthy(track.opt("remote"))
        )
    }
}
