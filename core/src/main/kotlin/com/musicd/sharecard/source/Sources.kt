package com.musicd.sharecard.source

import com.musicd.sharecard.Log
import com.musicd.sharecard.library.Normalize

/**
 * Every source, and the one answer the card needs from them.
 *
 * The selection rule is the same one the Sonos-only version used, widened to
 * cross sources — because "what is playing" does not respect which protocol
 * happens to know about it:
 *
 *   1. the zone the user picked, if it is still playing something;
 *   2. any zone that is PLAYING, asked source by source;
 *   3. the picked zone, playing or not;
 *   4. any zone with metadata at all, so a paused room still makes a card;
 *   5. nothing, and the page says so.
 *
 * THAT LADDER IS FOR "WHATEVER'S PLAYING" AND FOR NOTHING ELSE. Naming a zone
 * goes through [inZone], which asks that room and answers for that room —
 * silence included. The ladder used to run for a named zone too, and the
 * result was a card headed "Playing in Stereo Fives · via Roon" while the
 * picker said WiiM Pro Plus: the WiiM was idle, rung 2 found a room that was
 * not, and the answer was about somewhere else entirely. Reported from the
 * field, and it made /api/debug lie in the same breath — every zone in the
 * report showed the same record, because the report asks per zone too.
 *
 * Each zone is independent. A room that is not playing says so.
 *
 * SOURCES ARE ASKED IN ORDER AND THE ORDER IS DELIBERATE. Roon first, because
 * when Roon is playing to a speaker it is the only one of the two that knows
 * what the record is — the speaker sees a stream with a session id where the
 * title should be.
 */
class Sources(private val sources: List<Source>) {

    /** The zone the user last chose, so a refresh does not wander. */
    @Volatile
    var preferredZoneId: String? = null

    /**
     * Which zones are switched on, from the settings file.
     *
     * A property rather than a constructor argument because the answer changes
     * while the app runs — somebody enables a room and the next request must
     * see it, without rebuilding every source and re-running discovery.
     *
     * It defaults to letting everything through so that a test, and any caller
     * that has no settings, behaves exactly as this class did before rooms
     * became opt-in.
     */
    @Volatile
    var zoneFilter: (String) -> Boolean = { true }

    fun start() = sources.forEach { source ->
        runCatching { source.start() }
            .onFailure { Log.w(TAG, "${source.name} would not start: ${it.message}", it) }
    }

    fun stop() = sources.forEach { runCatching { it.stop() } }

    fun refresh() = sources.forEach { source ->
        runCatching { source.refresh() }
            .onFailure { Log.w(TAG, "${source.name} would not refresh: ${it.message}", it) }
    }

    /**
     * WHICH ZONES THIS APP MAY ANSWER ABOUT, which is not every zone it can
     * see.
     *
     * Rooms are opt-in: a device is discovered, listed in Settings, and does
     * nothing at all until somebody asks for it. So everything downstream of
     * this — the picker, the chooser grid, the fallback ladder, `anyZones` —
     * inherits the filter by inheriting this one function, and there is no
     * second place that has to remember. [allZones] is the unfiltered list and
     * exists for exactly one caller: the settings screen, which has to offer
     * the rooms that are switched off.
     */
    fun zones(): List<ZoneRef> = allZones().filter { zoneFilter(it.id) }

