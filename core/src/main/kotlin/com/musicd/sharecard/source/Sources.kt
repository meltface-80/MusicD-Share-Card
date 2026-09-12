package com.musicd.sharecard.source

import com.musicd.sharecard.Log

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
 * A user's choice does NOT beat a room that is actually playing when the chosen
 * one has fallen silent. The app's purpose is a card for what is on, and
 * answering with an hour-old paused album because that room was picked once is
 * the wrong answer to the question being asked.
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

    fun start() = sources.forEach { source ->
        runCatching { source.start() }
            .onFailure { Log.w(TAG, "${source.name} would not start: ${it.message}", it) }
    }

    fun stop() = sources.forEach { runCatching { it.stop() } }

    fun refresh() = sources.forEach { source ->
        runCatching { source.refresh() }
            .onFailure { Log.w(TAG, "${source.name} would not refresh: ${it.message}", it) }
    }

    fun zones(): List<ZoneRef> = sources.flatMap { source ->
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

    fun nowPlaying(preferId: String? = preferredZoneId): Playing? {
        // The chosen zone first, so the common case — one room, picked,
        // playing — costs one question to one source.
        val chosen = preferId?.let { ask(it) }
        if (chosen != null && chosen.state.isPlaying) return chosen

        for (source in sources) {
            val playing = runCatching { source.nowPlaying(null) }
                .onFailure { Log.w(TAG, "${source.name} would not answer: ${it.message}") }
                .getOrNull() ?: continue
            // Do not re-report the chosen zone as though it were a second
            // opinion; it was already asked and was not playing.
            if (playing.state.isPlaying && playing.zoneId != chosen?.zoneId) return playing
        }

        if (chosen != null && !chosen.isEmpty) return chosen

        // Nothing is playing anywhere. A paused room with a record still on its
        // transport is a better card than an empty screen.
        for (source in sources) {
            val playing = runCatching { source.nowPlaying(null) }.getOrNull() ?: continue
            if (!playing.isEmpty) return playing
        }
        return chosen
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
    }
}
