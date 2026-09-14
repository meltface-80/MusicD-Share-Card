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
     * THE LAST FEW ATTEMPTS, AND WHY THIS EXISTS AT ALL.
     *
     * Reported from the field: a suggested album that IS in the library was
     * tapped on a Roon card and did not arrive in the queue. `/api/debug` had
     * nothing whatever to say about it — no `queue` section, no note — and the
     * page threw the server's `detail` away and silently opened a streaming
     * search instead. So the whole report was "it did not work", against a
     * chain with seven places to stop: not connected, search found nothing, no
     * Albums heading, no row matching the record, no action list, no Queue
     * action, and Roon refusing the action itself. Seven causes, seven
     * different fixes, and nothing to tell them apart.
     *
     * Same lesson as [com.musicd.sharecard.meta.Pitchfork.attempts] and
     * `ArtProxy.attempts`, and the same remedy: the app says which step it
     * stopped at and what Roon actually sent, so the next tap is the bug
     * report. It matters more here than anywhere else in this app, because no
     * Roon Core is reachable from where this is written — the diagnostics ARE
     * the test.
     */
    fun attempts(): List<String> = synchronized(notes) { notes.toList() }

    private val notes = ArrayList<String>()

    private fun note(line: String) {
        Log.i(TAG, line)
        synchronized(notes) {
            notes += line
            while (notes.size > MAX_NOTES) notes.removeAt(0)
        }
    }

    /** What a list of rows actually held, for a note that names the reason. */
    private fun titles(items: JSONArray): String {
        val rows = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
        if (rows.isEmpty()) return "no rows"
        return rows.take(8).joinToString(", ") {
            val title = it.str("title").ifEmpty { "(untitled)" }
            val sub = it.strOrNull("subtitle").orEmpty()
            val hint = it.strOrNull("hint").orEmpty()
            title + (if (sub.isNotEmpty()) " / $sub" else "") + (if (hint.isNotEmpty()) " [$hint]" else "")
        } + (if (rows.size > 8) ", +${rows.size - 8} more" else "")
    }

    /**
     * One Roon browse session per call.
     *
     * Roon keys browse state by `multi_session_key`, and two searches sharing
     * one would walk into each other's results. A fresh key per call is the
     * documented way to keep them apart.
     */
    private fun sessionKey(): String = "sharecard-" + System.nanoTime().toString(36)

    fun queueAlbum(album: String, artist: String, zoneId: String): Outcome {
        /*
         * WHAT GOES IN THE SEARCH BOX IS NOT WHAT THE SUGGESTION SAYS.
         *
         * A suggestion comes from Deezer, and Deezer's copy of a record is
         * whichever pressing it sells: "The Offspring · Ignition (2008
         * Remaster)". The copy in somebody's Roon library is called "Ignition".
         * So the search went out with words no record in the house is named,
         * and Roon answered `action: "none"` — which the app, before the reply
         * check, did not even read. Reported with a photograph of the page
         * saying "Roon answered \"none\" where a list was expected", and
         * diagnosed by the person who owns the library: "could this be because
         * the version I have isn't labelled as 2008 remaster".
         *
         * So the EDITION comes off, for every album, and only the first
         * credited act goes in — both through the folds that already exist for
         * this in Normalize, neither invented here. The full title is still
         * what gets MATCHED against Roon's rows a few steps down, because
         * namesOverlap accepts a name qualified on the right either way round.
         */
        val query = Normalize.stripEdition(album)
        val act = Normalize.primaryArtist(artist)
        val input = searchInput(album, artist)
        val what = "$artist \u2014 $album -> ${ZoneRefIds.raw(zoneId)}"
        val moo = socket() ?: return fail(what, "not connected to Roon", "Not connected to Roon.")
        val key = sessionKey()
        return try {
            // 1. Search Roon for the record. `pop_all` starts from the top
            //    rather than wherever a previous browse was left.
            note("$what -> searching Roon for \"$input\"")
            val search = request(
                moo,
                JSONObject()
                    .put("hierarchy", HIERARCHY)
                    .put("input", input)
                    .put("pop_all", true)
                    .put("multi_session_key", key)
            )
            refused(search, expectList = true)?.let { return fail(what, "search: $it", it) }
            val results = load(moo, key)

            // 2. Roon groups a search under headings. The Albums one is the
            //    only one worth opening: an Artists row would drill into a
            //    discography and a Tracks row into a single song.
            val albumsHeading = pickAlbumsHeading(results)
                ?: return fail(
                    what,
                    "no Albums heading in the search results: ${titles(results)}",
                    "Roon found nothing for that record."
                )
            refused(request(moo, browseItem(albumsHeading, key)), expectList = true)
                ?.let { return fail(what, "opening Albums: $it", it) }
            val albums = load(moo, key)

            // 3. The right record among them, by name AND by artist.
            val albumRow = pickAlbum(albums, query, act)
                ?: return fail(
                    what,
                    // THE ROWS ARE PRINTED, because "not in your library" and
                    // "there it is and the match refused it" are the same
                    // sentence from the outside and are not the same bug.
                    "no row matched \"$album\" by \"$artist\" among: ${titles(albums)}",
                    "That record is not in your Roon library."
                )
            refused(request(moo, browseItem(albumRow, key)), expectList = true)
                ?.let { return fail(what, "opening the album: $it", it) }
            val albumScreen = load(moo, key)

            // 4. The album's action menu, then Queue within it.
            val menu = pickActionList(albumScreen)
                ?: return fail(
                    what,
                    "no action_list row on the album screen: ${titles(albumScreen)}",
                    "Roon offered no actions for that record."
                )
            // THE ZONE IS NAMED WHEN THE MENU IS OPENED, NOT ONLY WHEN THE
            // ACTION IS INVOKED. Roon decides which actions to offer from the
            // zone they would apply to, so a menu opened without one can come
            // back with no playback actions in it at all — which this would
            // then report as "Roon offered no Queue action", naming the wrong
            // cause. UNVERIFIED like everything else here, and recorded either
            // way by the note below.
            refused(
                request(moo, browseItem(menu, key).put("zone_or_output_id", ZoneRefIds.raw(zoneId))),
                expectList = true
            )?.let { return fail(what, "opening the actions: $it", it) }
            val actions = load(moo, key)

            val queue = pickQueueAction(actions)
                ?: return fail(
                    what,
                    "no action titled exactly \"Queue\" among: ${titles(actions)}",
                    "Roon offered no Queue action for that record."
                )

            // 5. Perform it, in the zone the card is about.
            //
            // AND READ WHAT ROON SAYS BACK. This reply used to be thrown away,
            // so a refusal — a zone that has gone, an action Roon would not
            // take — was reported to the page as "Added to the end of the
            // queue in Roon". A tap that lies about having worked is worse
            // than one that admits it did not: nobody goes looking for a bug
            // they have been told is not there.
            val done = request(
                moo,
                browseItem(queue, key).put("zone_or_output_id", ZoneRefIds.raw(zoneId))
            )
            refused(done, expectList = false)?.let { return fail(what, "invoking Queue: $it", it) }

            note("$what -> QUEUED${done.strOrNull("message")?.let { " ($it)" }.orEmpty()}")
            Outcome(true, "Added to the end of the queue in Roon.")
        } catch (e: Throwable) {
            // Throwable, not Exception: this runs on a request thread and a
            // class that fails to initialise throws an Error.
            val why = e.message ?: e.javaClass.simpleName
            return fail(what, "threw: $why", "Roon would not take that request.")
        }
    }

    private fun fail(what: String, why: String, detail: String): Outcome {
        note("$what -> $why")
        return Outcome(false, detail)
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
        /**
         * WHAT ROON SAID BACK, WHEN WHAT IT SAID BACK WAS NO.
         *
         * Every `browse` was fired and its answer dropped on the floor. Roon
         * does not answer a browse with a list unconditionally: `action` may be
         * `message` and `is_error` may be set — "Zone is not available", "This
         * is not available in your library", an `item_key` that has expired.
         * Ignoring that cost two things at once.
         *
         * A refusal PARTWAY DOWN the chain was followed by a `load` of whatever
         * list happened to still be open, so the reason finally reported was
         * whichever later step then failed — a diagnostic naming the wrong
         * cause, which this repo has already paid for once with Pitchfork.
         *
         * And the FINAL invoke, the one that actually does the queueing, was
         * never read at all. A refusal there reached the page as "Added to the
         * end of the queue in Roon". A tap that lies about having worked is
         * worse than one that admits it failed: nobody goes looking for a bug
         * they have been told is not there, which is very likely why this was
         * reported as "it hasn't been added" rather than as an error.
         *
         * [expectList] is the caller saying whether this step must produce rows
         * to go on with. The last one must not: Roon answers a performed action
         * with `action: "message"` and the words it would have shown on screen.
         */
        internal fun refused(reply: JSONObject, expectList: Boolean): String? {
            val message = reply.strOrNull("message")?.trim().orEmpty()
            if (reply.truthy("is_error")) return message.ifEmpty { "Roon refused that step." }
            if (!expectList) return null
            val action = reply.strOrNull("action")?.lowercase()
            // "list" is the only answer carrying rows. Anything else means the
            // rows this step was about to load belong to the PREVIOUS screen.
            if (action == "list") return null
            return message.ifEmpty { "Roon answered \"${action ?: "nothing"}\" where a list was expected." }
        }

        /**
         * `optBoolean` READS THE NUMBER 1 AS FALSE — the same org.json trap
         * `LmsClient.truthy` exists for, and `is_error` is exactly the kind of
         * field an encoder writes as 1.
         */
        private fun JSONObject.truthy(key: String): Boolean = when (val v = opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", ignoreCase = true) || v == "1"
            else -> false
        }

        private const val TAG = "RoonBrowse"
        private const val HIERARCHY = "search"

        /** Enough attempts to cover a session's worth of taps, and no more. */
        private const val MAX_NOTES = 12

        /** Roon pages its lists; a search's first hundred rows is plenty. */
        private const val PAGE = 100

        /**
         * WHAT GOES IN ROON'S SEARCH BOX.
         *
         * Not what the suggestion says. A suggestion comes from Deezer and
         * Deezer's copy of a record is whichever pressing it sells — "Ignition
         * (2008 Remaster)" — while the copy in somebody's library is called
         * "Ignition". So the search went out with words no record in the house
         * is named and Roon answered `action: "none"`. Reported with a
         * photograph of the page saying exactly that, and diagnosed by the
         * person who owns the library.
         *
         * Both folds already existed and neither is invented here:
         * [Normalize.stripEdition] was written for Pitchfork, whose review is
         * filed under the plain name, and [Normalize.primaryArtist] for the
         * search links, after a four-name credit was spent as one act. A
         * library search wants the shortest true form of both.
         */
        internal fun searchInput(album: String, artist: String): String =
            (Normalize.primaryArtist(artist) + " " + Normalize.stripEdition(album)).trim()

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
