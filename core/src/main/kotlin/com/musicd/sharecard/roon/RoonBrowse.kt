package com.musicd.sharecard.roon

import com.musicd.sharecard.Log
import com.musicd.sharecard.library.Normalize
import com.musicd.sharecard.str
import com.musicd.sharecard.strOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Putting a record at the end of a Roon zone's queue.
 *
 * THIS IS THE FIRST THING IN THIS APP THAT IS NOT A READ, and it was a scope
 * decision before it was a feature. Every route here answers a question; the
 * only things that ever wrote were the pairing token, the webhooks, the caches
 * and the settings, and none of them touch a speaker. Asked for directly — a
 * suggestion, on a card that came from a Roon zone, tapped to go on the end of
 * the queue — so the rule is narrowed rather than dropped: ONE action, QUEUE
 * and nothing else, behind the same gate as changing a webhook.
 *
 * THE PARSING IS SEPARATED FROM THE SOCKET ON PURPOSE. No Roon Core is
 * reachable from where this was written, so none of the wire traffic here has
 * been seen — these are the shapes RoonLabs' own `node-roon-api-browse`
 * documents and that MusicD Remote Lite drives against real hardware. The
 * decisions that can be wrong on their own (which row is the album, which
 * action is Queue) are pure functions with tests; the socket calls are the
 * thin part, so if the real thing differs it is a function to correct rather
 * than a protocol to re-derive.
 *
 * THE ONE GUARD THAT MATTERS MORE THAN THE FEATURE: [pickQueueAction] takes an
 * action named "Queue" and refuses everything else. Roon's album menu has Play
 * Now at the top of it, and an app that reaches for "the first action" would
 * stop whatever somebody is listening to and start something else. A tap that
 * does nothing is a disappointment; a tap that hijacks the room is a bug
 * nobody forgives.
 */
class RoonBrowse(private val socket: () -> MooSocket?) {

    /** What happened, in terms the page can show without translating. */
    data class Outcome(val queued: Boolean, val detail: String)

    /**
     * One Roon browse session per call.
     *
     * Roon keys browse state by `multi_session_key`, and two searches sharing
     * one would walk into each other's results. A fresh key per call is the
     * documented way to keep them apart.
     */
    private fun sessionKey(): String = "sharecard-" + System.nanoTime().toString(36)

    fun queueAlbum(album: String, artist: String, zoneId: String): Outcome {
        val moo = socket() ?: return Outcome(false, "Not connected to Roon.")
        val key = sessionKey()
        return try {
            // 1. Search Roon for the record. `pop_all` starts from the top
            //    rather than wherever a previous browse was left.
            request(
                moo,
                JSONObject()
                    .put("hierarchy", HIERARCHY)
                    .put("input", "$artist $album")
                    .put("pop_all", true)
                    .put("multi_session_key", key)
            )
            val results = load(moo, key)

            // 2. Roon groups a search under headings. The Albums one is the
            //    only one worth opening: an Artists row would drill into a
            //    discography and a Tracks row into a single song.
            val albumsHeading = pickAlbumsHeading(results)
                ?: return Outcome(false, "Roon found nothing for that record.")
            request(moo, browseItem(albumsHeading, key))
            val albums = load(moo, key)

            // 3. The right record among them, by name AND by artist.
            val albumRow = pickAlbum(albums, album, artist)
                ?: return Outcome(false, "That record is not in your Roon library.")
            request(moo, browseItem(albumRow, key))
            val albumScreen = load(moo, key)

            // 4. The album's action menu, then Queue within it.
            val menu = pickActionList(albumScreen)
                ?: return Outcome(false, "Roon offered no actions for that record.")
            request(moo, browseItem(menu, key))
            val actions = load(moo, key)

            val queue = pickQueueAction(actions)
                ?: return Outcome(false, "Roon offered no Queue action for that record.")

            // 5. Perform it, in the zone the card is about.
            request(
                moo,
                browseItem(queue, key).put("zone_or_output_id", ZoneRefIds.raw(zoneId))
            )
            Outcome(true, "Added to the end of the queue in Roon.")
        } catch (e: Throwable) {
            // Throwable, not Exception: this runs on a request thread and a
            // class that fails to initialise throws an Error.
            Log.w(TAG, "could not queue $album: ${e.message}")
            Outcome(false, e.message ?: "Roon would not take that request.")
        }
    }

