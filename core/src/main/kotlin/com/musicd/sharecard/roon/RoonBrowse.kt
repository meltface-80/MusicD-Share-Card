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
        val what = "$artist \u2014 $album -> ${ZoneRefIds.raw(zoneId)}"
        val moo = socket() ?: return fail(what, "not connected to Roon", "Not connected to Roon.")
        val zone = ZoneRefIds.raw(zoneId)
        return try {
            /*
             * A LADDER OF SEARCHES, NOT ONE SEARCH.
             *
             * Reported as a record that is in the library and would not queue,
             * with "this must work" attached — and the search is the only step
             * whose input this app invents. Roon's box takes one string, and
             * which string finds a record is not something that can be settled
             * from here, so the app tries the forms in order of how specific
             * they are and stops at the first that resolves. Every rung is
             * recorded, so one /api/debug says which one worked or that none
             * did.
             *
             * This is the same shape as the Pitchfork lookup — constructed
             * URL, then the edition stripped, then the feed — and for the same
             * reason: each rung costs a request only when the one before it
             * found nothing.
             */
            var found: JSONObject? = null
            var lastWhy = "nothing was tried"
            for (input in searchQueries(album, artist)) {
                val key = sessionKey()
                val outcome = findAlbum(moo, key, input, album, artist)
                if (outcome.row != null) {
                    note("$what -> \"$input\" matched ${outcome.row.str("title")} / ${outcome.row.strOrNull("subtitle").orEmpty()}")
                    found = outcome.row
                    return queueFrom(moo, key, outcome.row, zone, what)
                }
                note("$what -> \"$input\": ${outcome.why}")
                lastWhy = outcome.why
            }
            if (found == null) {
                return Outcome(false, "That record is not in your Roon library. ($lastWhy)")
            }
            Outcome(false, lastWhy)
        } catch (e: Throwable) {
            // Throwable, not Exception: this runs on a request thread and a
            // class that fails to initialise throws an Error.
            val why = e.message ?: e.javaClass.simpleName
            return fail(what, "threw: $why", "Roon would not take that request.")
        }
    }

    /** One search, walked as far as a row that really is this record. */
    private data class Found(val row: JSONObject?, val why: String)

    private fun findAlbum(
        moo: MooSocket,
        key: String,
        input: String,
        album: String,
        artist: String
    ): Found {
        val search = request(
            moo,
            JSONObject()
                .put("hierarchy", HIERARCHY)
                .put("input", input)
                .put("pop_all", true)
                .put("multi_session_key", key)
        )
        refused(search, expectList = true)?.let { return Found(null, "search refused: $it ${raw(search)}") }
        val results = load(moo, key)

        /*
         * ROON GROUPS A SEARCH UNDER HEADINGS, AND SOMETIMES IT DOES NOT.
         *
         * The Albums heading is the one to open — an Artists row drills into a
         * discography and a Tracks row into one song. But a search that
         * returns the records THEMSELVES has no heading to open, and giving up
         * there is how a library that holds the record answers "not in your
         * library". So the rows in hand are tried as albums too.
         */
        val rows = pickAlbumsHeading(results)?.let { heading ->
            val opened = request(moo, browseItem(heading, key))
            refused(opened, expectList = true)?.let { return Found(null, "opening Albums refused: $it ${raw(opened)}") }
            load(moo, key)
        } ?: results

        pickAlbum(rows, album, artist)?.let { return Found(it, "matched") }
        return Found(null, "no row matched \"$album\" by \"$artist\" among: ${titles(rows)}")
    }

    /** From the album's row to the Queue action, in the zone the card is about. */
    private fun queueFrom(
        moo: MooSocket,
        key: String,
        albumRow: JSONObject,
        zone: String,
        what: String
    ): Outcome {
        val opened = request(moo, browseItem(albumRow, key).put("zone_or_output_id", zone))
        refused(opened, expectList = true)
            ?.let { return fail(what, "opening the album: $it ${raw(opened)}", it) }
        val albumScreen = load(moo, key)

        // THE ZONE IS NAMED WHEN THE MENU IS OPENED, NOT ONLY WHEN THE ACTION
        // IS INVOKED. Roon decides which actions to offer from the zone they
        // would apply to, so a menu opened without one can come back with no
        // playback actions in it at all — which this would then report as
        // "Roon offered no Queue action", naming the wrong cause.
        val menu = pickActionList(albumScreen)
            ?: return fail(
                what,
                "no action_list row on the album screen: ${titles(albumScreen)}",
                "Roon offered no actions for that record."
            )
        val actionsReply = request(moo, browseItem(menu, key).put("zone_or_output_id", zone))
        refused(actionsReply, expectList = true)
            ?.let { return fail(what, "opening the actions: $it ${raw(actionsReply)}", it) }
        val actions = load(moo, key)

        val queue = pickQueueAction(actions)
            ?: return fail(
                what,
                "no action titled exactly \"Queue\" among: ${titles(actions)}",
                "Roon offered no Queue action for that record."
            )

        // AND READ WHAT ROON SAYS BACK. This reply used to be thrown away, so
        // a refusal — a zone that has gone, an action Roon would not take —
        // was reported to the page as "Added to the end of the queue in Roon".
        val done = request(moo, browseItem(queue, key).put("zone_or_output_id", zone))
        refused(done, expectList = false)
            ?.let { return fail(what, "invoking Queue: $it ${raw(done)}", it) }

        note("$what -> QUEUED${done.strOrNull("message")?.let { " ($it)" }.orEmpty()}")
        return Outcome(true, "Added to the end of the queue in Roon.")
    }

    /**
     * What Roon actually sent, trimmed.
     *
     * THE NOTES USED TO CARRY THIS APP'S READING OF THE REPLY AND NOT THE
     * REPLY. When the reading is the thing that is wrong — a field named
     * differently, a shape nobody here has seen, because no Core is reachable
     * from where this is written — an interpretation is exactly the wrong
     * thing to be shown. One screenshot of /api/debug should be enough to
     * settle any of this, and it only is if the raw answer is in it.
     */
    private fun raw(reply: JSONObject): String = "<- " + reply.toString().take(400)

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
         * EVERY FORM WORTH PUTTING IN THAT BOX, MOST SPECIFIC FIRST.
         *
         * Reported as a record that IS in the library and would not queue,
         * with "this must work" attached. The search is the one step whose
         * input this app invents, and which string finds a record in somebody
         * else's library is not a thing that can be settled from here — no
         * Roon Core is reachable from where this is written. So rather than
         * betting the feature on one guess, it tries the forms in order and
         * stops at the first that resolves.
         *
         * The rungs, and why each earns its request:
         *
         *   1. act + album, edition stripped — the ordinary case, and the one
         *      that already fixed "Ignition (2008 Remaster)".
         *   2. the album alone — an artist name in the box is extra words to
         *      fail on, and Roon's own search matches a title perfectly well
         *      without one. The artist is still checked on the ROWS, so this
         *      is not a looser match, only a looser question.
         *   3. act + album exactly as the suggestion spelled it — the edition
         *      strip is narrow on purpose and cannot know every shape, and a
         *      record genuinely titled with a bracket ("Zooropa (Deluxe)" as
         *      the library's own name) is found by asking for it whole.
         *
         * Deduplicated and in order, so a plain record costs exactly one
         * request — the same bargain the Pitchfork ladder makes.
         */
        internal fun searchQueries(album: String, artist: String): List<String> {
            val act = Normalize.primaryArtist(artist)
            val plain = Normalize.stripEdition(album)
            return listOf(
                "$act $plain",
                plain,
                "$act $album",
                album
            ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }

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
         * BOTH HALVES ARE CHECKED FIRST, and that is still the answer this
         * prefers. Roon's search is fuzzy and will happily return a
         * compilation, a live album or another act's record of the same name —
         * and what is at stake is what goes into somebody's queue.
         * [Normalize.namesOverlap] is the same rule the Wikipedia and Deezer
         * lookups use, so a record qualified on the right ("Spiderland
         * (Remastered)") still matches and a stranger's does not.
         *
         * BOTH SIDES ARE FOLDED, because the two names come from different
         * places. The row is the LIBRARY'S spelling and the album is
         * DEEZER'S — one may carry an edition the other does not, and a
         * four-name credit on this side has already broken a search once.
         *
         * A ROW WITH NO SUBTITLE AT ALL IS ACCEPTED ON ITS TITLE, and that is
         * as far as the loosening goes. Roon's subtitle is not guaranteed to
         * be the artist — a box set, a soundtrack, something filed under
         * Various Artists — and refusing a row that simply does not say turned
         * a library which HOLDS the record into "not in your Roon library".
         *
         * BUT A SUBTITLE THAT NAMES SOMEBODY ELSE STILL REFUSES THE ROW, and
         * the test for it is the pair this repo has already been burned by:
         * "Cult" by To/Die/For against Static-X's record of the same name. A
         * blank subtitle contradicts nothing; a wrong one contradicts
         * everything, and putting a stranger's record in somebody's queue is
         * the one failure this feature must never have. Exactly one such row,
         * too — two untitled candidates is a coin toss, and no queue beats the
         * wrong record.
         */
        internal fun pickAlbum(items: JSONArray, album: String, artist: String): JSONObject? {
            val title = Normalize.stripEdition(album)
            val act = Normalize.primaryArtist(artist)
            val rows = items.objects().filter { it.strOrNull("item_key") != null }
            val byTitle = rows.filter {
                Normalize.namesOverlap(Normalize.stripEdition(it.str("title")), title)
            }
            byTitle.firstOrNull { row ->
                act.isBlank() || Normalize.mentions(row.str("subtitle"), act)
            }?.let { return it }
            // Nothing named the act. A row that names NOBODY may still be it;
            // a row that names somebody else may not.
            return byTitle.singleOrNull { it.str("subtitle").isBlank() }
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
