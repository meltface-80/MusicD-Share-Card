package com.musicd.sharecard.discover

import com.musicd.sharecard.Log
import com.musicd.sharecard.library.Normalize
import com.musicd.sharecard.meta.CacheStore
import com.musicd.sharecard.meta.Similar
import com.musicd.sharecard.meta.RateGate
import com.musicd.sharecard.meta.TtlCache
import com.musicd.sharecard.strOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * NEW RECORDS, AND THE ONES BY ACTS THIS APP HAS ACTUALLY HEARD COME FIRST.
 *
 * Asked for as "new music based on listening", drawn as COVER ART and nothing
 * else — tap a sleeve and the links open. That last part is not a style choice,
 * it is the whole legal position: a title and an artist are facts, a sleeve
 * identifies the record the same way the card already does, and everything
 * written about it stays a LINK to whoever wrote it. Nobody's prose is
 * reproduced here, and there is no route that could.
 *
 * TWO SOURCES, AND THEY ANSWER DIFFERENT QUESTIONS.
 *
 *  1. ListenBrainz's fresh-releases window. ONE request returns every release
 *     in a date range, so the filtering against [PlayHistory] happens HERE
 *     rather than as a request per act — sixty acts would otherwise be sixty
 *     rate-limited MusicBrainz browses, which is a minute of waiting for a
 *     screen. The data is MusicBrainz's and the sleeves are the Cover Art
 *     Archive's, so rendering them as our own page is not a question.
 *  2. Deezer's editorial releases, which is not personal at all. It is what
 *     fills the screen on a FIRST RUN, when the history is empty and the
 *     honest answer to "based on your listening" is "I do not know you yet".
 *
 * NEITHER ENDPOINT HAS BEEN REACHED FROM WHERE THIS WAS WRITTEN. The proxy
 * here refuses both hosts — 403 on the CONNECT, checked rather than assumed —
 * so the shapes below are the documented ones and the parsing is deliberately
 * lenient: every field is looked for in more than one place, and anything
 * missing costs that row and not the screen. The socket is kept out of the
 * parsing so that a wire which differs is one function to correct rather than
 * a protocol to re-derive, and [attempts] says what happened. Treat the first
 * real run as the verification — the same posture as Lyrion and Roon, and for
 * the same reason.
 */
