package com.musicd.sharecard.lms

import com.musicd.sharecard.Log
import com.musicd.sharecard.library.Normalize
import com.musicd.sharecard.strOrNull
import org.json.JSONObject

/**
 * PUT A SUGGESTED RECORD ON THE END OF A LYRION PLAYER'S PLAYLIST.
 *
 * The same feature `RoonBrowse` is, one protocol over, and very much simpler —
 * which is the point worth recording. Roon's is a seven-step browse walk that
 * took four releases to get right: a search screen, a descent onto the album's
 * own screen, an action menu, and a verb identified by its TITLE being exactly
 * "Queue". Lyrion has a control API, so this is two calls:
 *
 *   ["albums", 0, N, "search:<terms>", "tags:la"]
 *   [<player>, ["playlistcontrol", "cmd:add", "album_id:<id>"]]
 *
 * THREE THINGS THAT WERE HARD IN ROON ARE FREE HERE.
 *
 *  1. **`cmd:add` IS "Queue".** It appends. `cmd:load` replaces and plays,
 *     `cmd:insert` plays next. `RoonBrowse.pickQueueAction` exists because
 *     Roon's album menu OPENS with Play Now and anything reaching for "the
 *     first action" would stop what somebody is listening to — and because an
 *     installation in another language names its verbs differently. Here the
 *     verb is a named parameter, not a menu row to identify, so neither hazard
 *     exists. `cmd:load` must never appear in this file.
 *  2. **THE REPLY SAYS HOW MANY TRACKS WENT IN.** `playlistcontrol` answers
 *     with `count`. The expensive Roon bug was the final invoke never being
 *     read, so a refusal reached the page as "Added to the end of the queue" —
 *     a tap that claims to have worked is the one failure nobody goes looking
 *     for. Here success is a number, so it is asserted rather than assumed.
 *  3. **A SEARCH RESULT IS THE ALBUM.** `albums` answers with album ids, not
 *     with rows that open onto screens that hold rows. The off-by-one that
 *     cost Roon four releases has no equivalent.
 *
 * WHAT IS NOT FREE IS THE MATCHING, and that is ported wholesale. A suggestion
 * comes from Deezer and Deezer's copy of a record is whichever pressing it
 * sells — "Ignition (2008 Remaster)" against a library that calls it
 * "Ignition" — so both sides are folded through [Normalize.stripEdition] and
 * [Normalize.primaryArtist], and BOTH the title and the act have to overlap on
 * the row. No queue beats the wrong record in somebody's queue.
 *
 * NO LYRION SERVER IS REACHABLE FROM WHERE THIS WAS WRITTEN, so the shapes are
 * the documented ones and the socket is kept out of every decision that can be
 * wrong on its own. [attempts] is what the first real run is read from.
 */