    /** Every zone every source can see, switched on or not. */
    fun allZones(): List<ZoneRef> = sources.flatMap { source ->
        runCatching { source.zones() }
            .onFailure { Log.w(TAG, "${source.name} would not list zones: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** Every host any source may have art fetched from. */
    fun artHosts(): Set<String> = sources.flatMapTo(HashSet()) {
        runCatching { it.artHosts() }.getOrDefault(emptyList())
    }

    fun diagnostics(): List<String> = sources.flatMap { source ->
        val lines = runCatching { source.diagnostics() }
            .getOrElse { listOf("threw: ${it.javaClass.simpleName}: ${it.message}") }
        listOf("${source.name}:") + lines.map { "  $it" }
    }

    /** Anything the user has to do, from any source. */
    fun notices(): List<String> = sources.mapNotNull { source ->
        runCatching { source.notice() }.getOrNull()
    }

    /** True when at least one source can see something. */
    fun anyZones(): Boolean = zones().isNotEmpty()

    /**
     * How much of a record an answer actually describes.
     *
     * THIS IS WHAT "ASK WHOEVER KNOWS" ACTUALLY MEANS, and the ordering of
     * sources alone was not enough to deliver it. When Roon plays to a Sonos
     * speaker BOTH sources see that room and both say "playing" — so a rule
     * that returned the first playing answer returned whichever was asked
     * first, and if the user had picked the Sonos zone that was a card headed
     * with a session id while Roon sat there knowing the album.
     *
     * Ranking on the answer rather than on the source also means this does not
     * depend on recognising a hash. `Didl.looksLikeStreamId` catches the shapes
     * it knows; a source that reports some other kind of rubbish still loses to
     * one that reports an album and an artist.
     */
    internal fun quality(playing: Playing): Int = when {
        playing.album.isNotEmpty() && playing.artist.isNotEmpty() -> 3
        playing.artist.isNotEmpty() -> 2
        playing.album.isNotEmpty() -> 1
        playing.track.isNotEmpty() -> 0
        else -> -1
    }

    fun nowPlaying(preferId: String? = preferredZoneId): Playing? {
        // Filtered HERE and not only below, because the fast path a few lines
        // down returns `chosen` outright — so a switched-off room asked for by
        // id would come back having skipped every other check.
        val chosen = preferId?.takeIf { zoneFilter(it) }?.let { ask(it) }

        // THE FAST PATH, and it is narrow on purpose: the chosen room is
        // playing AND it told us both the album and the artist. There is
        // nothing a second source could add, so nothing else is disturbed.
        if (chosen != null && chosen.state.isPlaying && quality(chosen) == FULL) return chosen

        // Otherwise every source gets a say, and the best answer wins rather
        // than the first one.
        val candidates = ArrayList<Playing>()
        chosen?.let { candidates += it }
        for (source in sources) {
            val playing = runCatching { source.nowPlaying(null) }
                .onFailure { Log.w(TAG, "${source.name} would not answer: ${it.message}") }
                .getOrNull() ?: continue
            if (candidates.none { it.zoneId == playing.zoneId && it.source == playing.source }) {
                candidates += playing
            }
        }

        /*
         * ONE PICK PER SOURCE IS NOT ENOUGH ONCE ROOMS ARE OPT-IN.
         *
         * `source.nowPlaying(null)` answers with that source's OWN best room,
         * which may be one that is switched off — and dropping it would then
         * lose the whole source, including an enabled room beside it that is
         * playing. So the switched-off answers go, and every enabled room the
         * sources did not volunteer is asked directly.
         *
         * The cost is bounded by how many rooms somebody has actually turned
         * on, and `rooms()` already pays exactly this for the chooser grid.
         */
        candidates.retainAll { zoneFilter(it.zoneId) }
        for (zone in zones()) {
            if (candidates.any { it.zoneId == zone.id }) continue
            ask(zone.id)?.let { candidates += it }
        }
        if (candidates.isEmpty()) return null

        // Playing beats paused; a fuller answer beats a thinner one; and on a
        // tie the source order decides, which is why Roon is listed first.
        val best = candidates
            .filter { quality(it) >= 0 }
            .minWithOrNull(
                compareBy(
                    { if (it.state.isPlaying) 0 else 1 },
                    { -quality(it) },
                    { sources.indexOfFirst { s -> s.name == it.source }.let { i -> if (i < 0) 99 else i } }
                )
            )
        if (best == null) {
            // Every answer described nothing — a transport that is "playing"
            // with no album, no artist and no title. Returning one of those
            // would draw a blank card that looks like the app working. The
            // page says nothing is playing, which is the truth.
            Log.i(TAG, "${candidates.size} answer(s), none of them describing a record")
            return null
        }
        if (chosen != null && best.source != chosen.source) {
            Log.i(
                TAG,
                "${best.source} describes ${best.zoneName} better than ${chosen.source} did"
            )
        }
        return best
    }

    /**
     * Every room and what each one is playing, for the chooser.
     *
     * ONE ROUND TRIP PER ROOM, and that is the honest cost of the question.
     * "What is on in the house" answered as one card could stop at the first
     * room that was playing; answered as a grid it has to ask them all. It is
     * still asked ONLY when somebody opens the page or presses Refresh — see
     * the rule against polling, which this does not weaken.
     *
     * TWO SOURCES CAN SEE ONE ROOM, and left alone that draws two tiles for
     * one piece of music. Roon playing to a Sonos speaker is the case: both
     * say "playing", and the Sonos side reports a session id where the title
     * should be. So rooms that fold to the same NAME collapse to one, and the
     * survivor is chosen by the same rule the ladder uses — playing first,
     * then the fuller answer, then source order, which is why Roon wins and
     * the tile says the album rather than a hash.
     *
     * WHAT IT CANNOT DO is spot the same speaker under two DIFFERENT names —
     * a Roon zone called "Study" against a Sonos room called "Office" is two
     * tiles, and no information here says otherwise. That is the right way to
     * be wrong: a spare tile is visible and tappable, where wrongly merging
     * two real rooms would hide one of them.
     */
    fun rooms(): List<Room> {
        val all = zones().map { Room(it, inZone(it.id)) }
        return all
            .groupBy { Normalize.text(it.zone.name) }
            .map { (_, sharing) -> sharing.minWith(bestFirst) }
            .sortedBy { Normalize.text(it.zone.name) }
    }

    /**
     * The ladder's tie-break, as a comparator over rooms. Lowest wins.
     *
     * It is built from [quality] and from the REAL position of each source in
     * [sources], not from the source's name — Roon is first because it is
     * listed first, and an alphabetical tie-break would only agree with that
     * by accident.
     */
    private val bestFirst: Comparator<Room> = compareBy(
        { room: Room -> if (room.playing?.state?.isPlaying == true) 0 else 1 },
        { room: Room -> -(room.playing?.let { quality(it) } ?: -1) },
        { room: Room ->
            sources.indexOfFirst { it.name.equals(room.zone.source, ignoreCase = true) }
                .let { if (it < 0) 99 else it }
        }
    )

    /**
     * What is playing IN ONE ROOM, and nothing else.
     *
     * No ladder, no fallback, no second source: the question is about that
     * room, so an answer about a different one is wrong however much better it
     * is. Null covers both "the room is idle" and "the room described
     * nothing", which are the same thing to a card — see the rule that an
     * answer describing nothing is never drawn.
     */
    fun inZone(zoneId: String): Playing? {
        // A SWITCHED-OFF ROOM ANSWERS NOTHING, and this is the line that makes
        // "no background processes" true rather than merely cosmetic: without
        // it a page left open from before the room was disabled — or a URL
        // typed by hand — still reaches the speaker on every refresh. The
        // filter is keyed on the id precisely so this costs no lookup.
        if (!zoneFilter(zoneId)) return null
        return ask(zoneId)?.takeIf { quality(it) >= 0 }
    }

    /** Ask whichever source owns this prefixed id. */
    private fun ask(zoneId: String): Playing? {
        val owner = ZoneRef.sourceOf(zoneId) ?: return null
        val source = sources.firstOrNull { it.name.equals(owner, ignoreCase = true) } ?: return null
        return runCatching { source.nowPlaying(ZoneRef.rawOf(zoneId)) }
            .onFailure { Log.w(TAG, "${source.name} would not answer for $zoneId: ${it.message}") }
            .getOrNull()
    }

    private companion object {
        const val TAG = "Sources"

        /** Both the album and the artist — nothing more is wanted. */
        const val FULL = 3
    }
}

/**
 * One room and whatever it is playing, which may be nothing.
 *
 * A room with no answer is NOT dropped: "not playing" is a fact the chooser
 * shows, and a grid that listed only the live rooms would look like the silent
 * ones had gone off the network.
 */
data class Room(val zone: ZoneRef, val playing: Playing?)