class NewMusic(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val history: PlayHistory,
    /** What the press has been reviewing. Null where the feeds are not wanted. */
    private val editorial: Editorial? = null,
    /**
     * ACTS LIKE THE ONES THIS HOUSE PLAYS, which is what makes this screen
     * broader than a list of your own artists releasing again.
     *
     * Null leaves the screen exactly as it was - seeded by the history alone -
     * so a host that does not pass one, and every test that does not need one,
     * behaves as before.
     */
    private val similar: Similar? = null,
    /** So a screenful of sleeves survives a restart as well as a tab switch. */
    private val store: CacheStore = CacheStore.NONE,
    /** Seams for the tests: neither wants a socket or a real calendar. */
    private val fetchText: (String) -> String? = { null },
    private val today: () -> String = { isoToday() }
) {

    /**
     * One record worth knowing about.
     *
     * [why] is what the screen prints under the sleeve, and it is the reason
     * this is not just a new-releases list: "because you played Slint" is a
     * different promise from "out this week", and a screen that cannot tell
     * you which it is has not earned the word "listening".
     */
    /**
     * An act whose new record may appear, and who to credit it to.
     *
     * [because] is the act the USER actually played — for a widened seed that
     * is somebody else, and it is what the tile says. Keeping it on the seed
     * rather than working it out later is what lets the screen say "Similar to
     * Ty Segall" instead of a bare "out this week", which is the difference
     * between this screen meaning its name and being a release calendar.
     */
    data class Seed(val artist: String, val because: String, val played: Boolean)

    data class Pick(
        val artist: String,
        val album: String,
        val released: String,
        val art: String?,
        val why: String,
        val heard: Boolean,
        /**
         * The review this record came from, when it came from one.
         *
         * A LINK AND NOTHING ELSE — no headline, no excerpt, no snippet of the
         * piece. The record is identified out of the feed and the writing stays
         * with whoever wrote it. See [Editorial] for why that line is where it
         * is.
         */
        val readAt: String? = null,
        val readAtName: String? = null
    )

    /*
     * TWO SHELVES, BECAUSE THEY GO STALE FOR DIFFERENT REASONS.
     *
     * The SCREEN is keyed on the history and turns over in an hour: it is a
     * this-week question and the feeds behind it are published hourly. It is
     * held in memory only — it is one answer, it is cheap to rebuild once a
     * restart has happened anyway, and writing a whole screenful down to save
     * the first look after a reboot is not the trade the others make.
     *
     * A SLEEVE does not change. Once Deezer has told us where the cover for a
     * record is, that is the answer for as long as the record exists, so it is
     * written to disk and kept for a week — which is what stops the same
     * twelve lookups being paid again tomorrow.
     */
    private val cache = TtlCache<String, List<Pick>>(SCREEN_TTL_MS, 4)

    private val sleeves = TtlCache<String, String>(
        SLEEVE_TTL_MS, 256,
        TtlCache.Persist(store, "sleeve", { it }, { it })
    )

    private val dzGate = RateGate(DZ_INTERVAL_MS)

    // ------------------------------------------------------------ the wire

    /**
     * THE WHOLE SCREEN, REMEMBERED. Two tabs and a page reload are one answer.
     *
     * Opening Discover cost a ListenBrainz window, two RSS feeds, a Deezer
     * list and a sleeve lookup per record — every time, including tapping
     * Playing and tapping back. That is the empty-sweep fault this repo has
     * already paid for once: an answer that was expensive to get and was not
     * kept. Refresh still forces, which is the bargain every source here
     * makes.
     *
     * The KEY is the history, folded. "Based on your listening" changes when
     * the listening does, so a record played since the last look reshapes the
     * screen at once rather than at the end of a TTL — and a house that has
     * played nothing new reads its screen off the shelf.
     */
    fun picks(limit: Int = WANTED): List<Pick> {
        val heard = history.recent()
        val cached = cache.get(key(heard, limit)) { compute(heard, limit) }
        return cached.take(limit)
    }

    /** What [picks] would answer without going near the network. */
    fun cachedPicks(limit: Int = WANTED): List<Pick>? =
        cache.peek(key(history.recent(), limit))?.take(limit)

    /** Throw the shelf away, so the next look is a fresh one. See Refresh. */
    fun forget() {
        cache.clear()
        editorial?.forget()
    }

    private fun compute(heard: List<PlayHistory.Heard>, limit: Int): List<Pick> {
        val out = LinkedHashMap<String, Pick>()

        for (pick in fromListenBrainz(heard)) out.putIfAbsent(key(pick), pick)
        note("listenbrainz -> ${out.size} by acts you have heard")

        /*
         * THE ORDER IS THE FEATURE, AND IT RUNS FROM MOST PERSONAL TO LEAST.
         *
         * 1. New records by acts this house actually plays. Nothing displaces
         *    these — they are the only part that earns the word "listening".
         * 2. What the press has just reviewed. Not personal, but it is
         *    somebody's judgement rather than a release calendar, which is
         *    what "what's good right now" was asking for.
         * 3. Deezer's new-release list, which is the same for everybody and is
         *    there so a first run is not an empty screen.
         *
         * Each only fills what the one above it left, so a week with plenty of
         * (1) never shows (3) at all.
         */
        if (out.size < limit) {
            var added = 0
            for (pick in fromEditorial()) {
                if (out.size >= limit) break
                if (out.putIfAbsent(key(pick), pick) == null) added++
            }
            note("editorial -> $added reviewed lately")
        }

        if (out.size < limit) {
            var added = 0
            for (pick in fromDeezer()) {
                if (out.size >= limit) break
                if (out.putIfAbsent(key(pick), pick) == null) added++
            }
            note("deezer -> $added new this week")
        }
        return withSleeves(out.values.toList())
    }

    private fun fromListenBrainz(heard: List<PlayHistory.Heard>): List<Pick> {
        val url = "$LB/1/explore/fresh-releases/?release_date=${today()}" +
            "&days=$WINDOW_DAYS&past=true&future=false"
        val body = get(url) ?: run { note("listenbrainz: no answer"); return emptyList() }
        val seeds = seedsFrom(heard)
        return runCatching { parseFreshReleases(body, seeds) }
            .onFailure { note("listenbrainz: unreadable answer (${it.javaClass.simpleName})") }
            .getOrDefault(emptyList())
    }

    /**
     * WHOSE NEW RECORDS MAY APPEAR: the acts this house plays, AND acts like
     * them.
     *
     * Reported from the field, and it is the fair reading of what this screen
     * was: "I recently listened to Ty Segall — it shouldn't always return
     * another Ty Segall album. It appears to do this with all artists listened
     * to recently. Needs to be broader — same style/genre." That was exactly
     * the design: the fresh-releases window was matched against the HISTORY, so
     * the only thing that could ever appear was an act already in it. A new
     * record by somebody you play is worth knowing about, but it cannot be the
     * whole of "discover".
     *
     * **THE COST IS PER ACT, NOT PER RECORD, AND THAT IS THE WHOLE REASON THIS
     * IS AFFORDABLE.** The window itself is still ONE request however many
     * seeds there are — widening the filter does not widen the fetch. Each
     * lookup is [Similar]'s, which is rate-gated and written to disk for a
     * week, and only the most recent [SEED_ACTS] acts are expanded, so a cold
     * screen pays a bounded handful and a warm one pays nothing. Expanding all
     * sixty would be the mistake this file already avoids elsewhere: sixty
     * rate-limited lookups is a minute of waiting for a screen.
     */
    internal fun seedsFrom(heard: List<PlayHistory.Heard>): List<Seed> {
        val played = heard.filter { it.artist.isNotBlank() }
        val seeds = LinkedHashMap<String, Seed>()
        for (act in played) seeds.putIfAbsent(Normalize.text(act.artist), Seed(act.artist, act.artist, true))

        val engine = similar ?: return seeds.values.toList().also {
            note("seeds -> ${it.size} act(s) played; no similar-artist lookup wired in")
        }
        var widened = 0
        for (act in played.take(SEED_ACTS)) {
            // No MusicBrainz id is held for a played record, so this is
            // Deezer's related-artists list in practice — which is the half
            // that answers in the field anyway.
            val like = runCatching { engine.forArtist(act.artist, null) }
                .onFailure { note("similar(${act.artist}): ${it.javaClass.simpleName}") }
                .getOrDefault(emptyList())
            for (other in like) {
                if (other.name.isBlank()) continue
                if (seeds.putIfAbsent(Normalize.text(other.name), Seed(other.name, act.artist, false)) == null) {
                    widened++
                }
            }
        }
        note("seeds -> ${played.size} act(s) played, widened by $widened like them")
        return seeds.values.toList()
    }

    private fun fromEditorial(): List<Pick> {
        val source = editorial ?: return emptyList()
        return runCatching {
            source.recent().map {
                Pick(
                    artist = it.artist,
                    album = it.album,
                    released = "",
                    art = it.art,
                    why = "Reviewed by ${it.source}",
                    heard = false,
                    readAt = it.url,
                    readAtName = it.source
                )
            }
        }.onFailure { note("editorial: ${it.javaClass.simpleName}") }.getOrDefault(emptyList())
    }

    private fun fromDeezer(): List<Pick> {
        val body = get("$DZ/editorial/0/releases?limit=$WANTED")
            ?: run { note("deezer: no answer"); return emptyList() }
        return runCatching { parseDeezerReleases(body) }
            .onFailure { note("deezer: unreadable answer (${it.javaClass.simpleName})") }
            .getOrDefault(emptyList())
    }

    /**
     * A REAL SLEEVE FOR EVERY RECORD THAT CAN HAVE ONE.
     *
     * Reported from the first run as "no album artwork", and there were two
     * halves to it. NME's feed carries no image this can use, so those tiles
     * drew the placeholder; Pitchfork's carried one this app then failed to
     * fetch, so that tile drew a broken image. Both are the same mistake
     * underneath: THE PICTURE BESIDE AN ARTICLE IS NOT THE RECORD'S SLEEVE.
     * It is whatever the publisher put at the top of the page — a press shot,
     * a live photo, a collage — and a press shot under an album title is a
     * confident wrong answer, which this app has a standing rule against.
     *
     * So the sleeve is resolved from the RECORD, out of the same Deezer API
     * the new-release list already comes from. The feed's own image is kept
     * only as the fallback, for a record Deezer does not carry.
     *
     * THE COST IS A REQUEST PER RECORD AND IT IS PAID ONCE. Rate-gated like
     * every other Deezer call here, cached on disk for a week, and behind the
     * screen cache above — so a cold first look pays about three seconds and
     * nothing after it pays anything.
     */
    private fun withSleeves(picks: List<Pick>): List<Pick> {
        var found = 0
        val out = picks.map { pick ->
            val fromRecord = sleeveFor(pick.artist, pick.album)
            if (fromRecord != null) found++
            if (fromRecord == null) pick else pick.copy(art = fromRecord)
        }
        note("sleeves -> $found of ${picks.size} resolved from the record")
        return out
    }

    /**
     * Deezer's cover for one record, or null.
     *
     * THE SEARCH IS LOOSE AND THE CHECK IS STRICT, which is the shape every
     * lookup in this app has: a quoted field search finds nothing when the
     * spelling differs by a word, so the query is the two names plainly and
     * the ROWS are what get checked. Both the act and the title have to
     * overlap through [Normalize.namesOverlap] — the first row is not the
     * answer, the same lesson `QobuzAlbum.pick` and the Deezer artist search
     * are both already written from. A wrong sleeve is worse than none: it
     * says the app knows which record this is when it does not.
     *
     * The EMPTY STRING is how "we looked and Deezer has nothing" is
     * remembered, because [TtlCache] cannot hold a null and a record nobody
     * carries must not be looked up again on every visit.
     */
    private fun sleeveFor(artist: String, album: String): String? {
        if (artist.isBlank() || album.isBlank()) return null
        val found = sleeves.get(sleeveKey(artist, album)) {
            val query = Normalize.primaryArtist(artist) + " " + Normalize.stripEdition(album)
            val body = dzGate.run { get("$DZ/search/album?limit=$SLEEVE_ROWS&q=" + urlEncode(query)) }
            if (body == null) {
                note("sleeve($artist - $album) -> no answer")
                ""
            } else {
                runCatching { pickSleeve(body, artist, album) }
                    .onFailure { note("sleeve($artist - $album) -> unreadable (${it.javaClass.simpleName})") }
                    .getOrDefault("")
            }
        }
        return found.takeIf { it.isNotEmpty() }
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

    // --------------------------------------------------------- diagnostics

    /** The last few lookups, for /api/debug. An empty screen says nothing. */
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
        private const val TAG = "NewMusic"
        private const val MAX_NOTES = 8

        private const val LB = "https://api.listenbrainz.org"
        private const val DZ = "https://api.deezer.com"

        /** The Cover Art Archive, where a MusicBrainz release's sleeve lives. */
        private const val CAA = "https://archive.org/download"

        /** How many sleeves the screen holds. Three columns, four rows. */
        const val WANTED = 12

        /**
         * How far back "new" reaches.
         *
         * Long enough that a household which opens this once a fortnight still
         * sees something, short enough that it is not a back catalogue. The
         * window is asked for in one request either way, so the length costs
         * nothing.
         */
        const val WINDOW_DAYS = 21

        /**
         * How many recently played acts get widened into acts like them.
         *
         * Bounded because each one is a rate-gated lookup on a cold cache, and
         * the history holds sixty. The most recent handful is what "recently
         * listened to" means anyway.
         */
        const val SEED_ACTS = 8

        /**
         * At most this many picks by acts already played, out of [WANTED].
         *
         * Not zero: a new record by somebody this house plays is the most
         * relevant thing the screen can hold. Not unlimited: that is what made
         * every tile another album by the act just played.
         */
        const val MAX_SAME_ACT = 4

        /** How long a screenful of "what is new" stays true. The feeds are hourly. */
        const val SCREEN_TTL_MS = 60L * 60_000L

        /** A record's cover does not change, so this is about the shelf, not the fact. */
        const val SLEEVE_TTL_MS = 7L * 24 * 60 * 60_000L

        /** Deezer asks for a pause between calls, the same one Similar uses. */
        const val DZ_INTERVAL_MS = 250L

        /** How many search rows to consider before giving up on a sleeve. */
        const val SLEEVE_ROWS = 8

        private fun key(p: Pick) =
            Normalize.text(p.artist) + "|" + Normalize.text(p.album)

        /**
         * The screen's cache key: the acts this house has played, folded.
         *
         * Not a constant, because "based on your listening" must follow the
         * listening. A record played since the last look changes this string
         * and the screen is rebuilt at once, rather than staying wrong until
         * an hour is up.
         */
        internal fun key(heard: List<PlayHistory.Heard>, limit: Int): String =
            limit.toString() + "/" + heard.joinToString(",") { Normalize.text(it.artist) }

        internal fun sleeveKey(artist: String, album: String): String =
            Normalize.text(artist) + "|" + Normalize.text(album)

        /**
         * The cover off a Deezer album search, matched on BOTH names.
         *
         * Biggest first — `cover_xl` down to `cover` — because a sleeve at any
         * size beats a blank tile, and a row whose names do not match is
         * skipped rather than taken, however high it ranks.
         */
        internal fun pickSleeve(body: String, artist: String, album: String): String {
            val rows = JSONObject(body).optJSONArray("data") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val title = row.strOrNull("title") ?: continue
                val act = row.optJSONObject("artist")?.strOrNull("name") ?: continue
                if (!Normalize.namesOverlap(album, title)) continue
                if (!Normalize.namesOverlap(artist, act)) continue
                /*
                 * `cover_big` FIRST, NOT `cover_xl`, AND THAT IS ABOUT THE
                 * CACHE. Deezer's big is 500px and its xl is 1000px; a tile
                 * is about 115px on a phone and the record a tile opens onto
                 * is 240px, so xl is four times the bytes for a picture
                 * nothing here draws that large. Twelve of them at once is
                 * what made the art proxy's shelf too small to hold a screen
                 * — see ArtProxy. The others are the fallbacks, because a
                 * sleeve at any size beats a blank tile.
                 */
                val cover = row.strOrNull("cover_big")
                    ?: row.strOrNull("cover_xl")
                    ?: row.strOrNull("cover_medium")
                    ?: row.strOrNull("cover")
                if (!cover.isNullOrBlank()) return cover
            }
            return ""
        }

        private fun urlEncode(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8")

        internal fun isoToday(): String = java.time.LocalDate.now().toString()

        /**
         * ListenBrainz's fresh-releases payload, filtered to acts in [heard].
         *
         * THE FILTER IS THE FEATURE. The endpoint answers with every release in
         * the window — thousands — and what makes this "based on your
         * listening" is keeping the ones whose act this app has drawn a card
         * for. [Normalize.namesOverlap] is the same rule every other name check
         * here uses, so "Bjork" matches "Björk" and a stranger does not.
         *
         * LENIENT ON EVERY FIELD, because none of this has been seen on the
         * wire: the rows are looked for under `payload.releases` and then at
         * the root, and each name is looked for under more than one key. A row
         * missing what it needs is dropped; it never takes the screen with it.
         */
        internal fun parseFreshReleases(body: String, seeds: List<Seed>): List<Pick> {
            val root = JSONObject(body)
            val rows = root.optJSONObject("payload")?.optJSONArray("releases")
                ?: root.optJSONArray("releases")
                ?: JSONArray()
            val wanted = seeds.filter { it.artist.isNotBlank() }
            val played = ArrayList<Pick>()
            val like = ArrayList<Pick>()
            // ONE RECORD PER ACT. Three new Ty Segall releases in one window is
            // three tiles saying the same thing, which is half the complaint
            // this widening answers.
            val seen = HashSet<String>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val artist = row.strOrNull("artist_credit_name")
                    ?: row.strOrNull("artist_name") ?: continue
                val album = row.strOrNull("release_name")
                    ?: row.strOrNull("title") ?: continue
                if (artist.isBlank() || album.isBlank()) continue
                val match = wanted.firstOrNull { Normalize.namesOverlap(it.artist, artist) }
                    ?: continue
                if (!seen.add(Normalize.text(artist))) continue
                val pick = Pick(
                    artist = artist,
                    album = album,
                    released = row.strOrNull("release_date").orEmpty(),
                    art = coverArtUrl(row),
                    why = if (match.played) "Because you played ${match.because}"
                    else "Similar to ${match.because}",
                    /*
                     * `heard` MARKS THE STRONGEST CLAIM, and only a record by
                     * an act actually played earns it - the page styles that
                     * differently. A widened seed is an honest "similar to",
                     * not "you played this".
                     */
                    heard = match.played
                )
                if (match.played) played += pick else like += pick
            }
            /*
             * AN ACT YOU ALREADY PLAY MAY NOT FILL THE SCREEN.
             *
             * This is the reported complaint stated as a rule. A new record by
             * somebody you love is the single most relevant thing here, so it
             * leads — but capped, because a screen of nothing else is a release
             * calendar for a library you already have. What is left goes to
             * acts like them, and anything still unfilled falls through to the
             * press and then to Deezer exactly as before.
             */
            return played.take(MAX_SAME_ACT) + like + played.drop(MAX_SAME_ACT)
        }

        /**
         * The sleeve, from the Cover Art Archive.
         *
         * `caa_id` names the image and `caa_release_mbid` names the release it
         * belongs to, and BOTH are needed — the id alone builds a URL that
         * 404s. A release with no art is kept WITHOUT a sleeve rather than
         * dropped: the screen draws a placeholder, and a record worth knowing
         * about does not stop being one because nobody has uploaded the cover.
         */
        internal fun coverArtUrl(row: JSONObject): String? {
            val mbid = row.strOrNull("caa_release_mbid") ?: return null
            val id = row.opt("caa_id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                ?: return null
            // Plain letters, digits and dashes only: both halves go straight
            // into a path, and a slash or a dot walking out of it is the same
            // hazard the Lyrion cover id has a rule about.
            if (!SAFE.matches(mbid) || !SAFE.matches(id)) return null
            return "$CAA/mbid-$mbid/mbid-$mbid-${id}_thumb500.jpg"
        }

        private val SAFE = Regex("[A-Za-z0-9-]{1,64}")

        /**
         * Deezer's editorial releases — new this week, the same for everybody.
         *
         * `cover_xl` down to `cover` because the bigger ones are not always
         * there, and a sleeve at any size beats a blank tile.
         */
        internal fun parseDeezerReleases(body: String): List<Pick> {
            val rows = JSONObject(body).optJSONArray("data") ?: JSONArray()
            val out = ArrayList<Pick>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val album = row.strOrNull("title") ?: continue
                val artist = row.optJSONObject("artist")?.strOrNull("name") ?: continue
                if (artist.isBlank() || album.isBlank()) continue
                out += Pick(
                    artist = artist,
                    album = album,
                    released = row.strOrNull("release_date").orEmpty(),
                    art = row.strOrNull("cover_xl")
                        ?: row.strOrNull("cover_big")
                        ?: row.strOrNull("cover_medium")
                        ?: row.strOrNull("cover"),
                    why = "New this week",
                    heard = false
                )
            }
            return out
        }
    }
}