class LmsQueue(
    private val client: LmsClient,
    /** The server that answered discovery, or null while none has. */
    private val server: () -> String?
) {

    /** Queued or not, and why — the same shape `RoonBrowse` answers in. */
    data class Outcome(val queued: Boolean, val detail: String)

    fun queueAlbum(album: String, artist: String, playerId: String): Outcome {
        if (album.isBlank()) return fail("no album named")
        val base = server() ?: return fail("no Lyrion server has answered")
        val player = playerId.ifBlank { return fail("no player named") }

        /*
         * A LADDER, NOT A GUESS — the same one `RoonBrowse.searchQueries`
         * walks, and for the same reason: which string finds a record in
         * somebody else's library cannot be settled from here. Most specific
         * first, and the first that MATCHES wins, so an ordinary hit still
         * costs one request.
         */
        for (terms in searchTerms(album, artist)) {
            val reply = client.request(base, "", listOf("albums", 0, SEARCH_ROWS, "search:$terms", "tags:$TAGS"))
            if (reply == null) {
                note("\"$terms\" -> the server did not answer the search")
                continue
            }
            val rows = LmsQueue.rows(reply)
            val id = pickAlbum(rows, album, artist)
            if (id == null) {
                note("\"$terms\" -> no match in ${rows.size} row(s): " + describe(rows))
                continue
            }
            note("\"$terms\" -> album_id $id")
            return append(base, player, id)
        }
        return fail("not in your Lyrion library, or not under that name")
    }

    /**
     * `cmd:add`, and the reply is READ.
     *
     * `count` is how many tracks went in. Anything else — a reply that never
     * came, a count of zero, a count that is not a number — is a failure, said
     * so. See the note at the top about the Roon invoke that was never read.
     */
    private fun append(base: String, player: String, albumId: String): Outcome {
        val reply = client.request(base, player, listOf("playlistcontrol", "cmd:add", "album_id:$albumId"))
        if (reply == null) {
            return fail("the server did not answer playlistcontrol for album_id $albumId")
        }
        val added = count(reply)
        if (added <= 0) {
            return fail("the server added nothing for album_id $albumId: $reply")
        }
        note("queued album_id $albumId ($added track(s))")
        return Outcome(true, if (added == 1) "Added 1 track to the queue" else "Added $added tracks to the queue")
    }

    // --------------------------------------------------------- diagnostics

    /** The last few attempts, for /api/debug. There cannot be another test. */
    fun attempts(): List<String> = synchronized(notes) { notes.toList() }

    private val notes = ArrayList<String>()

    private fun note(line: String) {
        Log.i(TAG, line)
        synchronized(notes) {
            notes += line
            while (notes.size > MAX_NOTES) notes.removeAt(0)
        }
    }

    private fun fail(why: String): Outcome {
        note("gave up: $why")
        return Outcome(false, why)
    }

    private fun describe(rows: List<JSONObject>): String =
        rows.take(4).joinToString("; ") {
            (it.strOrNull("album") ?: "?") + " / " + (it.strOrNull("artist") ?: "—")
        }.ifEmpty { "none" }

    internal companion object {
        private const val TAG = "LmsQueue"
        private const val MAX_NOTES = 8

        /** Enough rows that a common title is still found among its namesakes. */
        const val SEARCH_ROWS = 20

        /**
         * `l` is the album title and `a` the artist.
         *
         * THE TAG STRING DECIDES WHICH FIELDS COME BACK AT ALL — ask for too
         * few and the artist is simply absent, which reads exactly like a row
         * that names nobody and would be matched on its title alone. Same
         * lesson as `LmsClient.TAGS`.
         */
        const val TAGS = "la"

        /**
         * What to type in Lyrion's search box, most specific first.
         *
         * The edition comes off and the credit is cut to its first act,
         * because a suggestion is spelled DEEZER's way and a library is
         * spelled its owner's. Both rules already existed for other callers —
         * `stripEdition` for Pitchfork, `primaryArtist` for the links row —
         * and are used here rather than copied.
         */
        internal fun searchTerms(album: String, artist: String): List<String> {
            val plainAlbum = Normalize.stripEdition(album).trim()
            val act = Normalize.primaryArtist(artist).trim()
            return listOf(
                if (act.isNotEmpty()) "$act $plainAlbum" else plainAlbum,
                plainAlbum,
                if (artist.isNotBlank()) "$artist $album" else album
            ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }

        /** `albums_loop`, or nothing. Lenient: the key has moved before. */
        internal fun rows(reply: JSONObject): List<JSONObject> {
            val loop = reply.optJSONArray("albums_loop") ?: reply.optJSONArray("titles_loop")
                ?: return emptyList()
            val out = ArrayList<JSONObject>(loop.length())
            for (i in 0 until loop.length()) loop.optJSONObject(i)?.let(out::add)
            return out
        }

        /**
         * THE ONE ROW THAT IS THIS RECORD, OR NOTHING.
         *
         * Ported from `RoonBrowse.pickAlbum` with its hard-won shape intact:
         *
         *  - BOTH names must overlap where the row names an artist. A row that
         *    names somebody else contradicts everything — "Cult" by To/Die/For
         *    against Static-X's record of the same name is the pair this repo
         *    has already been burned by.
         *  - A row that names NOBODY is matched on its title alone. A library
         *    files box sets, soundtracks and compilations under no artist, and
         *    demanding one turns a library that HOLDS the record into "not in
         *    your library".
         *  - TWO blank-artist candidates is a coin toss and neither is taken.
         *    No queue beats the wrong record in somebody's queue, which is the
         *    one failure this feature must never have.
         */
        internal fun pickAlbum(rows: List<JSONObject>, album: String, artist: String): String? {
            val named = ArrayList<String>()
            val anonymous = ArrayList<String>()
            for (row in rows) {
                val id = row.strOrNull("id") ?: continue
                val title = row.strOrNull("album") ?: continue
                if (!titlesMatch(title, album)) continue
                val act = row.strOrNull("artist")
                if (act.isNullOrBlank()) {
                    anonymous += id
                } else if (artist.isNotBlank() && actsMatch(act, artist)) {
                    named += id
                } else if (artist.isBlank()) {
                    // Nothing to contradict, because nothing was asked for.
                    anonymous += id
                }
            }
            named.firstOrNull()?.let { return it }
            return anonymous.singleOrNull()
        }

        private fun titlesMatch(rowTitle: String, wanted: String): Boolean =
            Normalize.namesOverlap(Normalize.stripEdition(wanted), Normalize.stripEdition(rowTitle))

        private fun actsMatch(rowArtist: String, wanted: String): Boolean =
            Normalize.namesOverlap(Normalize.primaryArtist(wanted), Normalize.primaryArtist(rowArtist))

        /**
         * How many tracks `playlistcontrol` says it added.
         *
         * LENIENT ABOUT THE TYPE, because LMS writes its numbers as strings as
         * often as not — the same family as `LmsClient.truthy`, which exists
         * because `optBoolean` reads the number 1 as false. A count that
         * cannot be read is ZERO, so it fails loudly rather than reporting a
         * success nobody can check.
         */
        internal fun count(reply: JSONObject): Int = when (val raw = reply.opt("count")) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull() ?: 0
            else -> 0
        }
    }
}