    // ------------------------------------------------------------ the wire

    private fun request(moo: MooSocket, body: JSONObject): JSONObject =
        JSONObject(moo.call(RoonServices.BROWSE, "browse", body).bodyText.orEmpty())

    private fun load(moo: MooSocket, key: String): JSONArray {
        val body = JSONObject()
            .put("hierarchy", HIERARCHY)
            .put("offset", 0)
            .put("count", PAGE)
            .put("multi_session_key", key)
        val answer = JSONObject(moo.call(RoonServices.BROWSE, "load", body).bodyText.orEmpty())
        return answer.optJSONArray("items") ?: JSONArray()
    }

    private fun browseItem(item: JSONObject, key: String) = JSONObject()
        .put("hierarchy", HIERARCHY)
        .put("item_key", item.str("item_key"))
        .put("multi_session_key", key)

    companion object {
        private const val TAG = "RoonBrowse"
        private const val HIERARCHY = "search"

        /** Roon pages its lists; a search's first hundred rows is plenty. */
        private const val PAGE = 100

        /**
         * The "Albums" grouping out of a search result.
         *
         * Matched on the title because that is what Roon labels it, and
         * leniently because the heading is a display string: an installation in
         * another language will not say "Albums", so a single row that is not
         * Artists or Tracks is taken rather than nothing at all.
         */
        internal fun pickAlbumsHeading(items: JSONArray): JSONObject? {
            val rows = items.objects().filter { it.strOrNull("item_key") != null }
            rows.firstOrNull { it.str("title").equals("Albums", ignoreCase = true) }
                ?.let { return it }
            // Nothing called Albums. If there is exactly one grouping at all,
            // it is the only thing this could mean.
            return rows.singleOrNull()
        }

        /**
         * The row that really is this record.
         *
         * BOTH HALVES ARE CHECKED. Roon's search is fuzzy and will happily
         * return a compilation, a live album or another act's record of the
         * same name — and the thing at stake is what goes into somebody's
         * queue. [Normalize.namesOverlap] is the same rule the Wikipedia and
         * Deezer lookups use, so a record qualified on the right ("Spiderland
         * (Remastered)") still matches and a stranger's does not.
         */
        internal fun pickAlbum(items: JSONArray, album: String, artist: String): JSONObject? =
            items.objects()
                .filter { it.strOrNull("item_key") != null }
                .firstOrNull { row ->
                    Normalize.namesOverlap(row.str("title"), album) &&
                        (artist.isBlank() || Normalize.mentions(row.str("subtitle"), artist))
                }

        /**
         * The row that opens the album's actions.
         *
         * Roon marks it `action_list`. Taken by the hint rather than by its
         * title, which is a display string and differs by language.
         */
        internal fun pickActionList(items: JSONArray): JSONObject? =
            items.objects().firstOrNull {
                it.str("hint").equals("action_list", ignoreCase = true) &&
                    it.strOrNull("item_key") != null
            }

        /**
         * QUEUE, AND ONLY QUEUE.
         *
         * This is the guard that matters more than the feature works. Roon's
         * album menu opens with Play Now, so anything that reached for "the
         * first action" would stop whatever is playing in that room and start
         * something else — from a tap on a suggestion. A tap that does nothing
         * is a disappointment; a tap that hijacks the room is unforgivable.
         *
         * So the title must BE "Queue". Not "starts with", not "the first
         * action", not "Play" anything. An installation in another language
         * will find nothing here and the tap will fall back to opening the
         * record in a streaming service, which is the right way to be wrong.
         */
        internal fun pickQueueAction(items: JSONArray): JSONObject? =
            items.objects().firstOrNull {
                it.str("title").trim().equals("Queue", ignoreCase = true) &&
                    it.strOrNull("item_key") != null
            }

        private fun JSONArray.objects(): List<JSONObject> =
            (0 until length()).mapNotNull { optJSONObject(it) }
    }
}

/** Splitting this app's prefixed zone id back into the one Roon knows. */
internal object ZoneRefIds {
    fun raw(id: String): String = id.substringAfter(':', id)
}
